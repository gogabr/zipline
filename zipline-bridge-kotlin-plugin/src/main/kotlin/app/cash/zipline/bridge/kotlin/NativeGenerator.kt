package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.name.FqName
import java.io.File

// -- Kotlin/Native bridge code generation (iOS) --
// Hermes rewrite: uses COpaquePointer? (context) + Int (handle) instead of
// CPointer<JSContext> + CValue<JSValue>. All JS value operations go through
// HermesBridge_* C functions declared in hermes-ios.h.

/** Marker for Redwood's generated-code-only APIs; the opt-in is only needed when the bridged class uses them. */
private val REDWOOD_CODEGEN_API_FQN = FqName("app.cash.redwood.RedwoodCodegenApi")

// -- Recursive JS → Kotlin collection conversion (List/Array/Map, incl. nested) --

private val MAP_KOTLIN_TYPES = setOf(
  "kotlin.collections.Map", "kotlin.collections.MutableMap",
  "kotlin.collections.HashMap", "kotlin.collections.LinkedHashMap",
)

/** Primitive arrays supported by the generators. */
internal val PRIMITIVE_ARRAY_ELEMENT_TYPE = mapOf(
  "kotlin.IntArray" to "kotlin.Int",
  "kotlin.FloatArray" to "kotlin.Float",
  "kotlin.BooleanArray" to "kotlin.Boolean",
  "kotlin.DoubleArray" to "kotlin.Double",
  "kotlin.CharArray" to "kotlin.Char",
  "kotlin.ShortArray" to "kotlin.Short",
  "kotlin.ByteArray" to "kotlin.Byte",
  "kotlin.LongArray" to "kotlin.Long",
)

/** Wraps a converted primitive into the full inline wrapper chain (Outer(Inner(value))). */
private fun inlineWrapExpression(field: FieldInfo, innerExpr: String): String {
  val chain = field.inlineWrapperChain.map { it.substringAfterLast(".") }
  return chain.foldRight(innerExpr) { name, acc -> "$name($acc)" }
}

/** Renders a Kotlin type expression for generated code (short names; classes must be imported). */
private fun renderedTypeName(type: IrType?): String {
  val simple = type as? IrSimpleType ?: return "Any"
  val base = effectiveClassFqn(simple)
  if (simple.arguments.isEmpty()) return base.substringAfterLast(".")
  return base.substringAfterLast(".") + simple.arguments.joinToString(", ", "<", ">") { arg ->
    renderedTypeName((arg as? IrTypeProjection)?.type ?: (arg as? IrType))
  }
}

/** Collects import statements for every class referenced by [type], including nested generics. */
private fun collectTypeImports(type: IrType?, acc: MutableSet<String>) {
  val fqn = effectiveClassFqn(type)
  if (fqn != "kotlin.Any") acc.add(importForType(fqn))
  (type as? IrSimpleType)?.arguments?.forEach { arg ->
    collectTypeImports((arg as? IrTypeProjection)?.type ?: (arg as? IrType), acc)
  }
}

/** Collects the zipline runtime imports needed to convert [type] and everything nested in it. */
private fun collectRuntimeImports(
  type: IrType?,
  needsBridgeForAny: MutableSet<Unit>,
  needsJsLong: MutableSet<Unit>,
) {
  val ktType = effectiveClassFqn(type)
  // Inline value class backed by Long (e.g. Color) converts via JsNumberToLong.
  val elemClass = (type as? IrSimpleType)?.getClass()
  if (elemClass != null && isInlineClass(elemClass) && unwrapInlineUnderlying(elemClass) == "kotlin.Long") {
    needsJsLong.add(Unit)
    return
  }
  when {
    ktType == "kotlin.Any" -> needsBridgeForAny.add(Unit)
    ktType == "kotlin.Long" -> needsJsLong.add(Unit)
    ktType in MAP_KOTLIN_TYPES -> {
      (type as? IrSimpleType)?.arguments?.forEach { arg ->
        collectRuntimeImports((arg as? IrTypeProjection)?.type ?: (arg as? IrType), needsBridgeForAny, needsJsLong)
      }
    }
    ktType == "kotlin.collections.List" || ktType == "kotlin.Array" -> {
      needsBridgeForAny.add(Unit)
      (type as? IrSimpleType)?.arguments?.forEach { arg ->
        collectRuntimeImports((arg as? IrTypeProjection)?.type ?: (arg as? IrType), needsBridgeForAny, needsJsLong)
      }
    }
    ktType in PRIMITIVE_ARRAY_ELEMENT_TYPE -> {
      if (PRIMITIVE_ARRAY_ELEMENT_TYPE[ktType] == "kotlin.Long") needsJsLong.add(Unit)
    }
    else -> needsBridgeForAny.add(Unit)
  }
}

