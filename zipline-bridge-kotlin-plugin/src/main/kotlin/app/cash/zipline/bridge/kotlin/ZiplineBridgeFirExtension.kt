package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.GeneratedDeclarationKey
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirConstructor
import org.jetbrains.kotlin.fir.declarations.FirDeclarationOrigin
import org.jetbrains.kotlin.fir.declarations.FirRegularClass
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
import org.jetbrains.kotlin.fir.plugin.createNestedClass
import org.jetbrains.kotlin.fir.resolve.getContainingClassSymbol
import org.jetbrains.kotlin.fir.symbols.SymbolInternals
import org.jetbrains.kotlin.fir.symbols.impl.FirClassLikeSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.coneTypeOrNull
import org.jetbrains.kotlin.fir.types.constructType
import org.jetbrains.kotlin.fir.types.typeContext
import org.jetbrains.kotlin.fir.types.withNullability
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames

/** Marks every declaration this plugin generates, so later phases can recognise them. */
internal object BridgeGeneratedKey : GeneratedDeclarationKey()

/** The member that builds an instance from a flag word set plus supplied arguments. */
internal val BRIDGE_CREATE_NAME: Name = Name.identifier("bridgeCreate")

/**
 * The nested object `bridgeCreate` lives in, for a class that has no companion of its own.
 *
 * Not the companion itself, which was the first attempt and which Redwood rejected: its payload
 * classes are `@Serializable`, and kotlinx.serialization generates a `Companion` of its own for
 * those, so two plugins produced a nested class of the same name in the same class and the compiler
 * answered `Multiple plugins generated nested class with same name Companion` -- or, in the
 * compilation where the clash was resolved by dropping ours, `Unresolved reference 'bridgeCreate'`.
 * A companion we generate is therefore a name we do not own, and any third-party plugin may claim
 * it. `Bridge` is a name this plugin owns.
 *
 * The factory therefore lives in exactly one place, named here, so the generators never have to
 * discover it: a class that declares a `Bridge` of its own is reported rather than quietly sharing
 * the name (see [declaresOwnBridgeContainer]).
 */
internal val BRIDGE_CONTAINER_NAME: Name = Name.identifier("Bridge")

/** Primitives cannot carry `null`, so a bridge parameter for one keeps its type. */
private val PRIMITIVE_CLASS_IDS = setOf(
  "kotlin.Boolean",
  "kotlin.Byte",
  "kotlin.Short",
  "kotlin.Int",
  "kotlin.Long",
  "kotlin.Float",
  "kotlin.Double",
  "kotlin.Char",
)

private val WITH_JS_2_HOST = FqName("app.cash.zipline.bridge.support.WithJS2HostBridge")
private val WITH_HOST_2_JS = FqName("app.cash.zipline.bridge.support.WithHost2JSBridge")

/**
 * Declares the construction factory that the generated host bridges call.
 *
 * The factory is a member of a classifier NESTED in the annotated class -- its companion when the
 * source declares one, otherwise a generated object named [BRIDGE_CONTAINER_NAME] -- because a
 * default-value expression may call a `private` top-level declaration of the class's file or a
 * `private` companion member; nothing outside the class sees both. The body is filled in by
 * [ZiplineBridgeIrGenerationExtension], which is where the real default expressions live: this phase
 * only states the signature, so that the bridge text the generators emit can name the function.
 *
 * The signature is `bridgeCreate(mask0: Int, …, maskN: Int, p1: T1, p2: T2?, …)`:
 *  - one `Int` per [MASK_BITS_PER_WORD] constructor parameters, carrying the "this parameter was not
 *    supplied" flags, in the packing the JVM's own masked constructor uses (see [maskWordCount]);
 *  - then every constructor parameter, in declaration order, with the type written on it. A reference
 *    type that declares a default becomes nullable, because `null` is how an absent argument arrives;
 *    a primitive keeps its type — it cannot hold `null`, so the caller passes a placeholder and the
 *    flag decides. A parameter without a default keeps its type either way: its argument always
 *    arrives.
 */
class ZiplineBridgeFirExtension(session: FirSession) : FirDeclarationGenerationExtension(session) {

