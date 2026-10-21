package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocationWithRange
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.IrAnnotationContainer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrFile
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrConst
import org.jetbrains.kotlin.ir.expressions.IrConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrDelegatingConstructorCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.IrSetField
import org.jetbrains.kotlin.ir.expressions.IrTypeOperator
import org.jetbrains.kotlin.ir.expressions.IrTypeOperatorCall
import org.jetbrains.kotlin.ir.expressions.IrVararg
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.SYNTHETIC_OFFSET
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.fileOrNull
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

// -- CLV-88: compile-time safety subset for direct-bridge payload types --
//
// A bridge-annotated class may only carry fields whose type is *good* (it provably crosses
// the bridge) or a dynamic slot (`Any?` or an unbounded type parameter). A slot is checked
// at every write -- initializer, assignment, or constructor argument -- so no `Any?` chain
// can smuggle an unbridgeable value across the bridge.
//
// The subset is deliberately conservative: the only field type allowed without being good is
// a function type, which the generators handle specially. Bridge annotations are sticky -- a
// subclass inherits its ancestors' annotations and is validated, and given a converter, as if
// it carried them itself. Code that passes this check is guaranteed safe at crossing time. A
// site that is safe by argument but opaque to the static type (e.g. a decode boundary typed
// `Any?`) opts out with `@Suppress("BRIDGE_UNSAFE")` on the source variable or an enclosing
// declaration.

/** Severity for a violation. Single constant: downgrade here for a softer rollout. */
private val BRIDGE_TYPE_SEVERITY = CompilerMessageSeverity.ERROR

/** `@Suppress` key that opts a write out of the `Any?`-slot check. */
private const val BRIDGE_UNSAFE = "BRIDGE_UNSAFE"

private val PRIMITIVES = setOf(
  "kotlin.Boolean",
  "kotlin.Byte",
  "kotlin.Char",
  "kotlin.Short",
  "kotlin.Int",
  "kotlin.Long",
  "kotlin.Float",
  "kotlin.Double",
)

private val LIST_LIKE = setOf(
  "kotlin.collections.List",
  "kotlin.collections.MutableList",
  "kotlin.collections.Collection",
  "kotlin.collections.Iterable",
  "kotlin.collections.Set",
  "kotlin.collections.MutableSet",
)

private val MAP_LIKE = setOf(
  "kotlin.collections.Map",
  "kotlin.collections.MutableMap",
  "kotlin.collections.HashMap",
  "kotlin.collections.LinkedHashMap",
)

/** Bridge direction a type is validated against. */
internal enum class BridgeDirection {
  /** JS -> host (`@WithJS2HostBridge`). */
  JS2HOST,

  /** Host -> JS (`@WithHost2JSBridge`). */
  HOST2JS,
  ;

  private fun carries(clazz: IrClass): Boolean = when (this) {
    JS2HOST -> hasWithJS2HostBridgeAnnotation(clazz)
    HOST2JS -> hasWithHost2JSBridgeAnnotation(clazz)
  }