internal fun generateNativeBridgeFile(
  outputDir: String,
  clazz: IrClass,
  messageCollector: MessageCollector? = null,
) {
  val fqn = clazz.fqNameWhenAvailable?.asString() ?: return
  val functionName = "${fqn.replace(".", "_")}_toKotlin"
  val fields = extractFields(clazz)

  // The generated code constructs the bridged class (and, for nested classes, its enclosing
  // class). If any of those is marked @RedwoodCodegenApi, the file must opt in — but only
  // then, so zipline-only builds never reference the Redwood annotation.
  val usesRedwoodCodegenApi =
    clazz.hasAnnotation(REDWOOD_CODEGEN_API_FQN) ||
      (clazz.parent as? IrClass)?.hasAnnotation(REDWOOD_CODEGEN_API_FQN) == true

  // zipline has no function bridge, so a NON-NULLABLE function-typed field makes the class
  // unconvertible. Say so instead of silently generating no converter: the host would otherwise
  // report a missing converter far from the cause. A nullable function field decodes to null.
  val nonNullableFunctionField = fields.firstOrNull {
    it.isObjectType && it.ktType.startsWith("kotlin.Function") && !it.isNullable
  }
  if (nonNullableFunctionField != null) {
    messageCollector?.report(
      CompilerMessageSeverity.WARNING,
      "No bridge generated for ${fqn}: its field '${nonNullableFunctionField.name}' has type " +
        "'${nonNullableFunctionField.ktType}', which zipline cannot convert. Make the field " +
        "nullable, or remove the bridge annotation.",
    )
    return
  }

  val source = buildString {
    appendLine("@file:Suppress(\"UNUSED_PARAMETER\", \"unused\", \"INVISIBLE_MEMBER\", \"INVISIBLE_REFERENCE\", \"UNCHECKED_CAST\")")
    if (usesRedwoodCodegenApi) {
      appendLine("@file:OptIn(app.cash.redwood.RedwoodCodegenApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)")
    } else {
      appendLine("@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)")
    }
    appendLine("package generated_bridges")
    appendLine()
    appendLine("import kotlinx.cinterop.*")
    appendLine("import app.cash.zipline.hermes.*")
    appendLine("import app.cash.zipline.registerBridge")
    val needsBridgeForAny = mutableSetOf<Unit>()
    val needsJsLong = mutableSetOf<Unit>()
    val needsJsBoxed = mutableSetOf<Unit>()
    for (field in fields) {
      collectRuntimeImports(field.type, needsBridgeForAny, needsJsLong)
      if (field.isInline && field.underlyingKtType == "kotlin.Long") needsJsLong.add(Unit)
      if (field.isInline) needsJsBoxed.add(Unit)
    }
    if (needsBridgeForAny.isNotEmpty()) {
      appendLine("import app.cash.zipline.bridgeForAny")
    }
    appendLine("import app.cash.zipline.jsEnumOrdinal")
    appendLine("import app.cash.zipline.jsCollectionToKotlin")
    appendLine("import app.cash.zipline.jsMapToKotlin")
    appendLine("import app.cash.zipline.CollectionKind")
    if (needsJsLong.isNotEmpty()) {
      appendLine("import app.cash.zipline.JsNumberToLong")
    }
    if (needsJsBoxed.isNotEmpty()) {
      appendLine("import app.cash.zipline.JsBoxedNumberToLong")
      appendLine("import app.cash.zipline.JsBoxedNumberToDouble")
    }
    // Import the target class and any inline wrapper types + object types
    val parentFqn = (clazz.parent as? IrClass)?.fqNameWhenAvailable?.asString()
    val importFqn = parentFqn ?: fqn
    val imports = mutableSetOf(importForType(importFqn))
    for (field in fields) {
      field.inlineWrapperChain.forEach { imports.add(importForType(it)) }
      if (field.isObjectType && field.ktType != "kotlin.Any" && field.ktType != "kotlin.collections.List" &&
        field.ktType !in MAP_KOTLIN_TYPES && field.effectiveKtType !in PRIMITIVE_ARRAY_ELEMENT_TYPE
      ) {
        imports.add(importForType(field.ktType))
      }
      collectTypeImports(field.type, imports)
    }
    imports.filter { it.isNotEmpty() }.sorted().forEach { appendLine(it) }
    appendLine()
    // Tag constants matching HermesBridge_getValueTag in hermes-ios.h
    appendLine("private const val TAG_NULL = 0")
    appendLine("private const val TAG_INT = 1")
    appendLine("private const val TAG_DOUBLE = 2")
    appendLine("private const val TAG_STRING = 3")
    appendLine("private const val TAG_BOOL = 4")
    appendLine("private const val TAG_OBJECT = 5")
    appendLine("private const val TAG_ARRAY = 6")
    appendLine("private const val TAG_UNDEFINED = 7")
    appendLine()
    appendLine("public fun $functionName(")
    appendLine("  ctx: COpaquePointer?,")
    appendLine("  jsValHandle: Int,")
    appendLine("): COpaquePointer? {")
    // Generate field reads
    val helpers = StringBuilder()
    for (field in fields) {
      val propName = field.jsPropertyName

      // Helpers to emit the common Hermes read/free preamble/suffix.
      fun readProperty(): String =
        "val ${field.name}Ref = HermesBridge_createHandle(ctx, jsValHandle, \"$propName\")"
      fun freeRef(): String = "HermesBridge_freeHandle(ctx, ${field.name}Ref)"

      when {
        field.isInline && field.underlyingKtType == "kotlin.Int" -> {
          appendLine("    ${readProperty()}")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_UNDEFINED || HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_NULL) null")
            appendLine("        else ${inlineWrapExpression(field, "JsBoxedNumberToDouble(ctx, ${field.name}Ref)?.toInt() ?: HermesBridge_getValueDouble(ctx, ${field.name}Ref).toInt()")}")
          } else {
            appendLine("    val ${field.name} = ${inlineWrapExpression(field, "JsBoxedNumberToDouble(ctx, ${field.name}Ref)?.toInt() ?: HermesBridge_getValueDouble(ctx, ${field.name}Ref).toInt()")}")
          }
          appendLine("    ${freeRef()}")
        }
        field.isInline && field.underlyingKtType == "kotlin.Float" -> {
          appendLine("    ${readProperty()}")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_UNDEFINED || HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_NULL) null")
            appendLine("        else ${inlineWrapExpression(field, "JsBoxedNumberToDouble(ctx, ${field.name}Ref)?.toFloat() ?: HermesBridge_getValueDouble(ctx, ${field.name}Ref).toFloat()")}")
          } else {
            appendLine("    val ${field.name} = ${inlineWrapExpression(field, "JsBoxedNumberToDouble(ctx, ${field.name}Ref)?.toFloat() ?: HermesBridge_getValueDouble(ctx, ${field.name}Ref).toFloat()")}")
          }
          appendLine("    ${freeRef()}")
        }
        field.isInline && field.underlyingKtType == "kotlin.Double" -> {
          appendLine("    ${readProperty()}")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_UNDEFINED || HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_NULL) null")
            appendLine("        else ${inlineWrapExpression(field, "JsBoxedNumberToDouble(ctx, ${field.name}Ref) ?: HermesBridge_getValueDouble(ctx, ${field.name}Ref)")}")
          } else {
            appendLine("    val ${field.name} = ${inlineWrapExpression(field, "JsBoxedNumberToDouble(ctx, ${field.name}Ref) ?: HermesBridge_getValueDouble(ctx, ${field.name}Ref)")}")
          }
          appendLine("    ${freeRef()}")
        }
        field.isInline && field.underlyingKtType == "kotlin.Long" -> {
          appendLine("    ${readProperty()}")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_UNDEFINED || HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_NULL) null")
            appendLine("        else ${inlineWrapExpression(field, "JsBoxedNumberToLong(ctx, ${field.name}Ref) ?: JsNumberToLong(ctx, ${field.name}Ref)")}")
          } else {
            appendLine("    val ${field.name} = ${inlineWrapExpression(field, "JsBoxedNumberToLong(ctx, ${field.name}Ref) ?: JsNumberToLong(ctx, ${field.name}Ref)")}")
          }
          appendLine("    ${freeRef()}")
        }
        field.effectiveKtType == "kotlin.Int" -> {
          appendLine("    ${readProperty()}")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_UNDEFINED || HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_NULL) null else HermesBridge_getValueDouble(ctx, ${field.name}Ref).toInt()")
          } else {
            appendLine("    val ${field.name} = HermesBridge_getValueDouble(ctx, ${field.name}Ref).toInt()")
          }
          appendLine("    ${freeRef()}")
        }
        field.effectiveKtType == "kotlin.Boolean" -> {
          appendLine("    ${readProperty()}")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_UNDEFINED || HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_NULL) null else HermesBridge_getValueBool(ctx, ${field.name}Ref) != 0")
          } else {
            appendLine("    val ${field.name} = HermesBridge_getValueBool(ctx, ${field.name}Ref) != 0")
          }
          appendLine("    ${freeRef()}")
        }
        field.effectiveKtType == "kotlin.Double" -> {
          appendLine("    ${readProperty()}")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_UNDEFINED || HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_NULL) null else HermesBridge_getValueDouble(ctx, ${field.name}Ref)")
          } else {
            appendLine("    val ${field.name} = HermesBridge_getValueDouble(ctx, ${field.name}Ref)")
          }
          appendLine("    ${freeRef()}")
        }
        field.effectiveKtType == "kotlin.Float" -> {
          appendLine("    ${readProperty()}")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_UNDEFINED || HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_NULL) null else HermesBridge_getValueDouble(ctx, ${field.name}Ref).toFloat()")
          } else {
            appendLine("    val ${field.name} = HermesBridge_getValueDouble(ctx, ${field.name}Ref).toFloat()")
          }
          appendLine("    ${freeRef()}")
        }
        field.effectiveKtType == "kotlin.Long" -> {
          appendLine("    ${readProperty()}")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_UNDEFINED || HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_NULL) null else JsNumberToLong(ctx, ${field.name}Ref)")
          } else {
            appendLine("    val ${field.name} = JsNumberToLong(ctx, ${field.name}Ref)")
          }
          appendLine("    ${freeRef()}")
        }
        field.effectiveKtType == "kotlin.String" -> {
          appendLine("    ${readProperty()}")
          appendLine("    val ${field.name}Str = HermesBridge_getValueString(ctx, ${field.name}Ref)")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_UNDEFINED || HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_NULL) null else ${field.name}Str?.toKStringFromUtf8()?.also { platform.posix.free(${field.name}Str) }")
          } else {
            appendLine("    val ${field.name} = ${field.name}Str?.toKStringFromUtf8()?.also { platform.posix.free(${field.name}Str) } ?: \"\"")
          }
          appendLine("    ${freeRef()}")
        }
        field.effectiveKtType in MAP_KOTLIN_TYPES -> {
          // The guest drives the iteration (see jsMapToKotlin): a host-side walk of a Kotlin/JS
          // Map relies on mangled member names, which production builds drop.
          val renderedKey = renderedTypeName(typeArgument(field.type, 0))
          val renderedValue = renderedTypeName(typeArgument(field.type, 1))
          appendLine("    val ${field.name}Ref = HermesBridge_createHandle(ctx, jsValHandle, \"$propName\")")
          appendLine("    val ${field.name}Tag = HermesBridge_getValueTag(ctx, ${field.name}Ref)")
          val conv = emitMapHelper(helpers, "conv_${field.name}", field.type, "ctx", "${field.name}Ref")
          if (field.isNullable) {
            appendLine("    val ${field.name}: Map<$renderedKey, $renderedValue>? = if (${field.name}Tag == TAG_UNDEFINED || ${field.name}Tag == TAG_NULL) null else $conv")
          } else {
            appendLine("    val ${field.name}: Map<$renderedKey, $renderedValue> = $conv")
          }
          appendLine("    ${freeRef()}")
        }
        field.effectiveKtType == "kotlin.collections.List" -> {
          val renderedElement = renderedTypeName(field.arrayElementIrType)
          appendLine("    val ${field.name}Ref = HermesBridge_createHandle(ctx, jsValHandle, \"$propName\")")
          // Kotlin/JS ArrayList wraps the JS array in a name-mangled 'array_1' property.
          appendLine("    var ${field.name}ArrRef = ${field.name}Ref")
          appendLine("    val _tmpArrRef_${field.name} = HermesBridge_createHandle(ctx, ${field.name}Ref, \"array_1\")")
          appendLine("    if (HermesBridge_getValueTag(ctx, _tmpArrRef_${field.name}) != TAG_UNDEFINED) {")
          appendLine("        HermesBridge_freeHandle(ctx, ${field.name}Ref)")
          appendLine("        ${field.name}ArrRef = _tmpArrRef_${field.name}")
          appendLine("    }")
          val conv = emitListHelper(helpers, "conv_${field.name}", field.arrayElementIrType, "ctx", "${field.name}ArrRef")
          if (field.isNullable) {
            appendLine("    val ${field.name}: List<$renderedElement>? = if (HermesBridge_getValueTag(ctx, ${field.name}ArrRef) == TAG_UNDEFINED || HermesBridge_getValueTag(ctx, ${field.name}ArrRef) == TAG_NULL) null else $conv")
          } else {
            appendLine("    val ${field.name}: List<$renderedElement> = $conv")
          }
          appendLine("    HermesBridge_freeHandle(ctx, ${field.name}ArrRef)")
        }
        field.effectiveKtType in PRIMITIVE_ARRAY_ELEMENT_TYPE -> {
          val arrayType = field.effectiveKtType.substringAfterLast(".")
          appendLine("    val ${field.name}Ref = HermesBridge_createHandle(ctx, jsValHandle, \"$propName\")")
          val conv = emitPrimitiveArrayHelper(helpers, "conv_${field.name}", field.effectiveKtType, "ctx", "${field.name}Ref")
          if (field.isNullable) {
            appendLine("    val ${field.name}: $arrayType? = if (HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_UNDEFINED || HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_NULL) null else $conv")
          } else {
            appendLine("    val ${field.name}: $arrayType = $conv")
          }
          appendLine("    ${freeRef()}")
        }
        field.effectiveKtType == "kotlin.Array" -> {
          val renderedElement = renderedTypeName(field.arrayElementIrType)
          appendLine("    val ${field.name}Ref = HermesBridge_createHandle(ctx, jsValHandle, \"$propName\")")
          val conv = emitArrayHelper(helpers, "conv_${field.name}", field.arrayElementIrType, "ctx", "${field.name}Ref")
          if (field.isNullable) {
            appendLine("    val ${field.name}: Array<$renderedElement>? = if (HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_UNDEFINED || HermesBridge_getValueTag(ctx, ${field.name}Ref) == TAG_NULL) null else $conv")
          } else {
            appendLine("    val ${field.name}: Array<$renderedElement> = $conv")
          }
          appendLine("    ${freeRef()}")
        }
        // zipline has no function bridge: a function-typed field cannot cross. A nullable one
        // decodes to null; a non-nullable one is refused before generation (see above). The local
        // is intentionally untyped so it takes the constructor parameter's type.
        field.isObjectType && field.ktType.startsWith("kotlin.Function") -> {
          appendLine("    val ${field.name} = null")
        }
        field.isObjectType && field.ktType != "kotlin.Any" -> {
          val typeName = field.ktType.substringAfterLast(".")
          appendLine("    val ${field.name}Ref = HermesBridge_createHandle(ctx, jsValHandle, \"$propName\")")
          appendLine("    val ${field.name}DispPtr = HermesBridge_getBridgeDispatch(ctx, ${field.name}Ref)")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (${field.name}DispPtr == 0L) null else {")
            appendLine("        val ${field.name}DispatchFn = ${field.name}DispPtr.toCPointer<CFunction<(COpaquePointer?, Int) -> COpaquePointer?>>()!!")
            appendLine("        ${field.name}DispatchFn(ctx, ${field.name}Ref)?.asStableRef<Any>()?.get() as? $typeName")
            appendLine("    }")
          } else {
            appendLine("    if (${field.name}DispPtr == 0L) {")
            appendLine("        throw IllegalStateException(\"BRIDGE: non-nullable field ${field.name} ($typeName) — bridge_dispatch not found\")")
            appendLine("    }")
            appendLine("    val ${field.name}DispatchFn = ${field.name}DispPtr.toCPointer<CFunction<(COpaquePointer?, Int) -> COpaquePointer?>>()!!")
            appendLine("    val ${field.name} = ${field.name}DispatchFn(ctx, ${field.name}Ref)!!.asStableRef<Any>().get() as $typeName")
          }
          appendLine("    ${freeRef()}")
        }
        field.ktType == "kotlin.Any" -> {
          appendLine("    val ${field.name}Ref = HermesBridge_createHandle(ctx, jsValHandle, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name}: Any? = bridgeForAny(ctx, ${field.name}Ref)")
          } else {
            appendLine("    val ${field.name}: Any = bridgeForAny(ctx, ${field.name}Ref) as Any")
          }
          appendLine("    ${freeRef()}")
        }
        else -> {
          appendLine("    val ${field.name}Ref = HermesBridge_createHandle(ctx, jsValHandle, \"$propName\")")
          appendLine("    // TODO: unsupported type ${field.ktType} (isObjectType=${field.isObjectType}, isInline=${field.isInline})")
          appendLine("    val ${field.name} = ${field.name}Ref  // stub")
          appendLine("    ${freeRef()}")
        }
      }
    }

    // Constructor call
    val ctorFields = fields.filter { it.isConstructorParam }
    val bodyFields = fields.filter { !it.isConstructorParam }
    val className = clazz.name.asString()
    val qualifier = ((clazz.parent as? IrClass)?.name?.asString()?.plus(".")) ?: ""
    if (clazz.kind == ClassKind.ENUM_CLASS) {
      // Enum - the guest reports the ordinal (Kotlin/JS mangles the ordinal field's name, and
      // production builds drop it), and the index is range-checked: an unchecked index aborts.
      appendLine("    val ordinal = jsEnumOrdinal(ctx, jsValHandle)")
      appendLine("    val _obj = $qualifier$className.entries.getOrNull(ordinal)")
      appendLine("        ?: error(\"host bridge: ordinal \$ordinal is out of range for $qualifier$className\")")
    } else if (ctorFields.isNotEmpty()) {
      appendLine("    @Suppress(\"UNCHECKED_CAST\")")
      append("    val _obj = $qualifier$className(")
      append(ctorFields.joinToString(", ") { "${it.name} = ${it.name}" })
      appendLine(")")
    } else if (clazz.kind == ClassKind.OBJECT || clazz.isCompanion) {
      // Object/singleton — reference directly, not via constructor
      appendLine("    val _obj = $qualifier$className")
    } else {
      appendLine("    val _obj = $qualifier$className()")
    }
    // Body fields
    for (field in bodyFields) {
      appendLine("    _obj.${field.name} = ${field.name}")
    }
    appendLine("    return StableRef.create(_obj).asCPointer()")
    appendLine("}")
    appendLine()
    // Nested collection/array/map converters (may be empty).
    append(helpers)
    appendLine()
    appendLine("@OptIn(kotlin.ExperimentalStdlibApi::class)")
    appendLine("@kotlin.native.EagerInitialization")
    appendLine("private val _bridgeInit_${functionName} = run {")
    appendLine("    registerBridge(\"$fqn\", staticCFunction(::$functionName))")
    appendLine("    Unit")
    appendLine("}")
  }

  val fileName = "${fqn.replace(".", "_")}_bridge_native.kt"
  val file = File(outputDir, fileName)
  file.parentFile.mkdirs()
  file.writeText(source)
}