  private val withJs2Host = DeclarationPredicate.create { annotated(WITH_JS_2_HOST) }
  private val withHost2Js = DeclarationPredicate.create { annotated(WITH_HOST_2_JS) }

  override fun FirDeclarationPredicateRegistrar.registerPredicates() {
    register(withJs2Host)
    register(withHost2Js)
  }

  override fun getNestedClassifiersNames(
    classSymbol: FirClassSymbol<*>,
    context: NestedClassGenerationContext,
  ): Set<Name> = when {
    !classSymbol.needsBridgeCreate() -> emptySet()

    // A `Bridge` the source declares itself is left alone; the IR pass reports the clash, because
    // this phase has no message collector to report it with.
    classSymbol.declaresOwnBridgeContainer() -> emptySet()

    else -> setOf(BRIDGE_CONTAINER_NAME)
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    if (name != BRIDGE_CONTAINER_NAME) return null
    if (!owner.needsBridgeCreate() || owner.declaresOwnBridgeContainer()) return null
    // A plain nested object, deliberately not a companion: see BRIDGE_CONTAINER_NAME.
    return createNestedClass(owner, BRIDGE_CONTAINER_NAME, BridgeGeneratedKey, ClassKind.OBJECT) {}.symbol
  }

  override fun getCallableNamesForClass(
    classSymbol: FirClassSymbol<*>,
    context: MemberGenerationContext,
  ): Set<Name> {
    val target = classSymbol.bridgeTargetClass() ?: return emptySet()
    if (!target.needsBridgeCreate()) return emptySet()
    // A companion this extension generated also claims `<init>` (SpecialNames.INIT): `generateConstructors`
    // is only asked about names the extension claims, and an object without a constructor fails JVM
    // lowering with "Object should have a primary constructor".
    return if (classSymbol.isBridgeGeneratedContainer()) {
      setOf(BRIDGE_CREATE_NAME, SpecialNames.INIT)
    } else {
      setOf(BRIDGE_CREATE_NAME)
    }
  }

  /** The nested object this extension generated, as opposed to a companion the source declared. */
  private fun FirClassSymbol<*>.isBridgeGeneratedContainer(): Boolean = (origin as? FirDeclarationOrigin.Plugin)?.key === BridgeGeneratedKey

  /**
   * The generated companion is an object, and the JVM backend insists that every object has a
   * primary constructor: without one, lowering fails with "Object should have a primary constructor".
   * A companion the source already had brings its own.
   */
  override fun generateConstructors(context: MemberGenerationContext): List<FirConstructorSymbol> {
    val companion = context.owner
    // Only the companion this extension generates: a companion the source declared brings its own
    // constructor, and the check cannot be "does the class have a companion" — by now it does, and it
    // is the one being asked about.
    if (!companion.isBridgeGeneratedContainer()) return emptyList()
    // A primary constructor with nothing in it: an object needs one, and this is all it needs.
    val ctor = createConstructor(
      owner = companion,
      key = BridgeGeneratedKey,
      isPrimary = true,
      generateDelegatedNoArgConstructorCall = true,
    )
    return listOf(ctor.symbol)
  }

