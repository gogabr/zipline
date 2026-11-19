package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.IrBuilderWithScope
import org.jetbrains.kotlin.ir.builders.irBlockBody
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.builders.irCallConstructor
import org.jetbrains.kotlin.ir.builders.irGet
import org.jetbrains.kotlin.ir.builders.irIfThenElse
import org.jetbrains.kotlin.ir.builders.irInt
import org.jetbrains.kotlin.ir.builders.irNotEquals
import org.jetbrains.kotlin.ir.builders.irReturn
import org.jetbrains.kotlin.ir.builders.irTemporary
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.declarations.IrDeclarationWithName
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueDeclaration
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrBody
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrExpressionBody
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.primaryConstructor
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.IrVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid

/**
 * Fills the body of every `bridgeCreate` that [ZiplineBridgeFirExtension] declared.
 *
 * The body reads the flag words the caller passed and, for each parameter whose bit is set,
 * evaluates that parameter's *own* default expression instead of the argument. The expressions are
 * the primary constructor's `defaultValue`s, deep-copied, which is what keeps them honest: it is the
 * same IR the frontend produced for the declared defaults, so a default that calls a `private`
 * top-level function of the file or reads a `private` companion member works here exactly as it does
 * in a plain `Foo(a)` call — and, because the copy lands in the companion, the access stays inside
 * the class's own rights. Nothing about the value is re-derived from its type or its name.
 *
 * The copy remaps the constructor's value parameters onto the factory's own, so a default that
 * mentions an earlier parameter (`val b: Int = a + 1`) reads the argument the caller supplied rather
 * than a symbol belonging to the constructor.
 */
internal fun fillBridgeCreateBodies(moduleFragment: IrModuleFragment, context: IrPluginContext) {
  moduleFragment.files.forEach { file ->
    file.acceptVoid(
      object : IrVisitorVoid() {
        override fun visitElement(element: IrElement) {
          element.acceptChildrenVoid(this)
        }

        override fun visitSimpleFunction(declaration: IrSimpleFunction) {
          if (declaration.isBridgeCreate()) declaration.body = bridgeCreateBody(declaration, context)
          super.visitSimpleFunction(declaration)
        }

        override fun visitClass(declaration: IrClass) {
          reportBridgeCreateConflict(declaration, context)
          reportBridgeContainerConflict(declaration, context)
          super.visitClass(declaration)
        }
      },
    )
  }
}

