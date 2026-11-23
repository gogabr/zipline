package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBlockBodyBuilder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.IrValueParameterBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irEquals
import org.jetbrains.kotlin.ir.builders.irEqualsNull
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetObject
import org.jetbrains.kotlin.ir.builders.irIfThen
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irImplicitCast
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irUnit
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.declarations.impl.IrVariableImpl
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.impl.IrCatchImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrStringConcatenationImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrTryImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrVariableSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * A resolved bridge-registration entry point. [isRaw] is true for the `__bridgeRegister` /
 * `__bridgeRegisterRuntime` externals this plugin synthesizes, false for zipline's tolerant
 * `registerBridge`, which guards the global itself. Call sites use it to decide whether the
 * call needs a swallowing `try` around it.
 */
internal class BridgeRegisterTarget(
  val symbol: IrSimpleFunctionSymbol,
  val isRaw: Boolean,
)

/**
 * Returns [call] wrapped so a missing host cannot break the guest: when the callee is a raw
 * bridge external, a JS runtime with no host installed raises `ReferenceError` on the missing
 * global, and that must be swallowed rather than escape. Escaping would kill the companion
 * initializer (and with it the module's class initialization) or the module-load hook.
 *
 * The catch parameter is typed `Throwable` so the JS backend emits `instanceof Error`, which
 * covers `ReferenceError` and `TypeError`. The body is `Unit`, so the try is a statement like
 * the call it wraps.
 *
 * [raw] false returns [call] untouched: the tolerant `registerBridge` already checks
 * `typeof globalThis.__bridgeRegister === 'function'`, and wrapping it again would only hide
 * errors that helper is meant to propagate.
 */
internal fun IrBuilderWithScope.tolerantBridgeCall(
  call: IrExpression,
  raw: Boolean,
  parent: IrDeclarationParent,
): IrExpression {
  if (!raw) return call
  val builtIns = context.irBuiltIns
  val catchParameter = IrVariableImpl(
    UNDEFINED_OFFSET,
    UNDEFINED_OFFSET,
    IrDeclarationOrigin.CATCH_PARAMETER,
    IrVariableSymbolImpl(),
    Name.identifier("e"),
    builtIns.throwableType,
    isVar = false,
    isConst = false,
    isLateinit = false,
  ).apply {
    this.parent = parent
  }
  val catchClause = IrCatchImpl(
    UNDEFINED_OFFSET,
    UNDEFINED_OFFSET,
    catchParameter,
    irUnit(),
    null,
  )
  return IrTryImpl(
    UNDEFINED_OFFSET,
    UNDEFINED_OFFSET,
    builtIns.unitType,
    call,
    listOf(catchClause),
    null,
  )
}

// -- JS IR transformations (bridge_dispatch injection) --

internal fun injectCompanionInitBlocks(
  finder: DeclarationFinder,
  moduleFragment: IrModuleFragment,
  dispatchClasses: List<IrClass>,
  pluginContext: IrPluginContext,
) {
  val fileForModule = moduleFragment.files.firstOrNull() ?: return

  // Find KClass.js property getter (kotlin.js.KClass.js → JsClass<T>).
  // Uses pure IR APIs (findProperties, parameters, IrParameterKind) — no descriptors.
  val kclassJsPropId = CallableId(FqName("kotlin.js"), null, Name.identifier("js"))
  val kclassJsProperties = finder.findProperties(kclassJsPropId)
  val kclassJsGetterFn = kclassJsProperties
    .mapNotNull { it.owner.getter }
    .firstOrNull { getter ->
      val firstParam = getter.parameters.firstOrNull()
      firstParam?.kind == IrParameterKind.ExtensionReceiver &&
        firstParam.type.getClass()?.classId?.asSingleFqName()?.asString() == "kotlin.reflect.KClass"
    } ?: error("KClass.js property getter not found")
  val kclassJsGetterSymbol = kclassJsGetterFn.symbol

  // Companion constructors register through the same symbol the module-load hook uses: zipline's
  // tolerant `registerBridge` when it is on the classpath, otherwise the raw `__bridgeRegister`
  // external. See findOrCreateBridgeRegister.
  val bridgeRegister =
    findOrCreateBridgeRegister(finder, pluginContext, fileForModule, moduleFragment.name.asString())

  for (clazz in dispatchClasses) {
    val ownFqn = clazz.fqNameWhenAvailable?.asString() ?: continue
    val targetFqn = resolveTargetFqn(clazz) ?: ownFqn

    if (clazz.isCompanion || clazz.kind == ClassKind.OBJECT) {
      injectBridgeIntoConstructor(
        clazz,
        targetFqn,
        clazz,
        kclassJsGetterSymbol,
        bridgeRegister,
        pluginContext,
      )
      continue
    }

    // Regular class: inject bridge registration into companion constructor.
    var companion = clazz.declarations.filterIsInstance<IrClass>().firstOrNull { it.isCompanion }
    if (companion == null) {
      companion = createCompanion(clazz, pluginContext)
    }

    injectBridgeIntoConstructor(
      companion,
      targetFqn,
      clazz,
      kclassJsGetterSymbol,
      bridgeRegister,
      pluginContext,
    )
  }
}

/**
 * Inserts the bridge registration into the companion's primary constructor, equivalent to:
 * `registerBridge("targetFqn", Foo::class.js)`.
 *
 * IrClassReferenceImpl is used directly as the extension receiver for kClass.js —
 * no deprecated IrGetValue or parameter references needed.
 *
 * When [bridgeRegister] is the raw `__bridgeRegister` external (no tolerant `registerBridge`
 * on the classpath) the call is wrapped in a `try`/`catch` that swallows: a guest loaded into a
 * JS runtime with no host installed must still load, and a `ReferenceError` out of a companion
 * initializer would take the whole module down with it.
 */
internal fun injectBridgeIntoConstructor(
  companion: IrClass,
  targetFqn: String,
  clazz: IrClass,
  kclassJsGetterSymbol: IrSimpleFunctionSymbol,
  bridgeRegister: BridgeRegisterTarget,
  pluginContext: IrPluginContext,
) {
  val ctor = companion.declarations.filterIsInstance<IrConstructor>()
    .firstOrNull { it.isPrimary } ?: return
  val builder = pluginContext.irBuiltIns.createIrBuilder(ctor.symbol)

  // Foo::class
  val classRef = IrClassReferenceImpl(
    UNDEFINED_OFFSET,
    UNDEFINED_OFFSET,
    pluginContext.irBuiltIns.kClassClass.starProjectedType,
    clazz.symbol,
    clazz.defaultType,
  )

  // Foo::class.js — calls the KClass.js extension property getter at IR level
  val jsCtorCall = builder.irCall(kclassJsGetterSymbol).apply {
    insertExtensionReceiver(classRef)
  }

  // registerBridge("targetFqn", Foo::class.js); swallowed when the raw external has no host.
  val bridgeCall = builder.tolerantBridgeCall(
    call = builder.irCall(bridgeRegister.symbol).apply {
      arguments[0] = builder.irString(targetFqn)
      arguments[1] = jsCtorCall
    },
    raw = bridgeRegister.isRaw,
    parent = ctor,
  )

  val body = ctor.body
  if (body is IrBlockBody) {
    body.statements.add(1, bridgeCall)
  } else {
    val superCall = (body as? IrExpressionBody)?.expression
      ?: builder.irDelegatingConstructorCall(
        pluginContext.irBuiltIns.anyClass.owner.declarations
          .filterIsInstance<IrConstructor>()
          .first { it.isPrimary },
      )
    ctor.body = builder.irBlockBody {
      +superCall
      +bridgeCall
    }
  }
}