  /**
   * Whether every value of [type] provably crosses this direction. Single source of truth
   * for both field declarations and `Any?`-slot values.
   *
   * Good: primitives, `String`, `Nothing`, primitive arrays, classes that carry this
   * direction's annotation (their own or an ancestor's), value classes over a
   * primitive/String (JS->host only), enums (JS->host only), and `List`/`Array`/`Set`/`Map`
   * whose element/key/value types are good.
   *
   * Not good: `Any`/`Any?`, unbounded type parameters, `kotlin.Function*`, and any class
   * that does not carry the annotation -- final or not.
   */
  fun isGood(type: IrType?): Boolean {
    if (type == null) return false
    val typeParameter = (type as? IrSimpleType)?.classifier?.owner as? IrTypeParameter
    if (typeParameter != null) {
      val bound = typeParameter.superTypes.firstOrNull() ?: return false
      if (bound.classFqName?.asString() == "kotlin.Any") return false
      return isGood(bound)
    }
    val fqn = type.classFqName?.asString() ?: return false
    when {
      fqn == "kotlin.Nothing" -> return true

      fqn in PRIMITIVES || fqn == "kotlin.String" -> return true

      fqn in arrayKotlinTypes && fqn != "kotlin.Array" -> return true

      fqn == "kotlin.Any" -> return false

      fqn.startsWith("kotlin.Function") -> return false

      fqn in LIST_LIKE || fqn == "kotlin.Array" -> return isGood(typeArgument(type, 0))

      fqn in MAP_LIKE ->
        return isGood(typeArgument(type, 0)) && isGood(typeArgument(type, 1))

      else -> {
        val clazz = (type as? IrSimpleType)?.getClass() ?: return false
        // An explicit (own or inherited) annotation is the author's contract and wins.
        if (carries(clazz)) return true
        if (isInlineClass(clazz)) {
          // An unannotated value class over a primitive/String crosses JS->host as a boxed
          // number; host->JS needs the class's own convertToJs, so it is not good there.
          val underlying = unwrapInlineUnderlying(clazz)
          if (underlying != null && (underlying in PRIMITIVES || underlying == "kotlin.String")) {
            return this == JS2HOST
          }
        }
        if (clazz.kind == ClassKind.ENUM_CLASS) return this == JS2HOST
        return false
      }
    }
  }

  /**
   * The first type in [type]'s structure that is not good, or null when the whole type is
   * good. Descends into collection element/key/value types so the diagnostic names the
   * offending leaf -- the `Any` inside a `List<Any?>` -- rather than the outer container.
   */
  fun firstBad(type: IrType?): IrType? {
    if (type == null || isGood(type)) return null
    val fqn = type.classFqName?.asString() ?: return type
    if (fqn in LIST_LIKE || fqn == "kotlin.Array") {
      return firstBad(typeArgument(type, 0)) ?: type
    }
    if (fqn in MAP_LIKE) {
      return firstBad(typeArgument(type, 0)) ?: firstBad(typeArgument(type, 1)) ?: type
    }
    return type
  }
}

/**
 * Enforces the safety subset on [js2HostClasses] / [host2JsClasses]: field types must be
 * good or a dynamic slot (`Any?` / unbounded type parameter), and every value written into a
 * dynamic slot must be a good type.
 */
internal fun validateBridgeSafety(
  moduleFragment: IrModuleFragment,
  pluginContext: IrPluginContext,
  js2HostClasses: List<IrClass>,
  host2JsClasses: List<IrClass>,
) {
  val checker = BridgeSafetyChecker(pluginContext)
  checker.checkFields(js2HostClasses, host2JsClasses)
  checker.checkAnySlotAssignments(moduleFragment)
}

