package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.IrValueParameterBuilder
import org.jetbrains.kotlin.ir.builders.declarations.addValueParameter
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irGetField
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irString
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.builders.irVararg
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.impl.IrClassReferenceImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstructorCallImpl
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.starProjectedType
import org.jetbrains.kotlin.ir.util.createDispatchReceiverParameter
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

// -- Host2JS convertToJs member injection --

/**
 * Inject the JVM external member `convertToJs(ctx: Long): Long` into every
 * @WithHost2JSBridge class (except value classes). The member is implemented by the generated
 * C (see CGenerator.emitHost2JsConvertToJs) and dispatched virtually by bridgeAnyToJs.
 */
internal fun injectJvmConvertToJsMembers(
  pluginContext: IrPluginContext,
  host2JsClasses: List<IrClass>,
) {
  for (clazz in host2JsClasses) {
    if (isInlineClass(clazz)) continue
    if (clazz.declarations.filterIsInstance<IrSimpleFunction>().any { it.name.asString() == "convertToJs" }) continue

    val member = pluginContext.irFactory.buildFun {
      name = Name.identifier("convertToJs")
      returnType = pluginContext.irBuiltIns.longType
      visibility = DescriptorVisibilities.PUBLIC
      origin = org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin.DEFINED
      isExternal = true
    }
    member.parent = clazz
    member.addValueParameter {
      name = Name.identifier("ctx")
      type = pluginContext.irBuiltIns.longType
    }
    member.addDispatchReceiver(clazz, pluginContext)
    clazz.declarations += member
  }
}

/**
 * Inject the Kotlin/Native `override fun convertToJs(ctx: CPointer<JSContext>): CValue<JSValue>`
 * member and the `Host2JsConvertible` supertype into every @WithHost2JSBridge class. The body
 * builds a prototype-based JS object and recursively converts each field via anyToJs.
 */
internal fun injectNativeConvertToJsMembers(
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
  host2JsClasses: List<IrClass>,
) {
  val anyToJsSymbol = resolveTopLevelFun(finder, "app.cash.zipline", "anyToJs")
  val setJsPropertySymbol = resolveTopLevelFun(finder, "app.cash.zipline", "setJsProperty")
  val newJsObjectSymbol = resolveTopLevelFun(finder, "app.cash.zipline", "newJsObject")
  val checkNotNullSymbol = resolveTopLevelFun(finder, "kotlin", "checkNotNull")
  val jsNewInt32Symbol = resolveTopLevelFun(finder, "app.cash.zipline.quickjs", "JS_NewInt32")
  val host2JsConvertible = finder.findClass(
    ClassId(FqName("app.cash.zipline"), Name.identifier("Host2JsConvertible")),
  )?.owner ?: error("Host2JsConvertible class not found")

  val ctxType = anyToJsSymbol.owner.parameters.first().type
  val jsValueType = anyToJsSymbol.owner.returnType

  for (clazz in host2JsClasses) {
    val fqName = clazz.fqNameWhenAvailable?.asString() ?: continue
    val protoFqn = resolveTargetFqn(clazz) ?: fqName

    // Skip classes with kotlin.Function*-typed fields (same rule as generateNativeBridgeFile).
    val hasUnsupported = extractFields(clazz, includeValBodyFields = true).any {
      it.isObjectType && it.ktType.startsWith("kotlin.Function")
    }
    if (hasUnsupported) continue
    if (clazz.declarations.filterIsInstance<IrSimpleFunction>().any { it.name.asString() == "convertToJs" }) continue

    clazz.superTypes += host2JsConvertible.defaultType

    val convertToJsInInterface = host2JsConvertible.declarations
      .filterIsInstance<IrSimpleFunction>()
      .firstOrNull { it.name.asString() == "convertToJs" }
      ?.symbol
      ?: error("Host2JsConvertible.convertToJs not found")

    val member = pluginContext.irFactory.buildFun {
      name = Name.identifier("convertToJs")
      returnType = jsValueType
      visibility = DescriptorVisibilities.PUBLIC
      origin = org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin.DEFINED
    }
    member.parent = clazz
    member.addValueParameter {
      name = Name.identifier("ctx")
      type = ctxType
    }
    member.overriddenSymbols += convertToJsInInterface
    member.addDispatchReceiver(clazz, pluginContext)
    addOptInAnnotation(member, finder, pluginContext)

    val builder = pluginContext.irBuiltIns.createIrBuilder(member.symbol)
    member.body = builder.irBlockBody {
      val obj = irTemporary(
        irCall(checkNotNullSymbol).apply {
          typeArguments[0] = jsValueType
          arguments[0] = irCall(newJsObjectSymbol).apply {
            arguments[0] = irGet(member.parameters.first())
            arguments[1] = irString(protoFqn)
          }
        },
      )
      if (clazz.kind == ClassKind.ENUM_CLASS) {
        val ordinalGetter = finder.findProperties(
          CallableId(ClassId(FqName("kotlin"), Name.identifier("Enum")), Name.identifier("ordinal")),
        ).mapNotNull { it.owner.getter }.firstOrNull()?.symbol
          ?: error("kotlin.Enum.ordinal getter not found")
        +irCall(setJsPropertySymbol).apply {
          arguments[0] = irGet(member.parameters.first())
          arguments[1] = irGet(obj)
          arguments[2] = irString("ordinal_1")
          arguments[3] = irCall(jsNewInt32Symbol).apply {
            arguments[0] = irGet(member.parameters.first())
            arguments[1] = irCall(ordinalGetter).apply {
              dispatchReceiver = irGet(member.dispatchReceiverParameter!!)
            }
          }
        }
      } else if (isInlineClass(clazz)) {
        // Value class: convert the underlying value directly (primitives carry no prototype).
        val underlyingField = clazz.properties.singleOrNull()?.backingField
          ?: error("HOST2JS: value class $fqName has no backing field")
        +irReturn(
          irCall(anyToJsSymbol).apply {
            arguments[0] = irGet(member.parameters.first())
            arguments[1] = irGetField(irGet(member.dispatchReceiverParameter!!), underlyingField)
          },
        )
      } else {
        for (field in extractFields(clazz, includeValBodyFields = true)) {
          val backingField = clazz.properties.firstOrNull { it.name.asString() == field.name }?.backingField
            ?: error("HOST2JS: no backing field for ${clazz.name.asString()}.${field.name}")
          +irCall(setJsPropertySymbol).apply {
            arguments[0] = irGet(member.parameters.first())
            arguments[1] = irGet(obj)
            arguments[2] = irString(field.jsPropertyName)
            arguments[3] = irCall(anyToJsSymbol).apply {
              arguments[0] = irGet(member.parameters.first())
              arguments[1] = irGetField(irGet(member.dispatchReceiverParameter!!), backingField)
            }
          }
        }
        +irGet(obj)
      }
    }
    clazz.declarations += member
  }
}

