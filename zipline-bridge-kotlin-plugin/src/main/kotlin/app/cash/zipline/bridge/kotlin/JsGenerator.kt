package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.IrValueParameterBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObjectValue
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameter
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

// -- JS IR transformations (bridge_dispatch injection) --

internal fun injectCompanionInitBlocks(
  finder: org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder,
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
  val bridgeRegisterSymbol = findOrCreateBridgeRegister(finder, pluginContext, fileForModule)
  // Null when the annotations artifact on the compile classpath predates registerFieldAlias; alias
  // emission is then skipped (see findFieldAliasRegister).
  val fieldAliasSymbol = findFieldAliasRegister(finder)

  for (clazz in dispatchClasses) {
    val ownFqn = clazz.fqNameWhenAvailable?.asString() ?: continue
    val targetFqn = resolveTargetFqn(clazz) ?: ownFqn

    if (clazz.isCompanion || clazz.kind == ClassKind.OBJECT) {
      injectBridgeIntoConstructor(
        clazz, targetFqn, clazz,
        kclassJsGetterSymbol, bridgeRegisterSymbol, fieldAliasSymbol, pluginContext,
      )
      continue
    }

    // Regular class: inject bridge registration into companion constructor.
    var companion = clazz.declarations.filterIsInstance<IrClass>().firstOrNull { it.isCompanion }
    if (companion == null) {
      companion = createCompanion(clazz, pluginContext)
    }

    injectBridgeIntoConstructor(
      companion, targetFqn, clazz,
      kclassJsGetterSymbol, bridgeRegisterSymbol, fieldAliasSymbol, pluginContext,
    )
  }
}

/**
 * Inserts a direct __bridgeRegister call into the companion's primary constructor,
 * equivalent to: __bridgeRegister("targetFqn", Foo::class.js)
 *
 * IrClassReferenceImpl is used directly as the extension receiver for kClass.js —
 * no deprecated IrGetValue or parameter references needed.
 *
 * When [fieldAliasSymbol] is non-null, one registerFieldAlias call per `@HostName`-renamed field
 * of [clazz] follows, installing the old JS name on the class prototype so an already-shipped host
 * still reads it.
 */
