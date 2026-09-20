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

  for (clazz in dispatchClasses) {
    val ownFqn = clazz.fqNameWhenAvailable?.asString() ?: continue
    val targetFqn = resolveTargetFqn(clazz) ?: ownFqn

    if (clazz.isCompanion || clazz.kind == ClassKind.OBJECT) {
      injectBridgeIntoConstructor(
        clazz, targetFqn, clazz,
        kclassJsGetterSymbol, bridgeRegisterSymbol, pluginContext,
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
      kclassJsGetterSymbol, bridgeRegisterSymbol, pluginContext,
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

  val body = ctor.body
  if (body is IrBlockBody) {
    body.statements.add(1, bridgeCall)
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