/** Creates a synthetic companion object on [clazz]. */
internal fun createCompanion(clazz: IrClass, pluginContext: IrPluginContext): IrClass {
  val companion = pluginContext.irFactory.buildClass {
    name = Name.identifier("Companion")
    kind = ClassKind.OBJECT
    modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
    visibility = DescriptorVisibilities.PUBLIC
    origin = IrDeclarationOrigin.DEFINED
    isCompanion = true
  }
  companion.parent = clazz
  companion.createThisReceiverParameter()

  // Kotlin/JS only generates the singleton factory (with createThis + assignment
  // to Companion_instance) for objects that have a primary constructor with a
  // non-empty body. Add a primary constructor; injectBridgeIntoConstructor will
  // fill the body with the delegating super() call and the bridge registration.
  companion.addConstructor {
    visibility = DescriptorVisibilities.PRIVATE
    isPrimary = true
  }

  clazz.declarations += companion
  return companion
}

// -- array extraction helpers --

// -- @JsName annotation helper --

internal fun addJsNameAnnotation(
  property: IrProperty,
  name: String,
  jsNameCtor: IrConstructor,
  stringType: IrType,
) {
  val nameExpr = IrConstImpl.string(
    UNDEFINED_OFFSET,
    UNDEFINED_OFFSET,
    stringType,
    name,
  )
  val annotation = IrConstructorCallImpl(
    UNDEFINED_OFFSET,
    UNDEFINED_OFFSET,
    jsNameCtor.returnType,
    jsNameCtor.symbol,
    0,
    1,
  )
  annotation.arguments[0] = nameExpr
  property.annotations += annotation
}

// -- Host2JS module-load registration (prototypes + runtime factories) --

/**
 * Register every @WithHost2JSBridge class with the host when the guest module loads, plus the
 * synthetic `__BridgeRuntimeFactories` object whose methods the host uses to build real
 * Kotlin/JS `kotlin.Long`, `ArrayList`, and `LinkedHashMap` instances.
 *
 * The host needs a class prototype before it can build an event payload, and payload classes
 * that only ever originate host-side (e.g. a reuse host's `LayoutMetadata`) are never
 * constructed guest-side. Companion-injected registration was therefore demand-driven
 * (Kotlin/JS initializes a class on first use) and clients had to warm the payload classes up by
 * hand at launch, keeping the list in sync with every new payload type.
 *
 * Kotlin/JS cannot do this eagerly on its own: file-level property initializers (including
 * synthetic ones) only run when some declaration of that file is first touched. So the plugin
 * emits a module-level `@JsExport` hook instead — `__bridgeWarmUpHost2Js()` — which performs
 * `__bridgeRegister("<fq>", X::class.js)` per class plus a single `__bridgeRegisterRuntime(...)`,
 * and the host calls that hook from `loadJsModule` right after the module is defined (before the
 * application runs, hence before any host→JS conversion).
 *
 * The factory methods are plain Kotlin (internal, IR-built bodies) that construct real
 * instances via public stdlib APIs (`Long(low, high)`, `toCollection`, `zip`/`toMap`) — no
 * reliance on internal layout or mangled field names.
 */
internal fun injectModuleLoadBridgeRegistration(
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
  moduleFragment: IrModuleFragment,
  host2JsClasses: List<IrClass>,
  js2HostOnlyClasses: List<IrClass>,
) {
  // Every module whose payloads cross the bridge publishes the value ops, whether they travel
  // guest->host (@WithJS2HostBridge) or host->guest (@WithHost2JSBridge).
  if (host2JsClasses.isEmpty() && js2HostOnlyClasses.isEmpty()) return
  val fileForModule = moduleFragment.files.firstOrNull() ?: return
  // Guest-side value accessors for the host (collections, Longs, enums): Kotlin/JS mangles
  // stdlib member names, so the host cannot read those values on its own. Published from the
  // module-load hook below. Absent here, such values fail loudly with the class name.
  val valueOpsSymbol = finder.findFunctions(
    CallableId(FqName("app.cash.zipline"), Name.identifier("publishValueOps")),
  ).firstOrNull()

  val kclassJsGetterSymbol = findKClassJsGetter(finder)
  val bridgeRegister =
    findOrCreateBridgeRegister(finder, pluginContext, fileForModule, moduleFragment.name.asString())
  val bridgeRegisterRuntime = findOrCreateBridgeRegisterRuntime(
    finder,
    pluginContext,
    fileForModule,
    moduleFragment.name.asString(),
  )
  val factoriesClass =
    buildRuntimeFactories(finder, pluginContext, fileForModule, host2JsClasses, moduleFragment.name.asString())

  // `@JsExport fun __bridgeWarmUpHost2Js(): Boolean` — the host calls this from loadJsModule
  // right after the module is defined (see BRIDGE_WARM_UP_JS host-side). Being exported keeps it
  // reachable from the host and immune to dead-code elimination.
  // The Kotlin name carries a module key, because two modules that share a package would otherwise
  // publish one signature (see [bridgeDeclarationName]); the host searches the exports for the fixed
  // name below, so @JsName pins it back.
  val warmUpFn = pluginContext.irFactory.buildFun {
    name = Name.identifier(warmUpFunctionName(moduleFragment.name.asString()))
    returnType = pluginContext.irBuiltIns.booleanType
    visibility = DescriptorVisibilities.PUBLIC
    origin = IrDeclarationOrigin.DEFINED
  }
  warmUpFn.parent = fileForModule
  val jsExportClass = finder.findClass(
    ClassId(FqName("kotlin.js"), Name.identifier("JsExport")),
  )?.owner
  if (jsExportClass != null) {
    val jsExportCtor = jsExportClass.declarations.filterIsInstance<IrConstructor>()
      .firstOrNull { it.isPrimary }
    if (jsExportCtor != null) {
      warmUpFn.annotations += IrConstructorCallImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        jsExportCtor.returnType,
        jsExportCtor.symbol,
        0,
        0,
      )
    }
  }
  addJsNameToFunction(warmUpFn, BRIDGE_WARM_UP_FUNCTION_NAME, finder, pluginContext)

  val builder = pluginContext.irBuiltIns.createIrBuilder(warmUpFn.symbol)
  warmUpFn.body = builder.irBlockBody {
    if (valueOpsSymbol != null) {
      // Guest answers the value-type question and drives the access for the host; see
      // BridgeValueOps (zipline jsMain). Kotlin/JS mangles stdlib member names, so the host
      // cannot read collections/Longs/enums on its own.
      +irCall(valueOpsSymbol)
    }
    for (clazz in host2JsClasses) {
      val ownFqn = clazz.fqNameWhenAvailable?.asString() ?: continue
      val targetFqn = resolveTargetFqn(clazz) ?: ownFqn
      val classRef = IrClassReferenceImpl(
        UNDEFINED_OFFSET,
        UNDEFINED_OFFSET,
        pluginContext.irBuiltIns.kClassClass.starProjectedType,
        clazz.symbol,
        clazz.defaultType,
      )
      +tolerantBridgeCall(
        call = irCall(bridgeRegister.symbol).apply {
          arguments[0] = irString(targetFqn)
          arguments[1] = irCall(kclassJsGetterSymbol).apply {
            insertExtensionReceiver(classRef)
          }
        },
        raw = bridgeRegister.isRaw,
        parent = warmUpFn,
      )
    }
    +tolerantBridgeCall(
      call = irCall(bridgeRegisterRuntime.symbol).apply {
        // The object's instance, not a value we construct: this is what an object access compiles to.
        // The constructor still runs -- the backend calls it from the singleton accessor -- but the call
        // site no longer synthesises a construction the backend may or may not be able to route.
        arguments[0] = IrGetObjectValueImpl(
          UNDEFINED_OFFSET,
          UNDEFINED_OFFSET,
          factoriesClass.defaultType,
          factoriesClass.symbol,
        )
      },
      raw = bridgeRegisterRuntime.isRaw,
      parent = warmUpFn,
    )
    +irReturn(irBoolean(true))
  }

  fileForModule.declarations += warmUpFn
}

