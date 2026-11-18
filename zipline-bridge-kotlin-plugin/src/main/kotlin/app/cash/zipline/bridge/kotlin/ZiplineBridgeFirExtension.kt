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
import org.jetbrains.kotlin.fir.declarations.utils.isCompanion
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirDeclarationPredicateRegistrar
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.extensions.NestedClassGenerationContext
import org.jetbrains.kotlin.fir.extensions.predicate.DeclarationPredicate
import org.jetbrains.kotlin.fir.extensions.predicateBasedProvider
import org.jetbrains.kotlin.fir.plugin.createCompanionObject
import org.jetbrains.kotlin.fir.plugin.createConstructor
import org.jetbrains.kotlin.fir.plugin.createMemberFunction
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

/** The companion member that builds an instance from a flag word set plus supplied arguments. */
internal val BRIDGE_CREATE_NAME: Name = Name.identifier("bridgeCreate")

/** Primitives cannot carry `null`, so a bridge parameter for one keeps its type. */
private val PRIMITIVE_CLASS_IDS = setOf(
  "kotlin.Boolean", "kotlin.Byte", "kotlin.Short", "kotlin.Int", "kotlin.Long",
  "kotlin.Float", "kotlin.Double", "kotlin.Char",
)

private val WITH_JS_2_HOST = FqName("app.cash.zipline.bridge.support.WithJS2HostBridge")
private val WITH_HOST_2_JS = FqName("app.cash.zipline.bridge.support.WithHost2JSBridge")

/**
 * Declares the construction factory that the generated host bridges call.
 *
 * The factory is a member of the annotated class's own companion object because a default-value
 * expression may call a `private` top-level declaration of the class's file or a `private` companion
 * member; nothing outside the class sees both. The body is filled in by
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
    classSymbol.hasOwnCompanion() -> emptySet()
    else -> setOf(SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT)
  }

  override fun generateNestedClassLikeDeclaration(
    owner: FirClassSymbol<*>,
    name: Name,
    context: NestedClassGenerationContext,
  ): FirClassLikeSymbol<*>? {
    if (name != SpecialNames.DEFAULT_NAME_FOR_COMPANION_OBJECT) return null
    if (!owner.needsBridgeCreate() || owner.hasOwnCompanion()) return null
    return createCompanionObject(owner, BridgeGeneratedKey).symbol
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
    return if (classSymbol.isBridgeCompanion()) setOf(BRIDGE_CREATE_NAME, SpecialNames.INIT)
    else setOf(BRIDGE_CREATE_NAME)
  }

  /** The companion object this extension generated, as opposed to one the source declared. */
  private fun FirClassSymbol<*>.isBridgeCompanion(): Boolean =
    (origin as? FirDeclarationOrigin.Plugin)?.key === BridgeGeneratedKey

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
    if (!companion.isBridgeCompanion()) return emptyList()
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

  /** The annotated class whose companion this is, or null when this is not a bridge companion. */
  private fun FirClassSymbol<*>.bridgeTargetClass(): FirClassSymbol<*>? =
    if (isCompanion) getContainingClassSymbol() as? FirClassSymbol<*> else null

  /** Only a class with something to default needs a factory: with none, every argument arrives. */
  @OptIn(SymbolInternals::class)
  private fun FirClassSymbol<*>.needsBridgeCreate(): Boolean {
    if (this !is FirRegularClassSymbol) return false
    if (fir.classKind == ClassKind.ENUM_CLASS) return false
    val annotated = session.predicateBasedProvider.matches(withJs2Host, this) ||
      session.predicateBasedProvider.matches(withHost2Js, this)
    if (!annotated) return false
    return primaryConstructorOfItsOwn()?.valueParameterSymbols.orEmpty().any { it.fir.defaultValue != null }
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
  private fun FirClassSymbol<*>.primaryConstructorOfItsOwn(): FirConstructorSymbol? =
    (fir as? FirRegularClass)
      ?.declarations
      ?.filterIsInstance<FirConstructor>()
      ?.singleOrNull { it.isPrimary }
      ?.symbol

  /** Whether the class already has a companion to put the factory in — again, not through a scope. */
  @OptIn(SymbolInternals::class, DirectDeclarationsAccess::class)
  private fun FirClassSymbol<*>.hasOwnCompanion(): Boolean =
    (fir as? FirRegularClass)?.declarations?.any { (it.symbol as? FirClassLikeSymbol<*>)?.isCompanion == true } == true

  /**
   * A bridge parameter must be able to say "absent": a reference type becomes nullable and `null`
   * carries the absence; a primitive cannot, so its flag bit carries it instead.
   */
  private fun ConeKotlinType.forBridgeParameter(): ConeKotlinType =
    if (classId?.asFqNameString() in PRIMITIVE_CLASS_IDS) this
    else withNullability(nullable = true, typeContext = session.typeContext)
}
