package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.name.FqName

// -- Kotlin/Native bridge code generation (iOS) --

/** Marker for Redwood's generated-code-only APIs; the opt-in is only needed when the bridged class uses them. */
private val REDWOOD_CODEGEN_API_FQN = FqName("app.cash.redwood.RedwoodCodegenApi")

// -- Recursive JS → Kotlin collection conversion (List/Array/Map, incl. nested) --

private val MAP_KOTLIN_TYPES = setOf(
  "kotlin.collections.Map", "kotlin.collections.MutableMap",
  "kotlin.collections.HashMap", "kotlin.collections.LinkedHashMap",
)

/** Primitive arrays supported by the generators (the native generator skips FloatArray fields entirely). */
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

/** The [index]-th type argument of [type], if any. */
internal fun typeArgument(type: IrType?, index: Int): IrType? =
  (type as? IrSimpleType)?.arguments?.getOrNull(index)
    ?.let { (it as? IrTypeProjection)?.type ?: (it as? IrType) }

/** Effective (erased / upper-bound / Any) class FQN of a type; mirrors FieldInfo.ktType. */
internal fun effectiveClassFqn(type: IrType?): String {
  if (type == null) return "kotlin.Any"
  val direct = type.classFqName?.asString()
  if (direct != null) return direct
  val typeParameter = (type as? IrSimpleType)?.classifier?.owner as? IrTypeParameter
  return typeParameter?.superTypes?.firstOrNull()?.classFqName?.asString() ?: "kotlin.Any"
}

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
  needsJsNumber: MutableSet<Unit>,
  needsJsLong: MutableSet<Unit>,
  needsMapHelper: MutableSet<Unit>,
) {
  val ktType = effectiveClassFqn(type)
  when {
    ktType == "kotlin.Any" -> needsBridgeForAny.add(Unit)
    ktType == "kotlin.Double" || ktType == "kotlin.Float" -> needsJsNumber.add(Unit)
    ktType == "kotlin.Long" -> needsJsLong.add(Unit)
    ktType in MAP_KOTLIN_TYPES -> {
      needsMapHelper.add(Unit)
      // Map iteration converts keys/values through the converters below.
      (type as? IrSimpleType)?.arguments?.forEach { arg ->
        collectRuntimeImports((arg as? IrTypeProjection)?.type ?: (arg as? IrType), needsBridgeForAny, needsJsNumber, needsJsLong, needsMapHelper)
      }
    }
    ktType == "kotlin.collections.List" || ktType == "kotlin.Array" -> {
      needsBridgeForAny.add(Unit)
      (type as? IrSimpleType)?.arguments?.forEach { arg ->
        collectRuntimeImports((arg as? IrTypeProjection)?.type ?: (arg as? IrType), needsBridgeForAny, needsJsNumber, needsJsLong, needsMapHelper)
      }
    }
    ktType in PRIMITIVE_ARRAY_ELEMENT_TYPE -> {
      // Elements are read with JsValueGet*/JsNumberTo* directly in the array helper.
      val elem = PRIMITIVE_ARRAY_ELEMENT_TYPE[ktType]
      if (elem == "kotlin.Long") needsJsLong.add(Unit)
      if (elem == "kotlin.Double" || elem == "kotlin.Float") needsJsNumber.add(Unit)
    }
    else -> needsBridgeForAny.add(Unit)
  }
}

/**
 * Returns the expression converting a JS value of [type] into Kotlin, appending any nested
 * helper functions to [helpers]. Simple types convert inline; collections recurse into helpers.
 */
