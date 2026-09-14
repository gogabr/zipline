package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addConstructor
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildClass
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irDelegatingConstructorCall
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrBlockBody
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.createThisReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.IrValueParameterBuilder
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.builders.irBlock
import org.jetbrains.kotlin.ir.builders.irBoolean
import org.jetbrains.kotlin.ir.builders.irExprBody
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGetObjectValue
import org.jetbrains.kotlin.ir.builders.irNull
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetFieldImpl
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameter
import org.jetbrains.kotlin.name.ClassId

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

  // Prefer zipline's tolerant helper: it no-ops when no host installed `__bridgeRegister` (a bare
  // Kotlin/JS runtime — a unit test, or any plain JS consumer of a bridged module — has nothing to
  // register with), where the raw external below would throw at class-initialization time for every
  // bridged class the runtime touches. See app.cash.zipline.registerBridge.
  val tolerantRegisterSymbol = finder.findFunctions(
    CallableId(FqName("app.cash.zipline"), Name.identifier("registerBridge")),
  ).firstOrNull()

  // Publish the guest's value ops (collections, Longs, enums) as soon as a bridged class registers:
  // the host reads every one of those values through them, and it needs them before the application
  // produces any. See app.cash.zipline.BridgeValueOps.
  val valueOpsSymbol = finder.findFunctions(
    CallableId(FqName("app.cash.zipline"), Name.identifier("publishValueOps")),
  ).firstOrNull()

  // Generate @JsName("__bridgeRegister") external fun __bridgeRegister(fqn: String, ctor: Any?)
  // once in the module. Companion constructors call this directly — no bridgeSelfRegister wrapper.
  var bridgeRegisterFn = fileForModule.declarations
    .filterIsInstance<IrSimpleFunction>()
    .firstOrNull { it.name.asString() == "__bridgeRegister" }
  if (bridgeRegisterFn == null) {
    bridgeRegisterFn = pluginContext.irFactory.buildFun {
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
    // @JsName("__bridgeRegister")
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
  }
  val bridgeRegisterSymbol = tolerantRegisterSymbol ?: bridgeRegisterFn.symbol

  for ((index, clazz) in dispatchClasses.withIndex()) {
    val ownFqn = clazz.fqNameWhenAvailable?.asString() ?: continue
    val targetFqn = resolveTargetFqn(clazz) ?: ownFqn
    // Once per module is enough; the ops live on globalThis for the runtime's whole lifetime.
    val opsCall = if (index == 0) valueOpsSymbol else null

    if (clazz.isCompanion || clazz.kind == ClassKind.OBJECT) {
      injectBridgeIntoConstructor(
        clazz, targetFqn, clazz,
        kclassJsGetterSymbol, bridgeRegisterSymbol, pluginContext, opsCall,
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
      kclassJsGetterSymbol, bridgeRegisterSymbol, pluginContext, opsCall,
    )
  }
}

/**
 * Inserts a direct __bridgeRegister call into the companion's primary constructor,
 * equivalent to: __bridgeRegister("targetFqn", Foo::class.js)
 *
 * IrClassReferenceImpl is used directly as the extension receiver for kClass.js —
 * no deprecated IrGetValue or parameter references needed.
 */
internal fun injectBridgeIntoConstructor(
  companion: IrClass,
  targetFqn: String,
  clazz: IrClass,
  kclassJsGetterSymbol: IrSimpleFunctionSymbol,
  bridgeRegisterSymbol: IrSimpleFunctionSymbol,
  pluginContext: IrPluginContext,
  extraCallSymbol: IrSimpleFunctionSymbol? = null,
) {
  val ctor = companion.declarations.filterIsInstance<IrConstructor>()
    .firstOrNull { it.isPrimary } ?: return
  val builder = pluginContext.irBuiltIns.createIrBuilder(ctor.symbol)

  // Foo::class
  val classRef = IrClassReferenceImpl(
    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
    pluginContext.irBuiltIns.kClassClass.starProjectedType,
    clazz.symbol, clazz.defaultType,
  )

  // Foo::class.js — calls the KClass.js extension property getter at IR level
  val jsCtorCall = builder.irCall(kclassJsGetterSymbol).apply {
    insertExtensionReceiver(classRef)
  }

  // __bridgeRegister("targetFqn", Foo::class.js)
  val bridgeCall = builder.irCall(bridgeRegisterSymbol).apply {
    arguments[0] = builder.irString(targetFqn)
    arguments[1] = jsCtorCall
  }

  val extraCall = extraCallSymbol?.let { builder.irCall(it) }

  val body = ctor.body
  if (body is IrBlockBody) {
    body.statements.add(1, bridgeCall)
    if (extraCall != null) body.statements.add(2, extraCall)
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
      if (extraCall != null) +extraCall
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



/** Name of the `@JsExport` hook the host calls after defining a guest module. */
private const val BRIDGE_WARM_UP_FUNCTION_NAME = "__bridgeWarmUpHost2Js"

/**
 * Emits `@JsExport fun __bridgeWarmUpHost2Js(): Boolean` calling `publishValueOps()`, so the guest's
 * value accessors exist before the host decodes anything. A bridged class's companion initializer
 * also publishes them, but only once that class is first touched — later than the first conversion,
 * since a generated converter can run before any class is touched at all.
 */
internal fun injectModuleLoadValueOpsPublication(
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
  moduleFragment: IrModuleFragment,
) {
  val fileForModule = moduleFragment.files.firstOrNull() ?: return
  val valueOpsSymbol = finder.findFunctions(
    CallableId(FqName("app.cash.zipline"), Name.identifier("publishValueOps")),
  ).firstOrNull() ?: return

  val warmUpFn = pluginContext.irFactory.buildFun {
    name = Name.identifier(BRIDGE_WARM_UP_FUNCTION_NAME)
    returnType = pluginContext.irBuiltIns.booleanType
    visibility = DescriptorVisibilities.PUBLIC
    origin = IrDeclarationOrigin.DEFINED
  }
  warmUpFn.parent = fileForModule
  val jsExportCtor = finder.findClass(ClassId(FqName("kotlin.js"), Name.identifier("JsExport")))
    ?.owner?.declarations?.filterIsInstance<IrConstructor>()?.firstOrNull { it.isPrimary }
  if (jsExportCtor != null) {
    warmUpFn.annotations += IrConstructorCallImpl(
      UNDEFINED_OFFSET, UNDEFINED_OFFSET, jsExportCtor.returnType, jsExportCtor.symbol, 0, 0,
    )
  }
  val builder = pluginContext.irBuiltIns.createIrBuilder(warmUpFn.symbol)
  warmUpFn.body = builder.irBlockBody {
    +irCall(valueOpsSymbol)
    +irReturn(irBoolean(true))
  }
  fileForModule.declarations += warmUpFn
}