/** Name of the exported hook the host calls to register every @WithHost2JSBridge class. */
internal const val BRIDGE_WARM_UP_FUNCTION_NAME: String = "__bridgeWarmUpHost2Js"

/**
 * The name of [moduleName]'s runtime-factories object.
 *
 * The module key is not decoration. Synthetic module-level declarations are signed with the package of
 * the module's first source file, and two Clive modules share a package: both `:wb:nodes:main:common`
 * and `:wb:nodes:design-system:common` declare in `ru.wildberries.clive.compose`. Both therefore
 * published `ru.wildberries.clive.compose/__BridgeRuntimeFactories`, and `:wb:live`'s guest bundle
 * requires both, so the JS backend met one signature twice while loading the IC caches -- it keys
 * declarations by signature across the whole link and assumes a repeated signature is the same
 * declaration:
 *
 * ```
 * e: java.lang.IllegalStateException: IrClassSymbolImpl is already bound.
 *    Signature: ru.wildberries.clive.compose/__BridgeRuntimeFactories|null[0]
 * ```
 *
 * Nothing the host does depends on this name: it merges the object's *members* into its factories
 * global and reaches each one through its `@JsName`-pinned name, so a module-unique name is free. The
 * key is a hash of the module name rather than the name itself because module names carry `:` and `-`,
 * and it is stable because the module name is the only input.
 */
internal fun bridgeDeclarationName(base: String, moduleName: String): String {
  val digest = moduleName.hashCode().toULong().toString(16)
  val readable = moduleName.map { if (it.isLetterOrDigit()) it else '_' }
    .joinToString("")
    .trim('_')
    .take(24)
  return if (readable.isEmpty()) "${base}_$digest" else "${base}$readable-$digest".replace('-', '_')
}

/** See [bridgeDeclarationName]: the Kotlin name is keyed, the JS name is not, so the host is unaffected. */
internal fun runtimeFactoriesClassName(moduleName: String): String = bridgeDeclarationName("__BridgeRuntimeFactories", moduleName)

/** [BRIDGE_WARM_UP_FUNCTION_NAME] with a module key: the host finds the hook by its JS name. */
internal fun warmUpFunctionName(moduleName: String): String = bridgeDeclarationName(BRIDGE_WARM_UP_FUNCTION_NAME, moduleName)

/**
 * Build the synthetic internal `object __BridgeRuntimeFactories` with the three factory
 * methods the host calls (by name, from the object's prototype) to construct real Kotlin/JS
 * Long/ArrayList/LinkedHashMap instances.
 */