private fun jsValueToKotlinExpression(
  helpers: StringBuilder,
  name: String,
  type: IrType?,
  ctx: String,
  expr: String,
): String {
  val ktType = effectiveClassFqn(type)
  return when (ktType) {
    "kotlin.Int" -> "(bridgeForAny($ctx, $expr) as Double).toInt()"
    "kotlin.Float" -> "(bridgeForAny($ctx, $expr) as Double).toFloat()"
    "kotlin.Double" -> "bridgeForAny($ctx, $expr) as Double"
    "kotlin.Boolean" -> "bridgeForAny($ctx, $expr) as Boolean"
    "kotlin.String" -> "bridgeForAny($ctx, $expr) as String"
    "kotlin.Long" -> "JsNumberToLong($ctx, $expr)"
    "kotlin.Any" -> "bridgeForAny($ctx, $expr) as Any"
    "kotlin.collections.List" ->
      emitListHelper(helpers, name, typeArgument(type, 0), ctx, expr)
    "kotlin.Array" ->
      emitArrayHelper(helpers, name, typeArgument(type, 0), ctx, expr)
    in MAP_KOTLIN_TYPES ->
      emitMapHelper(helpers, name, type, ctx, expr)
    in PRIMITIVE_ARRAY_ELEMENT_TYPE ->
      emitPrimitiveArrayHelper(helpers, name, ktType, ctx, expr)
    else -> "bridgeForAny($ctx, $expr) as ${ktType.substringAfterLast(".")}"
  }
}

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
  helpers.appendLine("private fun $name(ctx: CPointer<JSContext>, jsArr: CValue<JSValue>): List<$renderedElement> = run {")
  // Kotlin/JS ArrayList wraps the JS array in a name-mangled 'array_1' property; unwrap it.
  // The input is borrowed: only the lookup result is freed here; the caller frees the input.
  helpers.appendLine("    var arr = jsArr")
  helpers.appendLine("    val _tmpArr = JS_GetPropertyStr(ctx, jsArr, \"array_1\")")
  helpers.appendLine("    if (JS_IsUndefined(_tmpArr) == 0) arr = _tmpArr")
  helpers.appendLine("    val lenVal = JS_GetPropertyStr(ctx, arr, \"length\")")
  helpers.appendLine("    val len = JsValueGetInt(lenVal)")
  helpers.appendLine("    JS_FreeValue(ctx, lenVal)")
  helpers.appendLine("    val result = mutableListOf<$renderedElement>()")
  helpers.appendLine("    var i = 0")
  helpers.appendLine("    while (i < len.toInt()) {")
  helpers.appendLine("        val elem = JS_GetPropertyUint32(ctx, arr, i.toUInt())")
  val elemConv = jsValueToKotlinExpression(pending, elementName, elementType, ctx, "elem")
  helpers.appendLine("        result.add($elemConv)")
  helpers.appendLine("        JS_FreeValue(ctx, elem)")
  helpers.appendLine("        i++")
  helpers.appendLine("    }")
  helpers.appendLine("    JS_FreeValue(ctx, _tmpArr)")
  helpers.appendLine("    result")
  helpers.appendLine("}")
  helpers.appendLine()
  helpers.append(pending)
  return "$name(ctx, $expr)"
}

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
  helpers.appendLine("private fun $name(ctx: CPointer<JSContext>, jsArr: CValue<JSValue>): Array<$renderedElement> = run {")
  helpers.appendLine("    val lenVal = JS_GetPropertyStr(ctx, jsArr, \"length\")")
  helpers.appendLine("    val len = JsValueGetInt(lenVal)")
  helpers.appendLine("    JS_FreeValue(ctx, lenVal)")
  helpers.appendLine("    val result = arrayOfNulls<$renderedElement>(len) as Array<$renderedElement>")
  helpers.appendLine("    var i = 0")
  helpers.appendLine("    while (i < len.toInt()) {")
  helpers.appendLine("        val elem = JS_GetPropertyUint32(ctx, jsArr, i.toUInt())")
  val elemConv = jsValueToKotlinExpression(pending, elementName, elementType, ctx, "elem")
  helpers.appendLine("        result[i] = $elemConv")
  helpers.appendLine("        JS_FreeValue(ctx, elem)")
  helpers.appendLine("        i++")
  helpers.appendLine("    }")
  helpers.appendLine("    result")
  helpers.appendLine("}")
  helpers.appendLine()
  helpers.append(pending)
  return "$name(ctx, $expr)"
}

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
    "kotlin.Int" -> "JsValueGetInt(elem)"
    "kotlin.Boolean" -> "JsValueGetBool(elem) != 0"
    "kotlin.Double" -> "JsNumberToDouble(elem)"
    "kotlin.Float" -> "JsNumberToDouble(elem).toFloat()"
    "kotlin.Char" -> "JsValueGetInt(elem).toChar()"
    "kotlin.Short" -> "JsValueGetInt(elem).toShort()"
    "kotlin.Byte" -> "JsValueGetInt(elem).toByte()"
    "kotlin.Long" -> "JsNumberToLong(ctx, elem)"
    else -> error("unexpected primitive array element: $elementType")
  }
  helpers.appendLine("private fun $name(ctx: CPointer<JSContext>, jsArr: CValue<JSValue>): $kotlinArrayType = run {")
  helpers.appendLine("    val lenVal = JS_GetPropertyStr(ctx, jsArr, \"length\")")
  helpers.appendLine("    val len = JsValueGetInt(lenVal)")
  helpers.appendLine("    JS_FreeValue(ctx, lenVal)")
  helpers.appendLine("    val result = $kotlinArrayType(len)")
  helpers.appendLine("    var i = 0")
  helpers.appendLine("    while (i < len.toInt()) {")
  helpers.appendLine("        val elem = JS_GetPropertyUint32(ctx, jsArr, i.toUInt())")
  helpers.appendLine("        result[i] = $elemConv")
  helpers.appendLine("        JS_FreeValue(ctx, elem)")
  helpers.appendLine("        i++")
  helpers.appendLine("    }")
  helpers.appendLine("    result")
  helpers.appendLine("}")
  helpers.appendLine()
  return "$name(ctx, $expr)"
}

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
  val keyConv = jsValueToKotlinExpression(pending, keyName, keyType, "ctx", "k")
  val valueConv = jsValueToKotlinExpression(pending, valueName, valueType, "ctx", "v")
  helpers.appendLine("private fun $name(ctx: CPointer<JSContext>, jsMap: CValue<JSValue>): Map<$renderedKey, $renderedValue> = jsMapToKotlin(ctx, jsMap,")
  helpers.appendLine("  { k -> $keyConv },")
  helpers.appendLine("  { v -> $valueConv },")
  helpers.appendLine(") as Map<$renderedKey, $renderedValue>")
  helpers.appendLine()
  helpers.append(pending)
  return "$name(ctx, $expr)"
}