/**
 * Emits a helper function that converts a JS handle (pointing to a JS array) into a Kotlin
 * [List], and returns the expression that calls it.
 */
private fun emitListHelper(
  helpers: StringBuilder,
  name: String,
  elementType: IrType?,
  ctx: String,
  expr: String,
): String {
  val elementName = "${name}_element"
  val renderedElement = renderedTypeName(elementType)
  val pending = StringBuilder()
  // The guest drives the iteration: Kotlin/JS mangles the stdlib's backing fields (and drops
  // them in production builds), and the concrete list class depends on how the guest built it.
  helpers.appendLine("private fun $name(ctx: COpaquePointer?, jsArrHandle: Int): List<$renderedElement> = run {")
  helpers.appendLine("    @Suppress(\"UNCHECKED_CAST\")")
  helpers.appendLine("    val result = jsCollectionToKotlin(ctx, jsArrHandle, CollectionKind.LIST) { element ->")
  val elemConv = emitElementConversion(pending, elementName, elementType, ctx, "element")
  helpers.appendLine("        $elemConv")
  helpers.appendLine("    } as List<$renderedElement>")
  helpers.appendLine("    result")
  helpers.appendLine("}")
  helpers.appendLine()
  helpers.append(pending)
  return "$name(ctx, $expr)"
}