private class BridgeSafetyChecker(
  private val pluginContext: IrPluginContext,
) {
  /**
   * The bridge directions [clazz] carries, empty when it is not bridge-annotated. The
   * annotation helpers walk the supertype chain, which covers inherited annotations for
   * classes declared in this module and for classes loaded from a dependency's metadata: the
   * annotation has BINARY retention, so a separately compiled bridge class reports it too.
   */
  private fun directions(clazz: IrClass): Set<BridgeDirection> = buildSet {
    if (hasWithJS2HostBridgeAnnotation(clazz)) add(BridgeDirection.JS2HOST)
    if (hasWithHost2JSBridgeAnnotation(clazz)) add(BridgeDirection.HOST2JS)
  }

  /**
   * A field or parameter holding a dynamic value: `Any?` or an unbounded type parameter.
   * Allowed as a field type, but every write into it must carry a good type. A bounded type
   * parameter is not a slot: it is validated through its bound.
   */
  private fun isDynamicSlot(type: IrType?): Boolean {
    if (type == null) return false
    if (type.classFqName?.asString() == "kotlin.Any") return true
    val typeParameter = (type as? IrSimpleType)?.classifier?.owner as? IrTypeParameter ?: return false
    val bound = typeParameter.superTypes.firstOrNull() ?: return true
    return bound.classFqName?.asString() == "kotlin.Any"
  }

  /**
   * Field types must be good or a dynamic slot. Function-typed fields are allowed: the
   * generators handle them specially and [NativeGenerator] already warns about them, so they
   * are never reported here.
   */
  fun checkFields(js2HostClasses: List<IrClass>, host2JsClasses: List<IrClass>) {
    for (clazz in (js2HostClasses + host2JsClasses).distinct()) {
      val dirs = directions(clazz)
      if (dirs.isEmpty()) continue
      for (field in extractFields(clazz, includeValBodyFields = true)) {
        if (isDynamicSlot(field.type)) continue
        if (field.ktType.startsWith("kotlin.Function")) continue
        for (dir in dirs) {
          val bad = dir.firstBad(field.type) ?: break
          if (bad.isEnumType()) {
            // A nullable enum field can still cross as null, and the generated convertToJs
            // already refuses a non-null value by name, so this is a warning, not an error.
            report(
              CompilerMessageSeverity.WARNING,
              "bridge: field '${clazz.name.asString()}.${field.name}' has enum type " +
                "'${bad.classFqName?.asString()}' that cannot cross the host-to-JS bridge as a value; " +
                "the guest would only see a lookalike, not an enum instance",
              locate(clazz, field.name),
            )
          } else {
            report(
              BRIDGE_TYPE_SEVERITY,
              "bridge: field '${clazz.name.asString()}.${field.name}' has type " +
                "'${field.ktType}' that cannot cross the bridge; use a primitive, String, " +
                "a bridge-annotated class, a List/Array/Map of allowed types, or Any?",
              locate(clazz, field.name),
            )
          }
          break
        }
      }
    }
  }

  /**
   * Every write into an `Any?` field or `Any?` constructor parameter of a bridge-annotated
   * class must carry a good static type. This is what forbids `Any?` chains: a value that is
   * itself `Any?` cannot be laundered into a bridge slot.
   */
  fun checkAnySlotAssignments(moduleFragment: IrModuleFragment) {
    val visitor = object : IrElementTransformerVoid() {
      private var currentConstructor: IrConstructor? = null
      private var currentFile: IrFile? = null

      override fun visitFile(declaration: IrFile): IrFile {
        val prev = currentFile
        currentFile = declaration
        try {
          return super.visitFile(declaration)
        } finally {
          currentFile = prev
        }
      }

      override fun visitConstructor(declaration: IrConstructor): IrStatement {
        val prev = currentConstructor
        currentConstructor = declaration
        try {
          return super.visitConstructor(declaration)
        } finally {
          currentConstructor = prev
        }
      }

      override fun visitField(declaration: IrField): IrStatement {
        val clazz = declaration.parent as? IrClass
        if (clazz != null && isDynamicSlot(declaration.type)) {
          val dirs = directions(clazz)
          val init = declaration.initializer?.expression
          // Backing-field plumbing `this.value = value`: the constructor parameter is `Any?`
          // by declaration, so it is not a chain. The real argument is validated at the
          // constructor call site.
          val ctorPlumbing = isOwnParamRead(init, clazz)
          if (dirs.isNotEmpty() && !ctorPlumbing) {
            checkValue(init, dirs, "field '${clazz.name.asString()}.${declaration.name.asString()}'", declaration, currentFile)
          }
        }
        return super.visitField(declaration)
      }

      override fun visitSetField(expression: IrSetField): IrExpression {
        val field = expression.symbol.owner
        val clazz = field.parent as? IrClass
        if (clazz != null && isDynamicSlot(field.type)) {
          // Generated `this.x = x` constructor plumbing: the real argument is validated at
          // the call site, so the parameter-to-field copy is not a chain.
          val value = expression.value
          val ownCtorParam = isOwnParamRead(value, clazz)
          if (!ownCtorParam) {
            val dirs = directions(clazz)
            if (dirs.isNotEmpty()) {
              checkValue(value, dirs, "field '${clazz.name.asString()}.${field.name.asString()}'", expression, currentFile)
            }
          }
        }
        return super.visitSetField(expression)
      }

      override fun visitConstructorCall(expression: IrConstructorCall): IrExpression {
        val ctor = expression.symbol.owner
        val clazz = ctor.parent as? IrClass
        if (clazz != null) {
          val dirs = directions(clazz)
          if (dirs.isNotEmpty()) {
            for (parameter in ctor.parameters.filter { it.kind == IrParameterKind.Regular }) {
              if (!isDynamicSlot(parameter.type)) continue
              checkValue(
                expression.arguments[parameter],
                dirs,
                "constructor parameter '${parameter.name.asString()}' of '${clazz.name.asString()}'",
                expression,
                currentFile,
              )
            }
          }
        }
        return super.visitConstructorCall(expression)
      }

      /**
       * `class Sub : Base(value)` compiles to a delegating constructor call rather than an
       * [IrConstructorCall] in the class body, so it needs the same `Any?`-argument check.
       */
      override fun visitDelegatingConstructorCall(
        expression: IrDelegatingConstructorCall,
      ): IrExpression {
        val clazz = expression.symbol.owner.parent as? IrClass
        if (clazz != null) {
          val dirs = directions(clazz)
          if (dirs.isNotEmpty()) {
            for (parameter in expression.symbol.owner.parameters.filter { it.kind == IrParameterKind.Regular }) {
              if (!isDynamicSlot(parameter.type)) continue
              checkValue(
                expression.arguments[parameter],
                dirs,
                "constructor parameter '${parameter.name.asString()}' of '${clazz.name.asString()}'",
                expression,
                currentFile,
              )
            }
          }
        }
        return super.visitDelegatingConstructorCall(expression)
      }

      override fun visitCall(expression: IrCall): IrExpression {
        val setter = expression.symbol.owner as? IrSimpleFunction
        if (setter != null && setter.name.asString().startsWith("<set-")) {
          val property = setter.correspondingPropertySymbol?.owner
          val field = property?.backingField
          val clazz = field?.parent as? IrClass
          if (clazz != null && isDynamicSlot(field.type)) {
            val dirs = directions(clazz)
            if (dirs.isNotEmpty()) {
              val value = setter.parameters
                .firstOrNull { it.kind == IrParameterKind.Regular }
                ?.let { expression.arguments[it] }
              checkValue(value, dirs, "property '${property.name.asString()}' of '${clazz.name.asString()}'", expression, currentFile)
            }
          }
        }
        return super.visitCall(expression)
      }
    }
    for (file in moduleFragment.files) {
      file.transformChildrenVoid(visitor)
    }
  }

  /** Reports [value] unless it provably crosses every direction in [dirs]. */
  private fun checkValue(
    value: IrExpression?,
    dirs: Set<BridgeDirection>,
    slot: String,
    at: IrElement,
    file: IrFile?,
  ) {
    if (value == null) return
    // Boxing a primitive into Any? erases the static type; judge the unboxed node instead.
    var expr: IrExpression = value
    while (expr is IrTypeOperatorCall && expr.operator == IrTypeOperator.IMPLICIT_CAST) {
      expr = expr.argument
    }
    // Every constant kind (null, the eight primitives, String) crosses the bridge.
    if (expr is IrConst) return
    // `@Suppress("BRIDGE_UNSAFE")` on the source variable or an enclosing declaration
    // vouches for a write the static type cannot prove safe (e.g. a decode boundary).
    if (isSuppressed(at, expr)) return
    if (dirs.all { it.isGood(expr.type) }) return
    val fqn = expr.type.classFqName?.asString() ?: return
    report(
      BRIDGE_TYPE_SEVERITY,
      "bridge: value of type '$fqn' assigned to $slot cannot cross the bridge; " +
        "an Any? bridge slot requires a primitive, String, a bridge-annotated class, " +
        "or a List/Array/Map of allowed types",
      file?.locationOf(at),
    )
  }

  private fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageSourceLocation?) {
    pluginContext.messageCollector.report(severity, message, location)
  }
}