@Suppress("DEPRECATION_ERROR")
private fun buildRuntimeFactories(
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
  fileForModule: IrFile,
  host2JsClasses: List<IrClass>,
  moduleName: String,
): IrClass {
  val factoriesClass = pluginContext.irFactory.buildClass {
    name = Name.identifier(runtimeFactoriesClassName(moduleName))
    kind = ClassKind.OBJECT
    modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
    visibility = DescriptorVisibilities.INTERNAL
    origin = IrDeclarationOrigin.DEFINED
  }
  factoriesClass.parent = fileForModule
  // An object, not a class: nothing has to be *constructed* for the host to get its factories, which
  // removes the whole class of defect where the JS backend decides to route construction through a
  // generated accessor (it does, for ES classes -- see the note on the constructor below). The earlier
  // note here claimed a synthetic object's singleton accessor "proved unreliable in the JS backend";
  // that was never measured, and it was load-bearing: it is why this was a class at all. What it
  // actually described is the ES5-only coverage of every test bundle, which hides the accessor route
  // entirely -- see `build the skew pair the way the app is built`.
  factoriesClass.createThisReceiverParameter()
  factoriesClass.addConstructor {
    visibility = DescriptorVisibilities.PRIVATE
    isPrimary = true
  }
  // The object route and this body are a pair; the object on its own buys nothing. The singleton
  // accessor lowers to a call to the very same static accessor:
  //
  //     function __BridgeRuntimeFactories_getInstance() {
  //       if (__BridgeRuntimeFactories_instance === VOID)
  //         __BridgeRuntimeFactories.new_..._vf6i4j_k$();  // empty body: no instance is ever created
  //       return __BridgeRuntimeFactories_instance;
  //     }
  //
  // so with a body-less constructor the object route fails exactly as the class route did -- which is
  // the whole content of the old note here claiming a synthetic object's singleton accessor was
  // "unreliable in the JS backend". What the object does buy is that the call site stops constructing
  // anything by hand: the registration reads the instance (IrGetObjectValueImpl below) and the backend
  // owns creation, in one place, where it can get it right.
  val singletonCtor = factoriesClass.declarations.filterIsInstance<IrConstructor>()
    .first { it.isPrimary }
  singletonCtor.body =
    pluginContext.irBuiltIns.createIrBuilder(singletonCtor.symbol).irBlockBody {
      +irDelegatingConstructorCall(
        pluginContext.irBuiltIns.anyClass.owner.primaryConstructor
          ?: error("kotlin.Any primary constructor not found"),
      )
    }
  // This constructor needs a real body, and that is not obvious, so the failure mode is written out.
  // The JS backend creates instances of this internal class through a generated static accessor, and
  // the accessor body IS this constructor body. Given nothing, the accessor is emitted empty:
  //
  //     static new_io_...___BridgeRuntimeFactories_t6f8x1_k$($box) {
  //     }                                    <- returns undefined, assigns nothing
  //
  // which is what made compose-live report prototypes registered and no factories global. An empty
  // `irBlockBody { }` is not enough either: the JS backend then fails outright on this class, with
  // "Back-end: Please report this problem" naming `internal class __BridgeRuntimeFactories`. What works
  // is a body that delegates to the superclass constructor, which is what every real primary
  // constructor of a concrete class has; the accessor then lowers correctly, including the singleton
  // assignment the object route relies on.
  //
  // Why nothing caught this for so long: only the ES-class lowering generates the accessor. Every test
  // bundle was ES5, where construction lowers inline as `new __BridgeRuntimeFactories()` and a
  // body-less constructor is harmless. The two changes that fixed that -- building the skew pair with
  // ES classes, and refusing to load a module whose factories arrive empty -- reproduce and detect this
  // in about fifteen seconds.

  val longClass = finder.findClass(ClassId(FqName("kotlin"), Name.identifier("Long")))?.owner
    ?: error("kotlin.Long class not found")
  val longCtor = longClass.declarations.filterIsInstance<IrConstructor>()
    .firstOrNull { it.isPrimary }
    ?: error("kotlin.Long constructor not found")
  val arrayListClass = finder.findClass(ClassId(FqName("kotlin.collections"), Name.identifier("ArrayList")))!!.owner
  val arrayListNoArgCtor = arrayListClass.declarations.filterIsInstance<IrConstructor>()
    .firstOrNull { it.parameters.isEmpty() }
    ?: error("ArrayList() constructor not found")
  val linkedHashMapClass = finder.findClass(ClassId(FqName("kotlin.collections"), Name.identifier("LinkedHashMap")))!!.owner
  // The (Map) copy constructor specifically — LinkedHashMap also has a one-arg (Int) capacity
  // constructor, and passing a map to it yields a bogus capacity.
  val linkedHashMapMapCtor = linkedHashMapClass.declarations.filterIsInstance<IrConstructor>()
    .firstOrNull {
      it.parameters.size == 1 &&
        it.parameters[0].type.getClass()?.fqNameWhenAvailable?.asString() == "kotlin.collections.Map"
    }
    ?: error("LinkedHashMap(Map) constructor not found")
  val toCollection = resolveExtensionFun(finder, "kotlin.collections", "toCollection") {
    it.parameters.size == 2 &&
      it.parameters[0].type.classOrNull?.owner?.fqNameWhenAvailable?.asString() == "kotlin.Array"
  }
  val zip = resolveExtensionFun(finder, "kotlin.collections", "zip") {
    it.parameters.size == 2 &&
      it.parameters[0].type.classOrNull?.owner?.fqNameWhenAvailable?.asString() == "kotlin.Array" &&
      it.parameters[1].type.classOrNull?.owner?.fqNameWhenAvailable?.asString() == "kotlin.Array"
  }
  val toMap = resolveExtensionFun(finder, "kotlin.collections", "toMap") {
    it.parameters.size == 1 &&
      it.parameters[0].type.classOrNull?.owner?.fqNameWhenAvailable?.asString() == "kotlin.collections.Iterable"
  }

  val anyNType = pluginContext.irBuiltIns.anyNType
  // MARKER_9F3A typeWith, not defaultType: stdlib klib classes have a null thisReceiver, so defaultType NPEs.
  val anyListType = arrayListClass.typeWith(anyNType)
  val anyMapType = linkedHashMapClass.typeWith(anyNType, anyNType)

  buildObjectMethod(
    finder,
    pluginContext,
    factoriesClass,
    "newLong",
    pluginContext.irBuiltIns.longType,
    listOf(pluginContext.irBuiltIns.intType, pluginContext.irBuiltIns.intType),
  ) { builder, params ->
    builder.irCall(longCtor).apply {
      arguments[0] = builder.irGet(params[0])
      arguments[1] = builder.irGet(params[1])
    }
  }

  buildObjectMethod(
    finder,
    pluginContext,
    factoriesClass,
    "newArrayList",
    anyListType,
    listOf(pluginContext.irBuiltIns.arrayClass.typeWith(anyNType)),
  ) { builder, params ->
    builder.irCall(toCollection).apply {
      typeArguments[0] = anyNType
      typeArguments[1] = anyListType
      arguments[0] = builder.irGet(params[0])
      arguments[1] = builder.irCall(arrayListNoArgCtor).apply {
        typeArguments[0] = anyNType
      }
    }
  }

  buildObjectMethod(
    finder,
    pluginContext,
    factoriesClass,
    "newLinkedHashMap",
    anyMapType,
    listOf(
      pluginContext.irBuiltIns.arrayClass.typeWith(anyNType),
      pluginContext.irBuiltIns.arrayClass.typeWith(anyNType),
    ),
  ) { builder, params ->
    builder.irCall(linkedHashMapMapCtor).apply {
      typeArguments[0] = anyNType
      typeArguments[1] = anyNType
      arguments[0] = builder.irCall(toMap).apply {
        typeArguments[0] = anyNType
        typeArguments[1] = anyNType
        arguments[0] = builder.irCall(zip).apply {
          typeArguments[0] = anyNType
          typeArguments[1] = anyNType
          arguments[0] = builder.irGet(params[0])
          arguments[1] = builder.irGet(params[1])
        }
      }
    }
  }

  // Host->guest create-factories: `create_<fqn>` for every bridged class, on the same instance the
  // host already reaches for. The host builds a plain `{}` of the fields it owns and calls the
  // factory; the factory runs the guest's REAL constructor through `bridgeCreate`, so the class's own
  // initializers, computed properties and validation run in the guest instead of being skipped by a
  // field-stamped lookalike. Not only the classes with defaults get one: a class whose constructor
  // does work is just as broken when it happens to declare no defaults.
  for (clazz in host2JsClasses) {
    val ownFqn = clazz.fqNameWhenAvailable?.asString() ?: continue
    if (!host2JsNeedsCreateFactory(clazz)) continue
    val targetFqn = resolveTargetFqn(clazz) ?: ownFqn
    when {
      clazz.kind == ClassKind.OBJECT -> buildSingletonFactory(finder, pluginContext, factoriesClass, clazz, targetFqn)
      clazz.kind == ClassKind.ENUM_CLASS -> buildEnumFactory(finder, pluginContext, factoriesClass, clazz, targetFqn)
      else -> buildCreateFactory(finder, pluginContext, factoriesClass, clazz, targetFqn)
    }
  }

  fileForModule.declarations += factoriesClass
  return factoriesClass
}

/** The JS name of [fq]'s host->guest create-factory; `@JsName` pins it so the host can look it up. */
internal fun createFactoryJsName(fq: String): String = "create_" + fq.replace('.', '_')

/**
 * Whether the guest will have a `create_<fqn>` for [clazz]. Both sides ask this — the guest when it
 * emits the factory, the host when it chooses between calling the factory and stamping a prototype —
 * so it is a function of the class's shape alone, never of what a given compilation happens to
 * contain. Enums cross as ordinals and singletons have no construction to run, so neither needs one.
 */
internal fun host2JsNeedsCreateFactory(clazz: IrClass): Boolean {
  // Enums cross as ordinals and the factory resolves the guest's own singleton entry, so they get a
  // factory like any other payload. A companion has no construction to run and no payload shape.
  if (clazz.kind == ClassKind.ENUM_CLASS) return true
  if (clazz.isCompanion) return false
  // An `object` has no constructor at all, which is why the check below comes after it: its factory
  // hands over the singleton rather than calling anything. See [buildSingletonFactory].
  if (clazz.kind == ClassKind.OBJECT) return true
  val constructor = clazz.primaryConstructor ?: return false
  // A `@JvmInline value class` gets a factory like any other class, on both sides of the field: as a
  // payload, and as a field of one. It used to be declined here on the theory that the host sends a box
  // while `bridgeCreate` wants the underlying value — but the host's box is itself converted through
  // this same path, so by the time the outer factory reads the field the guest has already built that
  // value class from the host's field values. Declining it instead left the shape with no way across:
  // the value crossed before only because stamping skipped the constructor, which is what this path
  // exists not to do. `Dp` (Redwood) and `FontWeight`, `TextUnit` (compose-live) are bridged value
  // classes used as fields, so declining was not a narrowing, it was a break.
  return true
}

/**
 * The factory for an `object` payload: it hands over the guest's own singleton.
 *
 * An `object` has no constructor to run, so "the guest builds it" can only mean "the guest gives up its
 * one instance" — and that is what the host should send anyway. A stamped lookalike of a singleton is a
 * second instance of a class that has exactly one, and identity (`===`, a `when` against `Empty`, a
 * serialization keyed on the singleton) is precisely where the difference shows. Field values the host
 * defined are dropped: there is no state of its own to receive them.
 */