/** Emits a helper function that converts a JS handle (JS array) into a Kotlin Array<T>. */
private fun emitArrayHelper(
  helpers: StringBuilder,
  name: String,
  elementType: IrType?,
  ctx: String,
  expr: String,
): String {
  val elementName = "${name}_element"
  val renderedElement = renderedTypeName(elementType)
  val pending = StringBuilder()
  helpers.appendLine("private fun $name(ctx: COpaquePointer?, jsArrHandle: Int): Array<$renderedElement> = run {")
  helpers.appendLine("    val len = HermesBridge_getArrayLength(ctx, jsArrHandle)")
  helpers.appendLine("    val result = arrayOfNulls<$renderedElement>(len)")
  helpers.appendLine("    var i = 0")
  helpers.appendLine("    while (i < len) {")
  helpers.appendLine("        val elemRef = HermesBridge_createArrayElementHandle(ctx, jsArrHandle, i)")
  val elemConv = emitElementConversion(pending, elementName, elementType, ctx, "elemRef")
  helpers.appendLine("        result[i] = $elemConv")
  helpers.appendLine("        HermesBridge_freeHandle(ctx, elemRef)")
  helpers.appendLine("        i++")
  helpers.appendLine("    }")
  helpers.appendLine("    @Suppress(\"UNCHECKED_CAST\")")
  helpers.appendLine("    result as Array<$renderedElement>")
  helpers.appendLine("}")
  helpers.appendLine()
  helpers.append(pending)
  return "$name(ctx, $expr)"
}

