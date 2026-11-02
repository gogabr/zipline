package app.cash.zipline.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.declarations.buildFun
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal val lazyClassId = ClassId(FqName("kotlin"), Name.identifier("Lazy"))

/**
 * Builds `lazy { <body> }` as an expression of type `Lazy<valueType>`.
 *
 * The initializer is compiled as a local `Function0` so the serializer lookup it performs runs on
 * first access instead of when the enclosing `ziplineFunctions()` call runs. That keeps a missing
 * serializer a call-time failure rather than a `take()`/`bind()`-time failure.
 */
internal fun IrPluginContext.irLazy(
  valueType: IrType,
  enclosingDeclaration: IrDeclarationParent,
  body: (IrBuilderWithScope, IrSimpleFunction) -> IrExpression,
): IrExpression {
  val builtIns = irBuiltIns
  val lazyFunction = referenceFunctions(CallableId(FqName("kotlin"), Name.identifier("lazy")))
    .single {
      it.owner.parameters.size == 1 &&
        it.owner.parameters.single().type.getClass()?.classId == builtIns.functionN(0).classId
    }

  val initializer = irFactory.buildFun {
    name = Name.identifier("<anonymous>")
    returnType = valueType
    visibility = DescriptorVisibilities.LOCAL
    origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
  }
  initializer.parent = enclosingDeclaration
  val initializerBuilder = builtIns.createIrBuilder(initializer.symbol)
  val bodyExpression = body(initializerBuilder, initializer)
  initializer.body = initializerBuilder.irBlockBody(UNDEFINED_OFFSET, UNDEFINED_OFFSET) {
    +irReturn(bodyExpression)
  }

  val functionExpression = IrFunctionExpressionImpl(
    UNDEFINED_OFFSET,
    UNDEFINED_OFFSET,
    builtIns.functionN(0).typeWith(valueType),
    initializer,
    IrStatementOrigin.LAMBDA,
  )
  return initializerBuilder.irCall(lazyFunction).apply {
    type = referenceClass(lazyClassId)!!.typeWith(valueType)
    arguments[0] = functionExpression
  }
}