private fun buildSingletonFactory(
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
  factoriesClass: IrClass,
  clazz: IrClass,
  targetFqn: String,
) {
  buildObjectMethod(
    finder,
    pluginContext,
    factoriesClass,
    createFactoryJsName(targetFqn),
    pluginContext.irBuiltIns.anyNType,
    listOf(pluginContext.irBuiltIns.anyNType),
  ) { builder, _ ->
    builder.irGetObject(clazz.symbol)
  }
}

/**
 * Build [clazz]'s host->guest enum factory: a `create_<fqn>` taking the ordinal the host sent and
 * returning the guest's own singleton entry at that ordinal. The host sends only an ordinal — never
 * a field bag — because enum entries are pre-built singletons on both sides; reading
 * `<EnumClass>.entries[ordinal]` triggers the guest's own entry initialization, so the returned
 * entry already holds every `val` and entry-specific override the guest's revision defines. An
 * ordinal the guest's entry list does not hold is reported loudly, naming the class and the ordinal.
 */
private fun buildEnumFactory(
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
  factoriesClass: IrClass,
  clazz: IrClass,
  targetFqn: String,
) {
  val bridgeEnumOrdinalArg = finder.findFunctions(
    CallableId(FqName("app.cash.zipline"), Name.identifier("bridgeEnumOrdinalArg")),
  ).firstOrNull() ?: error("bridgeEnumOrdinalArg not found on the compile classpath ($targetFqn)")

  // `entries` is a synthetic static property (no dispatch receiver) on the enum class, returning
  // EnumEntries<E>. A static `values()` function is the pre-1.9 fallback.
  val entriesGetter = clazz.declarations.filterIsInstance<IrProperty>()
    .firstOrNull {
      it.name.asString() == "entries" && it.getter?.dispatchReceiverParameter == null
    }?.getter
  val valuesFunction = clazz.declarations.filterIsInstance<IrSimpleFunction>()
    .firstOrNull {
      it.name.asString() == "values" && it.dispatchReceiverParameter == null
    }
  if (entriesGetter == null && valuesFunction == null) return

  // The entry list this factory resolves from, and its element type. `entries` is a List, so the
  // List overload of getOrNull answers; `values()` is an Array, so the Array overload answers.
  val isArray = entriesGetter == null
  val entriesType = entriesGetter?.returnType ?: valuesFunction!!.returnType
  val elementType = typeArgument(entriesType, 0) ?: pluginContext.irBuiltIns.anyNType
  val getOrNull = resolveExtensionFun(finder, "kotlin.collections", "getOrNull") {
    it.parameters.size == 2 &&
      it.parameters[0].type.classOrNull?.owner?.fqNameWhenAvailable?.asString() ==
      if (isArray) "kotlin.Array" else "kotlin.collections.List"
  }

  val errorSymbol = finder.findFunctions(
    CallableId(FqName("kotlin"), Name.identifier("error")),
  ).firstOrNull { fn -> fn.owner.parameters.count { it.kind == IrParameterKind.Regular } == 1 }
    ?: error("kotlin.error not found on the compile classpath ($targetFqn)")

  buildObjectMethod(
    finder,
    pluginContext,
    factoriesClass,
    createFactoryJsName(targetFqn),
    pluginContext.irBuiltIns.anyNType,
    listOf(pluginContext.irBuiltIns.anyNType),
  ) { builder, params ->
    val ordinal = builder.irCall(bridgeEnumOrdinalArg).apply {
      arguments[0] = builder.irGet(params[0])
      arguments[1] = builder.irString(targetFqn)
    }
    val entries = if (entriesGetter != null) {
      builder.irCall(entriesGetter)
    } else {
      builder.irCall(valuesFunction!!)
    }

    // Read twice rather than binding a temporary: this body builds a single expression, and
    // `getOrNull` is pure. The null branch throws, so the returned value is the non-null element.
    fun entry(): IrExpression = builder.irCall(getOrNull).apply {
      typeArguments[0] = elementType
      arguments[0] = entries
      arguments[1] = ordinal
    }

    val message = IrStringConcatenationImpl(
      UNDEFINED_OFFSET,
      UNDEFINED_OFFSET,
      pluginContext.irBuiltIns.stringType,
    )
    message.arguments.add(builder.irString("host2js: ordinal "))
    message.arguments.add(ordinal)
    message.arguments.add(builder.irString(" is out of range for $targetFqn"))

    builder.irIfThenElse(
      type = elementType.makeNullable(),
      condition = builder.irEqualsNull(entry()),
      thenPart = builder.irCall(errorSymbol).apply {
        arguments[errorSymbol.owner.parameters.last()] = message
      },
      elsePart = entry(),
    )
  }
}

/**
 * Build [clazz]'s host->guest create-factory: a `create_<fqn>` on `__BridgeRuntimeFactories` taking
 * the host's plain fields object and returning a real instance.
 *
 * Shape, per constructor parameter: ask whether the host sent it (`bridgeHas`), read it
 * (`bridgeGet`, cast to the factory's parameter type), and for a parameter the host omitted pass an
 * unreadable-in-place placeholder plus set its flag bit — [BridgeCreateBodyGenerator] then evaluates
 * the class's own default expression. One `bridgeCreate` call, with one mask word per 32 parameters:
 * the superseded attempt branched into 2^k constructor calls instead, which is both the cap and the
 * blow-up this change removes.
 *
 * Body fields are still defined onto the result by the host afterwards. That is deliberate and is
 * worth knowing: what the factory fixes is that the constructor runs; a host-owned body `var` still
 * arrives by assignment, so a guest-only body default survives only because the constructor ran.
 */