/** Emits a helper function that converts a JS handle (JS Map) into a Kotlin Map<K, V>. */
private fun emitMapHelper(
  helpers: StringBuilder,
  name: String,
  type: IrType?,
  ctx: String,
  expr: String,
): String {
  val keyType = typeArgument(type, 0)
  val valueType = typeArgument(type, 1)
  val renderedKey = renderedTypeName(keyType)
  val renderedValue = renderedTypeName(valueType)
  val keyName = "${name}_key"
  val valueName = "${name}_value"
  val pending = StringBuilder()
  // The guest identifies the collection and drives the iteration (see jsMapToKotlin): a host-side
  // walk of a Kotlin/JS Map only works in development builds.
  helpers.appendLine("private fun $name(ctx: COpaquePointer?, jsMapHandle: Int): Map<$renderedKey, $renderedValue> = run {")
  helpers.appendLine("    @Suppress(\"UNCHECKED_CAST\")")
  helpers.appendLine("    val result = jsMapToKotlin(ctx, jsMapHandle, { key ->")
  val keyConv = emitElementConversion(pending, keyName, keyType, ctx, "key")
  helpers.appendLine("        $keyConv")
  helpers.appendLine("    }, { value ->")
  val valueConv = emitElementConversion(pending, valueName, valueType, ctx, "value")
  helpers.appendLine("        $valueConv")
  helpers.appendLine("    }) as Map<$renderedKey, $renderedValue>")
  helpers.appendLine("    result")
  helpers.appendLine("}")
  helpers.appendLine()
  helpers.append(pending)
  return "$name(ctx, $expr)"
}