internal fun injectBridgeIntoConstructor(
  companion: IrClass,
  targetFqn: String,
  clazz: IrClass,
  kclassJsGetterSymbol: IrSimpleFunctionSymbol,
  bridgeRegisterSymbol: IrSimpleFunctionSymbol,
  fieldAliasSymbol: IrSimpleFunctionSymbol?,
  pluginContext: IrPluginContext,
) {
  val ctor = companion.declarations.filterIsInstance<IrConstructor>()
    .firstOrNull { it.isPrimary } ?: return
  val builder = pluginContext.irBuiltIns.createIrBuilder(ctor.symbol)

  // Foo::class.js — calls the KClass.js extension property getter at IR level. A fresh node per
  // call: IR nodes must not be shared between expressions.
  val jsCtor = {
    val classRef = IrClassReferenceImpl(
      UNDEFINED_OFFSET, UNDEFINED_OFFSET,
      pluginContext.irBuiltIns.kClassClass.starProjectedType,
      clazz.symbol, clazz.defaultType,
    )
    builder.irCall(kclassJsGetterSymbol).apply {
      insertExtensionReceiver(classRef)
    }
  }

  // __bridgeRegister("targetFqn", Foo::class.js)
  val bridgeCall = builder.irCall(bridgeRegisterSymbol).apply {
    arguments[0] = builder.irString(targetFqn)
    arguments[1] = jsCtor()
  }

  val aliasCalls = if (fieldAliasSymbol != null) {
    hostNameAliases(clazz).map { (alias, target) ->
      builder.irCall(fieldAliasSymbol).apply {
        arguments[0] = jsCtor()
        arguments[1] = builder.irString(alias)
        arguments[2] = builder.irString(target)
      }
    }
  } else {
    emptyList()
  }

  val body = ctor.body
  if (body is IrBlockBody) {
    body.statements.add(1, bridgeCall)
    body.statements.addAll(2, aliasCalls)
  } else {
    val superCall = (body as? IrExpressionBody)?.expression
      ?: builder.irDelegatingConstructorCall(
        pluginContext.irBuiltIns.anyClass.owner.declarations
          .filterIsInstance<IrConstructor>()
          .first { it.isPrimary }
      )
    ctor.body = builder.irBlockBody {
      +superCall
      +bridgeCall
      aliasCalls.forEach { +it }
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
    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
    stringType,
    name,
  )
  val annotation = IrConstructorCallImpl(
    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
    jsNameCtor.returnType,
    jsNameCtor.symbol,
    0, 1,
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
  val bridgeRegisterSymbol = findOrCreateBridgeRegister(finder, pluginContext, fileForModule)
  // Null when the annotations artifact on the compile classpath predates registerFieldAlias; alias
  // emission is then skipped (see findFieldAliasRegister).
  val fieldAliasSymbol = findFieldAliasRegister(finder)
  val bridgeRegisterRuntimeSymbol =
    findOrCreateBridgeRegisterRuntime(finder, pluginContext, fileForModule)
  val factoriesClass = buildRuntimeFactories(finder, pluginContext, fileForModule)
  val factoriesCtor = factoriesClass.declarations.filterIsInstance<IrConstructor>()
    .firstOrNull { it.isPrimary }
    ?: error("__BridgeRuntimeFactories primary constructor not found")

  // `@JsExport fun __bridgeWarmUpHost2Js(): Boolean` — the host calls this from loadJsModule
  // right after the module is defined (see BRIDGE_WARM_UP_JS host-side). Being exported keeps it
  // reachable from the host and immune to dead-code elimination.
  val warmUpFn = pluginContext.irFactory.buildFun {
    name = Name.identifier(BRIDGE_WARM_UP_FUNCTION_NAME)
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
        UNDEFINED_OFFSET, UNDEFINED_OFFSET,
        jsExportCtor.returnType, jsExportCtor.symbol, 0, 0,
      )
    }
  }

  val builder = pluginContext.irBuiltIns.createIrBuilder(warmUpFn.symbol)
  // Foo::class.js — a fresh node per call: IR nodes must not be shared between expressions.
  val jsCtor = { clazz: IrClass ->
    val classRef = IrClassReferenceImpl(
      UNDEFINED_OFFSET, UNDEFINED_OFFSET,
      pluginContext.irBuiltIns.kClassClass.starProjectedType,
      clazz.symbol, clazz.defaultType,
    )
    builder.irCall(kclassJsGetterSymbol).apply {
      insertExtensionReceiver(classRef)
    }
  }
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
      +irCall(bridgeRegisterSymbol).apply {
        arguments[0] = irString(targetFqn)
        arguments[1] = jsCtor(clazz)
      }
      // The prototype alias serves the host's JS->host reader, so only classes a host reads
      // through the @WithJS2HostBridge converter need it; a host2js-only payload class never
      // travels that way.
      if (fieldAliasSymbol != null && hasWithJS2HostBridgeAnnotation(clazz)) {
        for ((alias, aliasTarget) in hostNameAliases(clazz)) {
          +irCall(fieldAliasSymbol).apply {
            arguments[0] = jsCtor(clazz)
            arguments[1] = irString(alias)
            arguments[2] = irString(aliasTarget)
          }
        }
      }
    }
    +irCall(bridgeRegisterRuntimeSymbol).apply {
      arguments[0] = irCall(factoriesCtor)
    }
    +irReturn(irBoolean(true))
  }

  fileForModule.declarations += warmUpFn
}

/** Name of the exported hook the host calls to register every @WithHost2JSBridge class. */
internal const val BRIDGE_WARM_UP_FUNCTION_NAME: String = "__bridgeWarmUpHost2Js"

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
): IrClass {
  val factoriesClass = pluginContext.irFactory.buildClass {
    name = Name.identifier("__BridgeRuntimeFactories")
    kind = ClassKind.CLASS
    modality = org.jetbrains.kotlin.descriptors.Modality.FINAL
    visibility = DescriptorVisibilities.INTERNAL
    origin = IrDeclarationOrigin.DEFINED
  }
  factoriesClass.parent = fileForModule
  // A plain class (not an object): a synthetic object's singleton accessor proved unreliable
  // in the JS backend, so the registration constructs an instance via the constructor instead.
  factoriesClass.createThisReceiverParameter()
  factoriesClass.addConstructor {
    visibility = DescriptorVisibilities.PRIVATE
    isPrimary = true
  }

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
    finder, pluginContext, factoriesClass, "newLong", pluginContext.irBuiltIns.longType,
    listOf(pluginContext.irBuiltIns.intType, pluginContext.irBuiltIns.intType),
  ) { builder, params ->
    builder.irCall(longCtor).apply {
      arguments[0] = builder.irGet(params[0])
      arguments[1] = builder.irGet(params[1])
    }
  }

  buildObjectMethod(
    finder, pluginContext, factoriesClass, "newArrayList", anyListType,
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
    finder, pluginContext, factoriesClass, "newLinkedHashMap", anyMapType,
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

  fileForModule.declarations += factoriesClass
  return factoriesClass
}