private fun buildCreateFactory(
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
  factoriesClass: IrClass,
  clazz: IrClass,
  targetFqn: String,
) {
  val bridgeHasSymbol = finder.findFunctions(
    CallableId(FqName("app.cash.zipline"), Name.identifier("bridgeHas")),
  ).firstOrNull() ?: error("bridgeHas not found on the compile classpath ($targetFqn)")
  val bridgeGetSymbol = finder.findFunctions(
    CallableId(FqName("app.cash.zipline"), Name.identifier("bridgeGet")),
  ).firstOrNull() ?: error("bridgeGet not found on the compile classpath ($targetFqn)")
  val bridgeCharSymbol = finder.findFunctions(
    CallableId(FqName("app.cash.zipline"), Name.identifier("bridgeChar")),
  ).firstOrNull() ?: error("bridgeChar not found on the compile classpath ($targetFqn)")
  val errorSymbol = finder.findFunctions(
    CallableId(FqName("kotlin"), Name.identifier("error")),
  ).firstOrNull { fn -> fn.owner.parameters.count { it.kind == IrParameterKind.Regular } == 1 }
    ?: error("kotlin.error not found on the compile classpath ($targetFqn)")

  // The holder is found by what it declares, not by a companion flag: the companion the plugin
  // generates for a class that had none carries no `isCompanion` marker in IR, and getting this wrong
  // reads as "has no companion" for exactly the classes that need the factory.
  val companion = clazz.declarations.filterIsInstance<IrClass>().firstOrNull { nested ->
    nested.declarations.filterIsInstance<IrSimpleFunction>().any { it.name == BRIDGE_CREATE_NAME }
  } ?: return
  // No factory to call means the FIR gate declined this class — an interface, an object, a class with
  // only secondary constructors. The host looks the create-factory up at run time and falls back to
  // the prototype path when it is absent, so declining here is a decision the guest makes alone.
  val factory = companion.declarations.filterIsInstance<IrSimpleFunction>().first { it.name == BRIDGE_CREATE_NAME }

  val intOr = contextOf(pluginContext).intClass.owner.declarations
    .filterIsInstance<IrSimpleFunction>().firstOrNull { it.name.asString() == "or" }
    ?: error("Int.or not found ($targetFqn)")

  val constructor = clazz.primaryConstructor ?: return
  val ctorParams = constructor.parameters.filter { it.kind == IrParameterKind.Regular }
  val maskWords = maskWordCount(ctorParams.size)
  // The factory's regular parameters, in the contract order: mask words first, then every
  // constructor parameter. Positional, because that order is the whole contract between the halves.
  val factoryParams = factory.parameters.filter { it.kind == IrParameterKind.Regular }
  check(factoryParams.size == maskWords + ctorParams.size) {
    "bridgeCreate of $targetFqn declares ${factoryParams.size} parameters, expected $maskWords mask " +
      "words + ${ctorParams.size} constructor parameters"
  }
  val allFields = extractFields(clazz)
  val ctorFields = allFields.filter { it.isConstructorParam }
  // Mutable body properties: the converters send them (extractFields returns them), they are not
  // constructor parameters, and the guest's constructor cannot know the host's value for them.
  // Read-only body properties are absent from this list — `extractFields` skips `val` body fields on
  // this path — because a computed property belongs to whoever computes it.
  val bodyFields = allFields.filter { f -> !f.isConstructorParam }
    .mapNotNull { f -> clazz.properties.firstOrNull { it.name.asString() == f.name && it.isVar }?.let { f to it } }
  check(ctorFields.size == ctorParams.size) {
    "create-factory for $targetFqn: ${ctorFields.size} bridged fields but ${ctorParams.size} " +
      "constructor parameters"
  }

  buildObjectMethod(
    finder,
    pluginContext,
    factoriesClass,
    createFactoryJsName(targetFqn),
    pluginContext.irBuiltIns.anyNType,
    listOf(pluginContext.irBuiltIns.anyNType),
  ) { builder, params ->
    val fieldsObj = params[0]
    val orOperand = intOr.parameters.last()

    fun present(jsName: String): IrExpression = builder.irCall(bridgeHasSymbol).apply {
      arguments[0] = builder.irGet(fieldsObj)
      arguments[1] = builder.irString(jsName)
    }

    fun readRaw(jsName: String): IrExpression = builder.irCall(bridgeGetSymbol).apply {
      arguments[0] = builder.irGet(fieldsObj)
      arguments[1] = builder.irString(jsName)
    }

    /**
     * The wrapper chain of a (possibly nested) `@JvmInline value class` type, outermost first; empty
     * for every other type.
     */
    fun wrapperChain(type: IrType): List<IrClass> {
      val chain = mutableListOf<IrClass>()
      var current = type.classOrNull?.owner
      while (current != null && isInlineClass(current)) {
        chain += current
        current = current.primaryConstructor
          ?.parameters?.firstOrNull { it.kind == IrParameterKind.Regular }
          ?.type?.classOrNull?.owner
      }
      return chain
    }

    /** The type the host actually sends for a wrapper field: its innermost underlying type. */
    fun crossingType(type: IrType): IrType {
      var current = type
      while (true) {
        val owner = current.classOrNull?.owner ?: return current
        if (!isInlineClass(owner)) return current
        val parameter = owner.primaryConstructor?.parameters
          ?.firstOrNull { it.kind == IrParameterKind.Regular } ?: return current
        current = parameter.type
      }
    }

    /** Rebuild the wrappers around the value the host sent: `Outer(Inner(raw))`. */
    fun wrapChain(type: IrType, raw: IrExpression): IrExpression {
      var value = raw
      for (wrapper in wrapperChain(type).asReversed()) {
        val constructor = wrapper.primaryConstructor ?: return value
        val call = builder.irCallConstructor(constructor.symbol, emptyList())
        call.arguments[constructor.parameters.first { it.kind == IrParameterKind.Regular }] = value
        value = call
      }
      return value
    }

    /**
     * The value to hand `bridgeCreate` for the field [jsName], of the parameter's declared type.
     *
     * A `@JvmInline value class` crosses host->guest as its underlying value: the Kotlin/Native host
     * reads the wrapper's single backing field and converts that, and the C host reaches the same
     * conclusion through [FieldInfo.effectiveKtType]. But `bridgeCreate` takes the WRAPPER, and the JS
     * backend unboxes at the call by reading the wrapper's backing property — so the obvious
     * `bridgeGet(...) as Wrapped` compiles to `bridgeGet(...).raw_1`, which on a number is
     * `undefined`. That is not a compile error and not a crash: the field silently holds `undefined`,
     * and the payload comes back wrong on the return leg, which is where it surfaces. So the factory
     * builds the wrapper chain itself.
     */
    // The leaf value of a wire read: the number/boolean/string a payload carries, in the type the
    // property declares. `Char` is the one that cannot be cast into existence — see `bridgeChar` —
    // and it is asked for here rather than at each call site so that a value class *wrapping* a
    // `Char` builds its wrapper over a real `Char`. (A KDoc would not be allowed on a local fun.)
    fun leafValue(type: IrType, jsName: String): IrExpression = when (type.classFqName?.asString()) {
      "kotlin.Char" -> {
        val built = builder.irCall(bridgeCharSymbol).apply {
          arguments[bridgeCharSymbol.owner.parameters.last()] = readRaw(jsName)
        }
        if (!type.isMarkedNullable()) {
          built
        } else {
          builder.irIfThenElse(
            type = type,
            condition = builder.irEquals(readRaw(jsName), builder.irNull(pluginContext.irBuiltIns.anyNType)),
            thenPart = builder.irNull(type),
            elsePart = built,
          )
        }
      }

      else -> builder.irImplicitCast(readRaw(jsName), type)
    }

    fun readAs(type: IrType, jsName: String): IrExpression {
      if (wrapperChain(type).isEmpty()) return leafValue(type, jsName)
      val crossing = crossingType(type).let {
        if (type.isMarkedNullable() && !it.isMarkedNullable()) it.makeNullable() else it
      }
      if (!type.isMarkedNullable()) return wrapChain(type, leafValue(crossing, jsName))
      // Read twice rather than binding a temporary: this body builds expressions, and a variable
      // declaration is a statement. Two property reads of one field have no observable order.
      return builder.irIfThenElse(
        type = type,
        condition = builder.irEquals(readRaw(jsName), builder.irNull(pluginContext.irBuiltIns.anyNType)),
        thenPart = builder.irNull(type),
        elsePart = wrapChain(type, leafValue(crossing, jsName)),
      )
    }

    /**
     * What to hand the factory for a parameter the guest did not send. It is never read — the set
     * flag sends the call to the default expression — but it must satisfy the parameter's type, so a
     * reference takes null and a primitive its zero.
     */
    fun absentValue(type: IrType, name: String): IrExpression {
      if (wrapperChain(type).isNotEmpty()) {
        // A nullable wrapper's absent value is null like any other reference's; a non-null one gets a
        // real wrapper built from its innermost zero, which the set flag guarantees nobody reads.
        if (type.isMarkedNullable()) return builder.irNull(type)
        return wrapChain(type, absentValue(crossingType(type), name))
      }
      return when (type.classFqName?.asString()) {
        "kotlin.Int" -> builder.irInt(0)

        "kotlin.Long" -> IrConstImpl.long(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0L)

        "kotlin.Double" -> IrConstImpl.double(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0.0)

        "kotlin.Float" -> IrConstImpl.float(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0.0f)

        "kotlin.Boolean" -> builder.irBoolean(false)

        // The narrow scalars. All three are numbers on the wire and none of them is an `Int` to the
        // type checker, so each gets its own zero of the right type; the flag word makes sure nobody
        // reads it, but the expression is still evaluated, which rules out anything that could throw.
        "kotlin.Byte" -> IrConstImpl.byte(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0)

        "kotlin.Short" -> IrConstImpl.short(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0)

        "kotlin.Char" -> IrConstImpl.char(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, '\u0000')

        // The unsigned ones have no IR constant at all: they are inline classes, so the "constant"
        // is a constructor call over the signed underlying value. Zero is the only sensible
        // placeholder, and every wrapper takes it -- `UInt(0)`, `UByte(0)` -- which beats the loud
        // refusal below, because the flag word means the value is never read.
        "kotlin.UInt", "kotlin.ULong", "kotlin.UByte", "kotlin.UShort" -> {
          val wrapper = type.getClass() ?: error("unsigned placeholder for $type ($targetFqn)")
          val constructor = wrapper.primaryConstructor
            ?: error("no primary constructor on ${type.classFqName} ($targetFqn)")
          // The signed type the wrapper wraps, taken from the wrapper's own parameter rather than
          // named here, so the four types above and the types their zeros are built with cannot
          // drift apart.
          val underlying = constructor.parameters.single().type
          val zero = when (type.classFqName?.asString()) {
            "kotlin.ULong" -> IrConstImpl.long(UNDEFINED_OFFSET, UNDEFINED_OFFSET, underlying, 0L)
            "kotlin.UByte" -> IrConstImpl.byte(UNDEFINED_OFFSET, UNDEFINED_OFFSET, underlying, 0)
            "kotlin.UShort" -> IrConstImpl.short(UNDEFINED_OFFSET, UNDEFINED_OFFSET, underlying, 0)
            else -> IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, underlying, 0)
          }
          builder.irCall(constructor.symbol).apply {
            arguments[0] = zero
          }
        }

        "kotlin.String" -> builder.irString("")

        else -> if (type.isMarkedNullable()) {
          builder.irNull(type)
        } else {
          error(
            "cannot build a host->guest factory for $targetFqn: parameter '$name' has type " +
              "${type.classFqName}; the factory has no absent value for it. Report this: the " +
              "alternatives are a placeholder that the guest might read as a real value, or a " +
              "silent skip.",
          )
        }
      }
    }

    val maskTerms = Array(maskWords) { mutableListOf<IrExpression>() }
    val call = builder.irCall(factory.symbol).apply {
      dispatchReceiver = builder.irGetObject(companion.symbol)
    }

    for ((index, field) in ctorFields.withIndex()) {
      val argument = factoryParams[maskWords + index]
      val supplied = readAs(argument.type, field.jsPropertyName)
      val value = if (field.hasDefault) {
        maskTerms[maskWordIndex(index)] += builder.irIfThenElse(
          type = pluginContext.irBuiltIns.intType,
          condition = present(field.jsPropertyName),
          thenPart = builder.irInt(0),
          elsePart = builder.irInt(maskBit(index)),
        )
        builder.irIfThenElse(
          type = argument.type,
          condition = present(field.jsPropertyName),
          thenPart = supplied,
          elsePart = absentValue(argument.type, field.name),
        )
      } else {
        // A required parameter that did not arrive is a host-side bug, not skew: say so with the
        // field's name rather than letting the guest see undefined.
        builder.irIfThenElse(
          type = argument.type,
          condition = present(field.jsPropertyName),
          thenPart = supplied,
          elsePart = builder.irCall(errorSymbol).apply {
            arguments[errorSymbol.owner.parameters.last()] = builder.irString(
              "host2js: " + targetFqn + "." + field.jsPropertyName + " was not sent by the host",
            )
          },
        )
      }
      call.arguments[argument] = value
    }

    for (word in 0 until maskWords) {
      val terms = maskTerms[word]
      call.arguments[factoryParams[word]] = when {
        terms.isEmpty() -> builder.irInt(0)

        else -> terms.reduce { left, right ->
          builder.irCall(intOr.symbol).apply {
            dispatchReceiver = left
            arguments[orOperand] = right
          }
        }
      }
    }
    if (bodyFields.isEmpty()) return@buildObjectMethod call

    // Constructor first, then the host's mutable body fields — never the other way round, and never
    // for a field that did not arrive. The constructor has to run before the assignments because it
    // owns the initializer the absent fields keep; and a field is assigned only when the carrier
    // really carries it, so a peer that predates the property leaves the guest's own value standing
    // rather than writing `undefined` into it.
    with(builder) {
      val obj = irTemporary(irImplicitCast(call, clazz.defaultType), "constructed")
      bodyFields.forEach { (field, property) ->
        val setter = property.setter ?: return@forEach
        +irIfThen(
          type = pluginContext.irBuiltIns.unitType,
          condition = present(field.jsPropertyName),
          thenPart = irCall(setter.symbol).apply {
            dispatchReceiver = irGet(obj)
            arguments[setter.parameters.last()] = readAs(field.type, field.jsPropertyName)
          },
        )
      }
      irGet(obj)
    }
  }
}