/** The declaration to point a field diagnostic at: the property, its backing field, or the class. */
private fun locate(clazz: IrClass, fieldName: String): CompilerMessageSourceLocation? {
  val property: IrProperty? = clazz.properties.firstOrNull { it.name.asString() == fieldName }
  val element: IrDeclaration = property?.backingField ?: property ?: clazz
  return element.fileOrNull?.locationOf(element)
}

/**
 * Whether [expr] reads a parameter of a function or constructor declared on [clazz] itself.
 * Generated plumbing (`this.value = value` in a constructor or setter body) copies such a
 * parameter into the field; the real argument is validated at the call site, so the copy is
 * not an `Any?` chain.
 */
private fun isOwnParamRead(expr: IrExpression?, clazz: IrClass): Boolean {
  if (expr !is IrGetValue) return false
  val owner = expr.symbol.owner
  if (owner !is org.jetbrains.kotlin.ir.declarations.IrValueParameter) return false
  val fn = owner.parent as? org.jetbrains.kotlin.ir.declarations.IrFunction ?: return false
  return fn.parent == clazz
}

/**
 * Whether a bridge write is opted out via `@Suppress("BRIDGE_UNSAFE")`. The suppression may
 * sit on the reported element, on the value expression, on the declaration the value was
 * read from, or on any enclosing declaration up to the file.
 */