/** Build an internal member function on [owner] whose body returns the [body] expression. */
private fun buildObjectMethod(
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
  owner: IrClass,
  name: String,
  returnType: IrType,
  paramTypes: List<IrType>,
  body: (IrBuilderWithScope, List<IrValueParameter>) -> IrExpression,
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
    +irReturn(body(builder, params))
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
    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
    ctor.returnType, ctor.symbol, 0, 0,
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
    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
    ctor.returnType, ctor.symbol, 0, 1,
  ).apply {
    arguments[0] = IrConstImpl.string(
      UNDEFINED_OFFSET, UNDEFINED_OFFSET,
      pluginContext.irBuiltIns.stringType, name,
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
    .mapNotNull { it.owner }
    .firstOrNull { it.parameters.firstOrNull()?.kind == IrParameterKind.ExtensionReceiver && predicate(it) }
    ?: error("$packageName.$name extension not found")
}

/**
 * The zipline `registerFieldAlias` helper (jsMain of the annotations artifact), or null when the
 * annotations artifact on the compile classpath predates it. Null skips alias emission entirely:
 * a synthesized external would have no JS implementation to call, and the reader fallback plus the
 * host writer alias still cover the pairs where the guest carries no alias.
 */
private fun findFieldAliasRegister(finder: DeclarationFinder): IrSimpleFunctionSymbol? =
  finder.findFunctions(
    CallableId(FqName("app.cash.zipline"), Name.identifier("registerFieldAlias")),
  ).firstOrNull()

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

/** Find the synthetic `__bridgeRegister` external fun, creating it (with @JsName) if absent. */
private fun findOrCreateBridgeRegister(
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
  fileForModule: IrFile,
): IrSimpleFunctionSymbol {
  // Prefer zipline's helper: it no-ops when no host installed `__bridgeRegister` (a bare Kotlin/JS
  // runtime, e.g. a unit test), where the raw call below would throw at class-initialization time
  // for every bridged class the runtime touches. Modules that do not have the annotations artifact
  // on their classpath keep the raw external.
  finder.findFunctions(CallableId(FqName("app.cash.zipline"), Name.identifier("registerBridge")))
    .firstOrNull()
    ?.let { return it }

  val existing = fileForModule.declarations
    .filterIsInstance<IrSimpleFunction>()
    .firstOrNull { it.name.asString() == "__bridgeRegister" }
  if (existing != null) return existing.symbol

  val bridgeRegisterFn = pluginContext.irFactory.buildFun {
    name = Name.identifier("__bridgeRegister")
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
    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
    jsNameCtor.returnType, jsNameCtor.symbol, 0, 1,
  ).apply {
    arguments[0] = IrConstImpl.string(
      UNDEFINED_OFFSET, UNDEFINED_OFFSET,
      pluginContext.irBuiltIns.stringType, "__bridgeRegister",
    )
  }
  fileForModule.declarations += bridgeRegisterFn
  return bridgeRegisterFn.symbol
}

/** The synthetic `__bridgeRegisterRuntime` external fun (with @JsName), taking the factories object. */
private fun findOrCreateBridgeRegisterRuntime(
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
  fileForModule: IrFile,
): IrSimpleFunctionSymbol {
  val existing = fileForModule.declarations
    .filterIsInstance<IrSimpleFunction>()
    .firstOrNull { it.name.asString() == "__bridgeRegisterRuntime" }
  if (existing != null) return existing.symbol

  val bridgeRegisterRuntimeFn = pluginContext.irFactory.buildFun {
    name = Name.identifier("__bridgeRegisterRuntime")
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
    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
    jsNameCtor.returnType, jsNameCtor.symbol, 0, 1,
  ).apply {
    arguments[0] = IrConstImpl.string(
      UNDEFINED_OFFSET, UNDEFINED_OFFSET,
      pluginContext.irBuiltIns.stringType, "__bridgeRegisterRuntime",
    )
  }
  fileForModule.declarations += bridgeRegisterRuntimeFn
  return bridgeRegisterRuntimeFn.symbol
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