internal fun generateNativeBridgeFile(outputDir: String, clazz: IrClass) {
  val fqn = clazz.fqNameWhenAvailable?.asString() ?: return
  val functionName = "${clazz.name.asString()}_toKotlin"
  val fields = extractFields(clazz)

  // The generated code constructs the bridged class (and, for nested classes, its enclosing
  // class). If any of those is marked @RedwoodCodegenApi, the file must opt in — but only
  // then, so zipline-only builds never reference the Redwood annotation.
  val usesRedwoodCodegenApi =
    clazz.hasAnnotation(REDWOOD_CODEGEN_API_FQN) ||
      (clazz.parent as? IrClass)?.hasAnnotation(REDWOOD_CODEGEN_API_FQN) == true

  // Skip classes with unsupported field types (Function* object types).
  val hasUnsupported = fields.any {
    (it.isObjectType && it.ktType.startsWith("kotlin.Function"))
  }
  if (hasUnsupported) return

  val source = buildString {
    appendLine("// GENERATED FILE. DO NOT MODIFY MANUALLY.")
    appendLine()
    appendLine("@file:Suppress(\"UNUSED_PARAMETER\", \"unused\", \"INVISIBLE_MEMBER\", \"INVISIBLE_REFERENCE\", \"UNCHECKED_CAST\")")
    if (usesRedwoodCodegenApi) {
      appendLine("@file:OptIn(app.cash.redwood.RedwoodCodegenApi::class, kotlinx.cinterop.ExperimentalForeignApi::class)")
    } else {
      appendLine("@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)")
    }
    appendLine("package generated_bridges")
    appendLine()
    appendLine("import kotlinx.cinterop.*")
    appendLine("import app.cash.zipline.quickjs.*")
    appendLine("import app.cash.zipline.registerBridge")
    val needsBridgeForAny = mutableSetOf<Unit>()
    val needsJsNumber = mutableSetOf<Unit>()
    val needsJsLong = mutableSetOf<Unit>()
    val needsMapHelper = mutableSetOf<Unit>()
    for (field in fields) {
      collectRuntimeImports(field.type, needsBridgeForAny, needsJsNumber, needsJsLong, needsMapHelper)
      if (field.isInline && field.underlyingKtType == "kotlin.Long") needsJsLong.add(Unit)
      if (field.isInline && field.underlyingKtType in setOf("kotlin.Double", "kotlin.Float")) needsJsNumber.add(Unit)
      // Value classes not detected as inline still read Float/Double via JsNumberToDouble.
      if (!field.isInline && field.wrapperKtType != null && field.ktType in setOf("kotlin.Double", "kotlin.Float")) needsJsNumber.add(Unit)
    }
    if (needsBridgeForAny.isNotEmpty()) {
      appendLine("import app.cash.zipline.bridgeForAny")
    }
    if (needsJsNumber.isNotEmpty()) {
      appendLine("import app.cash.zipline.JsNumberToDouble")
    }
    if (needsJsLong.isNotEmpty()) {
      appendLine("import app.cash.zipline.JsNumberToLong")
    }
    if (needsMapHelper.isNotEmpty()) {
      appendLine("import app.cash.zipline.jsMapToKotlin")
    }
    // Import the target class and any inline wrapper types + object types
    // Import parent class for nested classes (e.g., Modifier for Modifier.Companion)
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
      // Recursively import every class referenced by collection/array/map field types.
      collectTypeImports(field.type, imports)
    }
    imports.filter { it.isNotEmpty() }.sorted().forEach { appendLine(it) }
    appendLine()
    appendLine("public fun $functionName(")
    appendLine("  ctx: CPointer<JSContext>,")
    appendLine("  jsVal: CValue<JSValue>,")
    appendLine("): Any {")
    // Generate field reads
    val helpers = StringBuilder()
    for (field in fields) {
      val propName = field.jsPropertyName

      when {
        field.isInline && field.underlyingKtType == "kotlin.Int" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else ${inlineWrapExpression(field, "JsValueGetInt(${field.name}Raw)")}")
          } else {
            appendLine("    val ${field.name}Val = JsValueGetInt(${field.name}Raw)")
            appendLine("    val ${field.name} = ${inlineWrapExpression(field, "${field.name}Val")}")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.isInline && field.underlyingKtType == "kotlin.Float" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else ${inlineWrapExpression(field, "JsNumberToDouble(${field.name}Raw).toFloat()")}")
          } else {
            appendLine("    val ${field.name}Val = JsNumberToDouble(${field.name}Raw).toFloat()")
            appendLine("    val ${field.name} = ${inlineWrapExpression(field, "${field.name}Val")}")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.isInline && field.underlyingKtType == "kotlin.Double" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else ${inlineWrapExpression(field, "JsNumberToDouble(${field.name}Raw)")}")
          } else {
            appendLine("    val ${field.name}Val = JsNumberToDouble(${field.name}Raw)")
            appendLine("    val ${field.name} = ${inlineWrapExpression(field, "${field.name}Val")}")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.isInline && field.underlyingKtType == "kotlin.Long" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else ${inlineWrapExpression(field, "JsNumberToLong(ctx, ${field.name}Raw)")}")
          } else {
            appendLine("    val ${field.name}Val = JsNumberToLong(ctx, ${field.name}Raw)")
            appendLine("    val ${field.name} = ${inlineWrapExpression(field, "${field.name}Val")}")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        !field.isInline && field.wrapperKtType.let { it != null } -> {
          val wrapperName = field.wrapperKtType!!.substringAfterLast(".")
          // Value class not detected as inline — treat as effectiveKtType + wrap
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          when (field.ktType) {
            "kotlin.Double" -> {
              appendLine("    val ${field.name}Val = JsNumberToDouble(${field.name}Raw).toFloat()")
            }
            "kotlin.Float" -> {
              appendLine("    val ${field.name}Val = JsNumberToDouble(${field.name}Raw).toFloat()")
            }
            else -> {
              appendLine("    // TODO: unsupported wrapper effective type ${field.ktType}")
              appendLine("    val ${field.name}Val = ${field.name}Raw  // stub")
            }
          }
          appendLine("    val ${field.name} = $wrapperName(${field.name}Val)")
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.ktType == "kotlin.Int" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else JsValueGetInt(${field.name}Raw)")
          } else {
            appendLine("    val ${field.name} = JsValueGetInt(${field.name}Raw)")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.ktType == "kotlin.Boolean" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else JsValueGetBool(${field.name}Raw) != 0")
          } else {
            appendLine("    val ${field.name} = JsValueGetBool(${field.name}Raw) != 0")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.ktType == "kotlin.Double" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else JsNumberToDouble(${field.name}Raw)")
          } else {
            appendLine("    val ${field.name} = JsNumberToDouble(${field.name}Raw)")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.ktType == "kotlin.Float" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else JsNumberToDouble(${field.name}Raw).toFloat()")
          } else {
            appendLine("    val ${field.name} = JsNumberToDouble(${field.name}Raw).toFloat()")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.ktType == "kotlin.Long" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else JsNumberToLong(ctx, ${field.name}Raw)")
          } else {
            appendLine("    val ${field.name} = JsNumberToLong(ctx, ${field.name}Raw)")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.ktType == "kotlin.String" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            // Guard JS null/undefined BEFORE JS_ToCString: String(null) is "null", not null.
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else { val s = JS_ToCString(ctx, ${field.name}Raw); s?.toKStringFromUtf8()?.also { JS_FreeCString(ctx, s) } }")
          } else {
            appendLine("    val ${field.name} = JS_ToCString(ctx, ${field.name}Raw)?.toKStringFromUtf8() ?: \"\"")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        field.isObjectType && field.ktType != "kotlin.collections.List" &&
          field.effectiveKtType !in MAP_KOTLIN_TYPES -> {
          val typeName = field.ktType.substringAfterLast(".")
          val castName = if (typeName == "List") "Any" else typeName
          appendLine("    val ${field.name}Ref = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name} = if (JS_IsUndefined(${field.name}Ref) != 0 || JS_IsNull(${field.name}Ref) != 0) null else {")
            appendLine("        val dispatch = JS_GetPropertyStr(ctx, ${field.name}Ref, \"bridge_dispatch\")")
            appendLine("        val dispatchFn = JsValueGetFloat64(dispatch).toRawBits().toCPointer<UByteVar>()!!.asStableRef<(CPointer<JSContext>, CValue<JSValue>) -> Any>()")
            appendLine("        val result = dispatchFn.get()(ctx, ${field.name}Ref) as $castName")
            appendLine("        JS_FreeValue(ctx, dispatch)")
            appendLine("        result")
            appendLine("    }")
          } else {
            appendLine("    val ${field.name}Dispatch = JS_GetPropertyStr(ctx, ${field.name}Ref, \"bridge_dispatch\")")
            appendLine("    val ${field.name}DispatchFn = JsValueGetFloat64(${field.name}Dispatch).toRawBits().toCPointer<UByteVar>()!!.asStableRef<(CPointer<JSContext>, CValue<JSValue>) -> Any>()")
            appendLine("    val ${field.name} = ${field.name}DispatchFn.get()(ctx, ${field.name}Ref) as $castName")
            appendLine("    JS_FreeValue(ctx, ${field.name}Dispatch)")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Ref)")
        }
        field.ktType == "kotlin.collections.List" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          // Kotlin/JS ArrayList wraps the JS array in a name-mangled 'array_1' property.
          appendLine("    var ${field.name}Arr = ${field.name}Raw")
          appendLine("    var _tmpArr_${field.name} = JS_GetPropertyStr(ctx, ${field.name}Raw, \"array_1\")")
          appendLine("    if (JS_IsUndefined(_tmpArr_${field.name}) == 0) {")
          appendLine("        JS_FreeValue(ctx, ${field.name}Raw)")
          appendLine("        ${field.name}Arr = _tmpArr_${field.name}")
          appendLine("    }")
          val renderedElement = renderedTypeName(field.arrayElementIrType)
          val conv = emitListHelper(helpers, "conv_${field.name}", field.arrayElementIrType, "ctx", "${field.name}Arr")
          if (field.isNullable) {
            appendLine("    val ${field.name}: List<$renderedElement>? = if (JS_IsUndefined(${field.name}Arr) != 0 || JS_IsNull(${field.name}Arr) != 0) null else {")
            appendLine("        val result = $conv")
            appendLine("        JS_FreeValue(ctx, ${field.name}Arr)")
            appendLine("        result")
            appendLine("    }")
          } else {
            appendLine("    val ${field.name}: List<$renderedElement> = $conv")
            appendLine("    JS_FreeValue(ctx, ${field.name}Arr)")
          }
        }
        field.effectiveKtType in PRIMITIVE_ARRAY_ELEMENT_TYPE -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          val arrayType = field.effectiveKtType.substringAfterLast(".")
          val conv = emitPrimitiveArrayHelper(helpers, "conv_${field.name}", field.effectiveKtType, "ctx", "${field.name}Raw")
          if (field.isNullable) {
            appendLine("    val ${field.name}: $arrayType? = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else {")
            appendLine("        val result = $conv")
            appendLine("        JS_FreeValue(ctx, ${field.name}Raw)")
            appendLine("        result")
            appendLine("    }")
          } else {
            appendLine("    val ${field.name}: $arrayType = $conv")
            appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
          }
        }
        field.effectiveKtType == "kotlin.Array" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          val renderedElement = renderedTypeName(field.arrayElementIrType)
          val conv = emitArrayHelper(helpers, "conv_${field.name}", field.arrayElementIrType, "ctx", "${field.name}Raw")
          if (field.isNullable) {
            appendLine("    val ${field.name}: Array<$renderedElement>? = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else {")
            appendLine("        val result = $conv")
            appendLine("        JS_FreeValue(ctx, ${field.name}Raw)")
            appendLine("        result")
            appendLine("    }")
          } else {
            appendLine("    val ${field.name}: Array<$renderedElement> = $conv")
            appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
          }
        }
        field.effectiveKtType in MAP_KOTLIN_TYPES -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          val renderedKey = renderedTypeName(typeArgument(field.type, 0))
          val renderedValue = renderedTypeName(typeArgument(field.type, 1))
          val conv = emitMapHelper(helpers, "conv_${field.name}", field.type, "ctx", "${field.name}Raw")
          if (field.isNullable) {
            appendLine("    val ${field.name}: Map<$renderedKey, $renderedValue>? = if (JS_IsUndefined(${field.name}Raw) != 0 || JS_IsNull(${field.name}Raw) != 0) null else {")
            appendLine("        val result = $conv")
            appendLine("        JS_FreeValue(ctx, ${field.name}Raw)")
            appendLine("        result")
            appendLine("    }")
          } else {
            appendLine("    val ${field.name}: Map<$renderedKey, $renderedValue> = $conv")
            appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
          }
        }
        field.ktType == "kotlin.Any" -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          if (field.isNullable) {
            appendLine("    val ${field.name}: Any? = bridgeForAny(ctx, ${field.name}Raw)")
          } else {
            appendLine("    val ${field.name}: Any = bridgeForAny(ctx, ${field.name}Raw)!!")
          }
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
        else -> {
          appendLine("    val ${field.name}Raw = JS_GetPropertyStr(ctx, jsVal, \"$propName\")")
          appendLine("    // TODO: unsupported type ${field.ktType} (isObjectType=${field.isObjectType}, isInline=${field.isInline})")
          appendLine("    val ${field.name} = ${field.name}Raw  // stub")
          appendLine("    JS_FreeValue(ctx, ${field.name}Raw)")
        }
      }
    }

    // Constructor call
    val ctorFields = fields.filter { it.isConstructorParam }
    val bodyFields = fields.filter { !it.isConstructorParam }
    val className = clazz.name.asString()
    val qualifier = ((clazz.parent as? IrClass)?.name?.asString()?.plus(".")) ?: ""
    if (clazz.kind == ClassKind.ENUM_CLASS) {
      // Enum — read ordinal, return entries[ordinal]
      appendLine("    val ordinalRaw = JS_GetPropertyStr(ctx, jsVal, \"ordinal_1\")")
      appendLine("    val ordinal = JsValueGetInt(ordinalRaw)")
      appendLine("    JS_FreeValue(ctx, ordinalRaw)")
      appendLine("    val _obj = $qualifier$className.entries[ordinal]")
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
    appendLine("    return _obj")
    appendLine("}")
    appendLine()
    // Nested collection/array/map converters (may be empty).
    append(helpers)
    appendLine()
    appendLine("@OptIn(kotlin.ExperimentalStdlibApi::class)")
    appendLine("@kotlin.native.EagerInitialization")
    appendLine("private val _bridgeInit_${functionName} = run {")
    appendLine("    registerBridge(\"$fqn\", ::$functionName)")
    appendLine("    Unit")
    appendLine("}")
  }

  val fileName = "${fqn.replace(".", "_")}_bridge_native.kt"
  val file = java.io.File(outputDir, fileName)
  file.parentFile.mkdirs()
  file.writeText(source)
}

/** Generate per-class native bridge files. Each file self-registers via @EagerInitialization. */
internal fun generateNativeBridges(outputDir: String, dispatchClasses: List<IrClass>) {
  for (clazz in dispatchClasses) {
    generateNativeBridgeFile(outputDir, clazz)
  }
}