private fun isSuppressed(at: IrElement, value: IrExpression): Boolean = suppressedAt(at) || suppressedAt(value) || (value is IrGetValue && suppressedAt(value.symbol.owner))

private fun suppressedAt(element: Any?): Boolean {
  var node = element as? IrElement ?: return false
  while (true) {
    if (node is IrAnnotationContainer && node.annotations.any { it.suppressesBridgeUnsafe() }) return true
    node = (node as? IrDeclaration)?.parent ?: return false
  }
}

/** A `@Suppress("BRIDGE_UNSAFE")` annotation node. */
private fun IrConstructorCall.suppressesBridgeUnsafe(): Boolean {
  if (symbol.owner.returnType.getClass()?.classId != SUPPRESS_CLASS_ID) return false
  return arguments.filterNotNull().any { it.constStrings().contains(BRIDGE_UNSAFE) }
}

/** The string constants in an annotation argument (a vararg of names wraps them in IrVararg). */
private fun IrExpression.constStrings(): List<String> = when (this) {
  is IrConst -> listOfNotNull(value?.toString())
  is IrVararg -> elements.filterIsInstance<IrExpression>().flatMap { it.constStrings() }
  else -> emptyList()
}

private val SUPPRESS_CLASS_ID = ClassId(FqName("kotlin"), Name.identifier("Suppress"))

private fun IrType?.isEnumType(): Boolean = (this as? IrSimpleType)?.getClass()?.kind == ClassKind.ENUM_CLASS

/** Finds the line and column of [irElement] within this file. */
private fun IrFile.locationOf(irElement: IrElement?): CompilerMessageSourceLocation {
  val sourceRangeInfo = fileEntry.getSourceRangeInfo(
    beginOffset = irElement?.startOffset ?: SYNTHETIC_OFFSET,
    endOffset = irElement?.endOffset ?: SYNTHETIC_OFFSET,
  )
  return CompilerMessageLocationWithRange.create(
    path = sourceRangeInfo.filePath,
    lineStart = sourceRangeInfo.startLineNumber + 1,
    columnStart = sourceRangeInfo.startColumnNumber + 1,
    lineEnd = sourceRangeInfo.endLineNumber + 1,
    columnEnd = sourceRangeInfo.endColumnNumber + 1,
    lineContent = null,
  )!!
}