private fun resolveTopLevelFun(finder: DeclarationFinder, packageName: String, name: String): IrSimpleFunctionSymbol {
  return finder.findFunctions(CallableId(FqName(packageName), Name.identifier(name)))
    .firstOrNull()
    ?: error("$packageName.$name not found")
}

/** Attach a dispatch receiver to an injected member (the parameters list is the single source of truth in 2.3). */
private fun IrSimpleFunction.addDispatchReceiver(clazz: IrClass, pluginContext: IrPluginContext) {
  val builder = IrValueParameterBuilder().apply {
    name = Name.identifier("this")
    type = clazz.defaultType
    origin = org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin.DEFINED
    kind = org.jetbrains.kotlin.ir.declarations.IrParameterKind.DispatchReceiver
  }
  val receiver = pluginContext.irFactory.buildValueParameter(builder, clazz)
  parameters = listOf(receiver) + parameters
}

/** @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class) on the generated member. */
private fun addOptInAnnotation(
  function: IrSimpleFunction,
  finder: DeclarationFinder,
  pluginContext: IrPluginContext,
) {
  val optInClass = finder.findClass(ClassId(FqName("kotlin"), Name.identifier("OptIn")))?.owner ?: return
  val ctor = optInClass.declarations.filterIsInstance<IrConstructor>().firstOrNull { it.isPrimary } ?: return
  val markerClass = finder.findClass(
    ClassId(FqName("kotlinx.cinterop"), Name.identifier("ExperimentalForeignApi")),
  )?.owner ?: return

  val kClassType = pluginContext.irBuiltIns.kClassClass.starProjectedType
  val markerRef = IrClassReferenceImpl(
    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
    kClassType, markerClass.symbol, markerClass.defaultType,
  )
  val builder = pluginContext.irBuiltIns.createIrBuilder(function.symbol)
  val vararg = builder.irVararg(kClassType, listOf(markerRef))
  function.annotations += IrConstructorCallImpl(
    UNDEFINED_OFFSET, UNDEFINED_OFFSET,
    ctor.returnType, ctor.symbol, 0, 1,
  ).apply {
    arguments[0] = vararg
  }
}