private fun contextOf(pluginContext: IrPluginContext) = pluginContext.irBuiltIns

/** Build an internal member function on [owner] whose body returns the [body] expression. */
private fun buildObjectMethod(
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
  owner: IrClass,
  name: String,
  returnType: IrType,
  paramTypes: List<IrType>,
  body: (IrBlockBodyBuilder, List<IrValueParameter>) -> IrExpression,
): IrSimpleFunction {
  val fn = pluginContext.irFactory.buildFun {
    this.name = Name.identifier(name)
    this.returnType = returnType
    visibility = DescriptorVisibilities.INTERNAL
    origin = IrDeclarationOrigin.DEFINED
  }
  fn.parent = owner
  val params = paramTypes.mapIndexed { index, type ->
    fn.addValueParameter {
      this.name = Name.identifier("p$index")
      this.type = type
    }
  }
  fn.addDispatchReceiver(owner, pluginContext)
  // @JsExport keeps the methods from being DCE'd (the host invokes them via the retained
  // references, the guest never calls them); @JsName pins the JS name, which the host looks up
  // (internal members would otherwise get mangled JS names).
  addJsExportAnnotation(fn, finder, pluginContext)
  addJsNameToFunction(fn, name, finder, pluginContext)
  val builder = pluginContext.irBuiltIns.createIrBuilder(fn.symbol)
  fn.body = builder.irBlockBody {
    // The block builder, not `builder`, is handed to the body: a temporary or an `if` is a statement,
    // and only this receiver appends to the body. Bodies that need neither are unaffected.
    +irReturn(body(this, params))
  }
  owner.declarations += fn
  return fn
}

/** @JsExport on the generated factory method so the JS DCE keeps it. */
private fun addJsExportAnnotation(
  fn: IrSimpleFunction,
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
) {
  val jsExportClass = finder.findClass(ClassId(FqName("kotlin.js"), Name.identifier("JsExport")))?.owner ?: return
  val ctor = jsExportClass.declarations.filterIsInstance<IrConstructor>()
    .firstOrNull { it.isPrimary } ?: return
  fn.annotations += IrConstructorCallImpl(
    UNDEFINED_OFFSET,
    UNDEFINED_OFFSET,
    ctor.returnType,
    ctor.symbol,
    0,
    0,
  )
}