/** The factory we declared: our name, produced by our plugin, still without a body. */
private fun IrSimpleFunction.isBridgeCreate(): Boolean = name == BRIDGE_CREATE_NAME &&
    body == null &&
    (origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey === BridgeGeneratedKey

private fun IrDeclaration.isBridgeGenerated(): Boolean = (origin as? IrDeclarationOrigin.GeneratedByPlugin)?.pluginKey === BridgeGeneratedKey

/**
 * Fail the build when a bridged class declares a nested `Bridge` of its own.
 *
 * The factory lives in a nested object named `Bridge` (see BRIDGE_CONTAINER_NAME), so a `Bridge` from
 * the source leaves nowhere to put it. Declining silently would leave the generated host bridge to
 * call whatever the author wrote -- a function with an unrelated signature, reached through a name the
 * bridge believes it owns -- so the class is named and the author is asked to rename. Reporting here
 * rather than in FIR, for the same reason as [reportBridgeCreateConflict]: this is where the plugin has
 * a voice.
 */
private fun reportBridgeContainerConflict(clazz: IrClass, context: IrPluginContext) {
  if (clazz.annotations.none { it.type.classFqName?.asString() in BRIDGE_ANNOTATION_FQ_NAMES }) return
  val foreign = clazz.declarations
    .filterIsInstance<IrClass>()
    .filter { it.name == BRIDGE_CONTAINER_NAME && !it.isBridgeGenerated() }
  if (foreign.isEmpty()) return
  val ownerName = clazz.classId?.asSingleFqName()?.asString() ?: clazz.name.asString()
  context.messageCollector.report(
    CompilerMessageSeverity.ERROR,
    "The Zipline bridge plugin generates a nested object named `Bridge` in $ownerName to hold " +
      "`bridgeCreate`, but $ownerName declares a nested classifier of that name. Rename it: the " +
      "generated object is what the generated host bridges reach through to construct $ownerName.",
  )
}

/** The annotations that make a class a bridge payload, and so a class the plugin nests a `Bridge` in. */
private val BRIDGE_ANNOTATION_FQ_NAMES = setOf(
  "app.cash.zipline.bridge.support.WithJS2HostBridge",
  "app.cash.zipline.bridge.support.WithHost2JSBridge",
)

/**
 * Fail the build when a bridged class declares `bridgeCreate` of its own.
 *
 * Silently keeping both would let a host bridge call the user's function, which is how a wrong value
 * gets constructed and shipped; silently preferring ours would delete a member the source declares.
 * Both are worse than naming the class and both members, so this reports an ERROR and leaves the
 * author the choice of renaming.
 *
 * The check is here rather than in the FIR extension because that is where this plugin has a voice:
 * the FIR phase in this Kotlin exposes no message collector to a declaration-generation extension, and
 * building a proper diagnostic needs a registered diagnostic factory, which is more machinery than
 * the failure is worth. Reporting at IR time still fails the build before any output is written.
 */

private fun reportBridgeCreateConflict(companion: IrClass, context: IrPluginContext) {
  if (companion.functions.none { it.isBridgeGenerated() && it.name == BRIDGE_CREATE_NAME }) return
  val foreign = companion.declarations.filter {
    (it as? IrDeclarationWithName)?.name == BRIDGE_CREATE_NAME && !it.isBridgeGenerated()
  }
  if (foreign.isEmpty()) return
  val owner = runCatching { companion.parentAsClass }.getOrNull()
  val ownerName = owner?.classId?.asSingleFqName()?.asString() ?: owner?.name?.asString() ?: companion.name.asString()
  val described = foreign.joinToString("; ") { declaration ->
    when (declaration) {
      is IrSimpleFunction -> "a function `bridgeCreate` of its own"
      is IrProperty -> "a property `bridgeCreate`"
      else -> "a member `bridgeCreate`"
    }
  }
  context.messageCollector.report(
    CompilerMessageSeverity.ERROR,
    "The Zipline bridge plugin generates `bridgeCreate` in a nested object of $ownerName, but that " +
      "class also declares $described. Rename the declaration in the source: the generated factory is " +
      "what the generated host bridges call to construct $ownerName.",
  )
}

/**
 * The factory's own parameters, counted from the end: `parameters` also lists the receivers, and
 * this Kotlin deprecates every accessor for telling them apart. Counting from the end is safe here
 * because the signature the plugin declared ends with the mask words and the constructor parameters.
 */
private fun IrSimpleFunction.bridgeArguments(expected: Int): List<IrValueParameter> {
  val declared = parameters.filterIsInstance<IrValueParameter>()
  require(declared.size >= expected) {
    "bridgeCreate declares ${declared.size} parameters, expected $expected of them plus receivers"
  }
  return declared.takeLast(expected)
}

private fun bridgeCreateBody(function: IrSimpleFunction, context: IrPluginContext): IrBody {
  val target = (function.parent as IrClass).parentAsClass
  val constructor = target.primaryConstructor
    ?: error("bridgeCreate was declared for ${target.classId ?: target.name}, which has no primary constructor")

  val ctorParams = constructor.parameters.filterIsInstance<IrValueParameter>()
  val wordCount = maskWordCount(ctorParams.size)
  val arguments = function.bridgeArguments(wordCount + ctorParams.size)
  val maskParams = arguments.take(wordCount)
  val argParams = arguments.drop(wordCount)

  // A default expression copied verbatim would still point at the constructor's parameters. Reads of
  // a constructor parameter are redirected instead — to the *chosen value* of that parameter, held in
  // the local computed just before this one, so `val b: Int = a + 1` sees the default that `a` took
  // rather than the placeholder the caller passed for a parameter the guest omitted.
  val chosen = mutableMapOf<IrSymbol, IrValueDeclaration>()

  return DeclarationIrBuilder(context, function.symbol, UNDEFINED_OFFSET, UNDEFINED_OFFSET)
    .irBlockBody {
      // One local per parameter, in declaration order: a default is evaluated at most once, and only
      // the parameters whose flags are clear get their arguments.
      ctorParams.forEachIndexed { index, ctorParam ->
        val local = irTemporary(
          bridgeArgument(ctorParam, argParams[index], maskParams, index, function, constructor, chosen),
          "p$index",
        )
        chosen[ctorParam.symbol] = local
      }
      val call = irCallConstructor(constructor.symbol, emptyList())
      ctorParams.forEach { ctorParam -> call.arguments[ctorParam] = irGet(chosen.getValue(ctorParam.symbol)) }
      +irReturn(call)
    }
}

/**
 * Either the argument the caller supplied, or — when the parameter's flag bit is set — the declared
 * default: `(mask[word] and bit) != 0 ? <default> : argument`.
 */
private fun IrBuilderWithScope.bridgeArgument(
  ctorParam: IrValueParameter,
  argument: IrValueParameter,
  maskParams: List<IrValueParameter>,
  index: Int,
  factory: IrSimpleFunction,
  constructor: IrConstructor,
  chosen: Map<IrSymbol, IrValueDeclaration>,
): IrExpression {
  val supplied = irGet(argument)
  val default = ctorParam.defaultValue ?: return supplied
  if (maskParams.isEmpty()) return copyDefault(default, factory, constructor, chosen)

  val and = context.irBuiltIns.intAndSymbol
  // `Int.and(other)` lists its receiver and then the operand; the operand is what gets the flag bit.
  val andOperand = and.owner.parameters.last()
  val flagIsSet = irNotEquals(
    irCall(and).apply {
      dispatchReceiver = irGet(maskParams[maskWordIndex(index)])
      arguments[andOperand] = irInt(maskBit(index))
    },
    irInt(0),
  )
  return irIfThenElse(
    type = argument.type,
    condition = flagIsSet,
    thenPart = copyDefault(default, factory, constructor, chosen),
    elsePart = supplied,
  )
}

/**
 * The declared default, moved into [factory]: deep-copied so the same expression is not shared
 * between the constructor and the factory, with every read of a constructor parameter redirected to
 * the local that holds that parameter's chosen value.
 */
private fun IrBuilderWithScope.copyDefault(
  body: IrExpressionBody,
  factory: IrSimpleFunction,
  constructor: IrConstructor,
  chosen: Map<IrSymbol, IrValueDeclaration>,
): IrExpression {
  val copy = body.expression.deepCopyWithSymbols(factory)
  val reader =
    object : IrElementTransformerVoid() {
      override fun visitGetValue(expression: IrGetValue): IrExpression = chosen[expression.symbol]?.let { irGet(it) } ?: super.visitGetValue(expression)
    }
  // transformChildrenVoid skips the node it is given, and a default may be nothing but a read.
  val rewritten: IrExpression =
    (copy as? IrGetValue)?.let(reader::visitGetValue)
      ?: copy.also { it.transformChildrenVoid(reader) } as IrExpression

  // Invariant: after the rewrite no read of a constructor parameter survives, because every one that
  // was mapped now reads a local of the factory instead. A survivor means the copy kept a symbol that
  // belongs to the constructor — IR that would fail in the backend, far from the cause — so stop here
  // and name the class and parameter. It cannot be provoked from Kotlin source (a default may not read
  // a later parameter, and every earlier one is mapped), which makes this a check on this file, not a
  // diagnostic for a user: the message is written for whoever edits this transformer next.
  val parameters = constructor.parameters.filterIsInstance<IrValueParameter>().map { it.symbol }.toSet()
  val surviving = mutableListOf<IrGetValue>()
  if ((rewritten as? IrGetValue)?.symbol in parameters) surviving += rewritten as IrGetValue
  rewritten.acceptChildrenVoid(
    object : IrVisitorVoid() {
      override fun visitElement(element: IrElement) {
        element.acceptChildrenVoid(this)
      }

      override fun visitGetValue(expression: IrGetValue) {
        if (expression.symbol in parameters) surviving += expression
      }
    },
  )
  if (surviving.isNotEmpty()) {
    val owner = factory.parentAsClass.let { it.classId?.asSingleFqName()?.asString() ?: it.name.asString() }
    error(
      "unmapped parameter in default for $owner.${ctorNameOf(body, constructor)}: " +
        surviving.joinToString(", ") { it.symbol.owner.name.asString() },
    )
  }
  return rewritten
}

/** The parameter whose default this is, for the invariant message above. */
private fun ctorNameOf(body: IrExpressionBody, constructor: IrConstructor): String = constructor.parameters.filterIsInstance<IrValueParameter>().firstOrNull { it.defaultValue === body }?.name?.asString()
    ?: "<unknown parameter>"