/** Emits a helper function that converts a JS handle (JS array) into a Kotlin primitive array. */
private fun emitPrimitiveArrayHelper(
  helpers: StringBuilder,
  name: String,
  arrayKtType: String,
  ctx: String,
  expr: String,
): String {
  val kotlinArrayType = arrayKtType.substringAfterLast(".")
  val elementType = PRIMITIVE_ARRAY_ELEMENT_TYPE[arrayKtType]!!
  val elemConv = when (elementType) {
    "kotlin.Int" -> "HermesBridge_getValueDouble(ctx, elemRef).toInt()"
    "kotlin.Boolean" -> "HermesBridge_getValueBool(ctx, elemRef) != 0"
    "kotlin.Double" -> "HermesBridge_getValueDouble(ctx, elemRef)"
    "kotlin.Float" -> "HermesBridge_getValueDouble(ctx, elemRef).toFloat()"
    "kotlin.Char" -> "HermesBridge_getValueDouble(ctx, elemRef).toInt().toChar()"
    "kotlin.Short" -> "HermesBridge_getValueDouble(ctx, elemRef).toInt().toShort()"
    "kotlin.Byte" -> "HermesBridge_getValueDouble(ctx, elemRef).toInt().toByte()"
    "kotlin.Long" -> "JsNumberToLong(ctx, elemRef)"
    else -> error("unexpected primitive array element: $elementType")
  }
  helpers.appendLine("private fun $name(ctx: COpaquePointer?, jsArrHandle: Int): $kotlinArrayType = run {")
  helpers.appendLine("    val len = HermesBridge_getArrayLength(ctx, jsArrHandle)")
  helpers.appendLine("    val result = $kotlinArrayType(len)")
  helpers.appendLine("    var i = 0")
  helpers.appendLine("    while (i < len) {")
  helpers.appendLine("        val elemRef = HermesBridge_createArrayElementHandle(ctx, jsArrHandle, i)")
  helpers.appendLine("        result[i] = $elemConv")
  helpers.appendLine("        HermesBridge_freeHandle(ctx, elemRef)")
  helpers.appendLine("        i++")
  helpers.appendLine("    }")
  helpers.appendLine("    result")
  helpers.appendLine("}")
  helpers.appendLine()
  return "$name(ctx, $expr)"
}