  @OptIn(SymbolInternals::class)
  override fun generateFunctions(
    callableId: CallableId,
    context: MemberGenerationContext?,
  ): List<FirNamedFunctionSymbol> {
    if (callableId.callableName != BRIDGE_CREATE_NAME) return emptyList()
    val owner = context?.owner ?: return emptyList()
    val target = owner.bridgeTargetClass() ?: return emptyList()
    if (!target.needsBridgeCreate() || owner !is FirRegularClassSymbol) return emptyList()
    val ctor = target.primaryConstructorOfItsOwn() ?: return emptyList()

    // A parameter whose type is not resolved yet cannot be given a signature.
    val parameterTypes = ctor.valueParameterSymbols.map { parameter ->
      parameter.name to parameter.fir.returnTypeRef.coneTypeOrNull?.forBridgeParameter()
    }
    val unresolved = parameterTypes.firstNotNullOfOrNull { (name, type) -> name.takeIf { type == null } }
    if (unresolved != null) {
      // Never happens at the phases this extension runs at; the plugin tests pin that. If a future
      // Kotlin moves declaration generation earlier, this names the class and parameter rather than
      // quietly widening every parameter to `Any?` and producing wrong values at run time.
      error(
        "The Zipline bridge compiler plugin could not read the type of parameter `$unresolved` of " +
          "${target.classId?.asFqNameString() ?: "<local>"} when declaring `bridgeCreate`. " +
          "This is a bug in the plugin, not in the annotated source.",
      )
    }

    val intType = session.builtinTypes.intType.coneType
    return listOf(
      createMemberFunction(
        owner,
        BridgeGeneratedKey,
        BRIDGE_CREATE_NAME,
        target.constructType(emptyArray(), false),
      ) {
        visibility = Visibilities.Public
        modality = Modality.FINAL
        repeat(maskWordCount(parameterTypes.size)) { word ->
          valueParameter(Name.identifier("mask$word"), intType)
        }
        parameterTypes.forEach { (name, type) -> valueParameter(name, type!!) }
      }.symbol,
    )
  }

  /** The annotated class that owns the generated container; null for any other classifier. */
  private fun FirClassSymbol<*>.bridgeTargetClass(): FirClassSymbol<*>? = if (isBridgeGeneratedContainer()) getContainingClassSymbol() as? FirClassSymbol<*> else null

  /**
   * Every bridged class with a primary constructor of its own gets the factory.
   *
   * This used to require at least one default, on the reasoning that with none every argument
   * arrives. That held for the constructor call and missed the bridge: the host->guest path stamps an
   * object from the guest's prototype, so a class whose constructor computes a property, runs an
   * initializer or validates an argument was handed an instance that skipped its own setup — defaults
   * or not. With the guest constructing through this factory, the constructor runs either way.
   */
  @OptIn(SymbolInternals::class)
  private fun FirClassSymbol<*>.needsBridgeCreate(): Boolean {
    if (this !is FirRegularClassSymbol) return false
    if (fir.classKind == ClassKind.ENUM_CLASS || fir.classKind == ClassKind.OBJECT) return false
    val annotated = session.predicateBasedProvider.matches(withJs2Host, this) ||
      session.predicateBasedProvider.matches(withHost2Js, this)
    if (!annotated) return false
    return primaryConstructorOfItsOwn() != null
  }

  /**
   * The primary constructor, read from the class's own declaration list.
   *
   * The obvious call, [primaryConstructorIfAny](org.jetbrains.kotlin.fir.declarations), goes through
   * the class's member scope — and the compiler is asking us for members precisely because it is
   * building that scope. Asking it back never returns: the recursion overflows the stack inside
   * `FirDeclaredMemberScopeProvider`. The declaration list is the class's own data, so reading it is
   * not a question to the scope, and `DirectDeclarationsAccess` is the sanctioned way to read it.
   */
  @OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)
  private fun FirClassSymbol<*>.primaryConstructorOfItsOwn(): FirConstructorSymbol? = (fir as? FirRegularClass)
      ?.declarations
      ?.filterIsInstance<FirConstructor>()
      ?.singleOrNull { it.isPrimary }
      ?.symbol

  /**
   * Whether the source declares a nested `Bridge` of its own, which the factory must not displace —
   * read from the declaration list, not through a scope: the compiler is asking us for members
   * precisely because it is building that scope.
   */
  @OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)
  private fun FirClassSymbol<*>.declaresOwnBridgeContainer(): Boolean = (fir as? FirRegularClass)?.declarations
      ?.filterIsInstance<FirRegularClass>()
      ?.any { it.name == BRIDGE_CONTAINER_NAME && !it.symbol.isBridgeGeneratedContainer() } == true

  /**
   * A bridge parameter must be able to say "absent": a reference type becomes nullable and `null`
   * carries the absence; a primitive cannot, so its flag bit carries it instead.
   */
  private fun ConeKotlinType.forBridgeParameter(): ConeKotlinType = if (classId?.asFqNameString() in PRIMITIVE_CLASS_IDS) {
      this
    } else {
      withNullability(nullable = true, typeContext = session.typeContext)
    }
}