/** @JsName("<name>") so the host can look the method up by its plain JS name. */
private fun addJsNameToFunction(
  fn: IrSimpleFunction,
  name: String,
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
) {
  val jsNameClass = finder.findClass(JS_NAME_CLASS_ID)?.owner ?: return
  val ctor = jsNameClass.declarations.filterIsInstance<IrConstructor>()
    .firstOrNull { it.isPrimary } ?: return
  fn.annotations += IrConstructorCallImpl(
    UNDEFINED_OFFSET,
    UNDEFINED_OFFSET,
    ctor.returnType,
    ctor.symbol,
    0,
    1,
  ).apply {
    arguments[0] = IrConstImpl.string(
      UNDEFINED_OFFSET,
      UNDEFINED_OFFSET,
      pluginContext.irBuiltIns.stringType,
      name,
    )
  }
}

/** Resolve a top-level extension function in [packageName] filtered by [predicate]. */
@Suppress("DEPRECATION_ERROR")
private fun resolveExtensionFun(
  finder: DeclarationFinder,
  packageName: String,
  name: String,
  predicate: (IrSimpleFunction) -> Boolean,
): IrSimpleFunction {
  return finder.findFunctions(CallableId(FqName(packageName), Name.identifier(name)))
    .map { it.owner }
    .firstOrNull { it.parameters.firstOrNull()?.kind == IrParameterKind.ExtensionReceiver && predicate(it) }
    ?: error("$packageName.$name extension not found")
}

/** The KClass.js property getter (kotlin.js.KClass.js → JsClass<T>). */
private fun findKClassJsGetter(finder: DeclarationFinder): IrSimpleFunctionSymbol {
  val kclassJsPropId = CallableId(FqName("kotlin.js"), null, Name.identifier("js"))
  return finder.findProperties(kclassJsPropId)
    .mapNotNull { it.owner.getter }
    .firstOrNull { getter ->
      val firstParam = getter.parameters.firstOrNull()
      firstParam?.kind == IrParameterKind.ExtensionReceiver &&
        firstParam.type.getClass()?.classId?.asSingleFqName()?.asString() == "kotlin.reflect.KClass"
    }?.symbol ?: error("KClass.js property getter not found")
}

/**
 * Resolve the module's `__bridgeRegister` entry point: zipline's tolerant `registerBridge` when
 * it is on the classpath, otherwise the synthetic raw external (created here with @JsName if
 * absent). The returned target says which one it is so call sites can guard the raw one.
 */
private fun findOrCreateBridgeRegister(
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
  fileForModule: IrFile,
  moduleName: String,
): BridgeRegisterTarget {
  // Prefer zipline's helper: it no-ops when no host installed `__bridgeRegister` (a bare Kotlin/JS
  // runtime, e.g. a unit test), where the raw call below would throw at class-initialization time
  // for every bridged class the runtime touches. Modules that do not have the annotations artifact
  // on their classpath keep the raw external.
  finder.findFunctions(CallableId(FqName("app.cash.zipline"), Name.identifier("registerBridge")))
    .firstOrNull()
    ?.let { return BridgeRegisterTarget(it, isRaw = false) }

  val keyedName = bridgeDeclarationName("__bridgeRegister", moduleName)
  val existing = fileForModule.declarations
    .filterIsInstance<IrSimpleFunction>()
    .firstOrNull { it.name.asString() == "__bridgeRegister" || it.name.asString() == keyedName }
  if (existing != null) return BridgeRegisterTarget(existing.symbol, isRaw = true)

  val bridgeRegisterFn = pluginContext.irFactory.buildFun {
    name = Name.identifier(keyedName)
    returnType = pluginContext.irBuiltIns.unitType
    visibility = DescriptorVisibilities.INTERNAL
    origin = IrDeclarationOrigin.DEFINED
    isExternal = true
  }
  bridgeRegisterFn.parent = fileForModule
  bridgeRegisterFn.addValueParameter {
    name = Name.identifier("fqn")
    type = pluginContext.irBuiltIns.stringType
  }
  bridgeRegisterFn.addValueParameter {
    name = Name.identifier("ctor")
    type = pluginContext.irBuiltIns.anyNType
  }
  val jsNameClass = finder.findClass(JS_NAME_CLASS_ID)!!
  val jsNameCtor = jsNameClass.owner.declarations
    .filterIsInstance<IrConstructor>()
    .firstOrNull { it.isPrimary }!!
  bridgeRegisterFn.annotations += IrConstructorCallImpl(
    UNDEFINED_OFFSET,
    UNDEFINED_OFFSET,
    jsNameCtor.returnType,
    jsNameCtor.symbol,
    0,
    1,
  ).apply {
    arguments[0] = IrConstImpl.string(
      UNDEFINED_OFFSET,
      UNDEFINED_OFFSET,
      pluginContext.irBuiltIns.stringType,
      "__bridgeRegister",
    )
  }
  fileForModule.declarations += bridgeRegisterFn
  return BridgeRegisterTarget(bridgeRegisterFn.symbol, isRaw = true)
}

/**
 * The synthetic `__bridgeRegisterRuntime` external fun (with @JsName), taking the factories
 * object. There is no tolerant counterpart in the annotations artifact, so this target is
 * always raw: its call sites must guard it.
 */
private fun findOrCreateBridgeRegisterRuntime(
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
  fileForModule: IrFile,
  moduleName: String,
): BridgeRegisterTarget {
  val keyedName = bridgeDeclarationName("__bridgeRegisterRuntime", moduleName)
  val existing = fileForModule.declarations
    .filterIsInstance<IrSimpleFunction>()
    .firstOrNull { it.name.asString() == "__bridgeRegisterRuntime" || it.name.asString() == keyedName }
  if (existing != null) return BridgeRegisterTarget(existing.symbol, isRaw = true)

  val bridgeRegisterRuntimeFn = pluginContext.irFactory.buildFun {
    name = Name.identifier(keyedName)
    returnType = pluginContext.irBuiltIns.unitType
    visibility = DescriptorVisibilities.INTERNAL
    origin = IrDeclarationOrigin.DEFINED
    isExternal = true
  }
  bridgeRegisterRuntimeFn.parent = fileForModule
  bridgeRegisterRuntimeFn.addValueParameter {
    name = Name.identifier("factories")
    type = pluginContext.irBuiltIns.anyNType
  }
  val jsNameClass = finder.findClass(JS_NAME_CLASS_ID)!!
  val jsNameCtor = jsNameClass.owner.declarations
    .filterIsInstance<IrConstructor>()
    .firstOrNull { it.isPrimary }!!
  bridgeRegisterRuntimeFn.annotations += IrConstructorCallImpl(
    UNDEFINED_OFFSET,
    UNDEFINED_OFFSET,
    jsNameCtor.returnType,
    jsNameCtor.symbol,
    0,
    1,
  ).apply {
    arguments[0] = IrConstImpl.string(
      UNDEFINED_OFFSET,
      UNDEFINED_OFFSET,
      pluginContext.irBuiltIns.stringType,
      "__bridgeRegisterRuntime",
    )
  }
  fileForModule.declarations += bridgeRegisterRuntimeFn
  return BridgeRegisterTarget(bridgeRegisterRuntimeFn.symbol, isRaw = true)
}

/** Attach a dispatch receiver to an injected member (the parameters list is the single source of truth in 2.3). */
private fun IrSimpleFunction.addDispatchReceiver(clazz: IrClass, pluginContext: IrPluginContext) {
  val builder = IrValueParameterBuilder().apply {
    name = Name.identifier("this")
    type = clazz.defaultType
    origin = IrDeclarationOrigin.DEFINED
    kind = IrParameterKind.DispatchReceiver
  }
  val receiver = pluginContext.irFactory.buildValueParameter(builder, clazz)
  parameters = listOf(receiver) + parameters
}