/**
 * Emits the expression converting a JS value (via a handle in [expr]) of [type] into Kotlin.
 * Simple types convert inline; objects use bridge dispatch; inline value classes are wrapped;
 * collections recurse into helper functions emitted to [helpers].
 */
private fun emitElementConversion(
  helpers: StringBuilder,
  name: String,
  type: IrType?,
  ctx: String,
  expr: String,
): String {
  val ktType = effectiveClassFqn(type)
  val elemClass = (type as? IrSimpleType)?.getClass()
  val isInlineElem = elemClass != null && isInlineClass(elemClass)
  val underlying = if (isInlineElem) unwrapInlineUnderlying(elemClass) else null

  return when {
    ktType == "kotlin.String" -> "HermesBridge_getValueString(ctx, $expr)?.let { s -> s.toKStringFromUtf8()?.also { platform.posix.free(s) } } ?: \"\""
    isInlineElem && underlying == "kotlin.Int" -> "${ktType.substringAfterLast(".")}(HermesBridge_getValueDouble(ctx, $expr).toInt())"
    isInlineElem && underlying == "kotlin.Double" -> "${ktType.substringAfterLast(".")}(HermesBridge_getValueDouble(ctx, $expr))"
    isInlineElem && underlying == "kotlin.Long" -> "${ktType.substringAfterLast(".")}(JsNumberToLong(ctx, $expr))"
    isInlineElem && underlying == "kotlin.Float" -> "${ktType.substringAfterLast(".")}(HermesBridge_getValueDouble(ctx, $expr).toFloat())"
    ktType == "kotlin.Int" -> "HermesBridge_getValueDouble(ctx, $expr).toInt()"
    ktType == "kotlin.Long" -> "JsNumberToLong(ctx, $expr)"
    ktType == "kotlin.Float" -> "HermesBridge_getValueDouble(ctx, $expr).toFloat()"
    ktType == "kotlin.Double" -> "HermesBridge_getValueDouble(ctx, $expr)"
    ktType == "kotlin.Boolean" -> "(HermesBridge_getValueBool(ctx, $expr) != 0)"
    ktType == "kotlin.Any" -> "bridgeForAny(ctx, $expr) as Any"
    ktType == "kotlin.collections.List" -> emitListHelper(helpers, name, typeArgument(type, 0), ctx, expr)
    ktType == "kotlin.Array" -> emitArrayHelper(helpers, name, typeArgument(type, 0), ctx, expr)
    ktType in MAP_KOTLIN_TYPES -> emitMapHelper(helpers, name, type, ctx, expr)
    ktType in PRIMITIVE_ARRAY_ELEMENT_TYPE -> emitPrimitiveArrayHelper(helpers, name, ktType, ctx, expr)
    else -> {
      // Object element: use bridge dispatch.
      val typeName = ktType.substringAfterLast(".")
      val isNullableElem = (type as? IrSimpleType)?.isMarkedNullable() ?: false
      if (isNullableElem) {
        "(HermesBridge_getBridgeDispatch(ctx, $expr).toCPointer<CFunction<(COpaquePointer?, Int) -> COpaquePointer?>>()!!.invoke(ctx, $expr)?.asStableRef<Any>()?.get() as? $typeName)"
      } else {
        "(HermesBridge_getBridgeDispatch(ctx, $expr).toCPointer<CFunction<(COpaquePointer?, Int) -> COpaquePointer?>>()!!.invoke(ctx, $expr)!!.asStableRef<Any>().get() as $typeName)"
      }
    }
  }
}

/** Generate per-class native bridge files. Each file self-registers via @EagerInitialization. */
internal fun generateNativeBridges(
  outputDir: String,
  dispatchClasses: List<IrClass>,
  messageCollector: MessageCollector? = null,
) {
  for (clazz in dispatchClasses) {
    generateNativeBridgeFile(outputDir, clazz, messageCollector)
  }
}
