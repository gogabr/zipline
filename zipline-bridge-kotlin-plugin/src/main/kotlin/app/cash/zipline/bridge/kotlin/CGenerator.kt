package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrTypeProjection

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.name.FqName
import java.io.File

// -- C file naming and JNI class name helpers --

internal const val KEEP_NAMES_FILE = "bridge-keep-names.txt"

internal fun cFunctionPrefix(fqName: FqName): String =
  fqName.asString().replace(".", "_")

internal fun cFileName(fqName: FqName): String =
  cFunctionPrefix(fqName) + ".c"

internal fun buildJniClassName(irClass: IrClass): String {
  val fqName = irClass.fqNameWhenAvailable?.asString() ?: return irClass.name.asString()
  val segments = fqName.split('.')

  var classDepth = 1
  var parent: IrDeclarationParent? = irClass.parent
  while (parent is IrClass) {
    classDepth++
    parent = parent.parent
  }

  val packageSegments = segments.dropLast(classDepth)
  val classSegments = segments.takeLast(classDepth)

  val packagePart = packageSegments.joinToString("/")
  val classPart = classSegments.joinToString("$")

  return if (packagePart.isEmpty()) classPart else "$packagePart/$classPart"
}

// -- C/JNI bridge code generation (Android .so) --

internal fun generateBridgeFile(
  outputDir: String,
  annotatedClass: IrClass,
  js2Host: Boolean,
  host2Js: Boolean,
) {
  val fqName = annotatedClass.fqNameWhenAvailable ?: return
  val functionPrefix = cFunctionPrefix(fqName)

  val fields = extractFields(annotatedClass, includeValBodyFields = true)
  val constructorFields = fields.filter { it.isConstructorParam }
  val bodyFields = fields.filter { !it.isConstructorParam }

  val targetFqn = resolveTargetFqn(annotatedClass)
  val jniClassName = targetFqn ?: buildJniClassName(annotatedClass)
  val jsClassName = fqName.asString()

  // The host2js JNI impl is emitted for non-value classes only (JVM value classes get no
  // convertToJs member, matching step 4); classes with kotlin.Function*-typed fields skip
  // host2js generation entirely (same hasUnsupported rule as generateNativeBridgeFile).
  val host2JsImpl = host2Js && !isInlineClass(annotatedClass) &&
    !fields.any { it.isObjectType && it.ktType.startsWith("kotlin.Function") }
  val host2JsFields = if (host2JsImpl && annotatedClass.kind != ClassKind.ENUM_CLASS) fields else emptyList()
  val host2JsUnboxFields = if (host2JsImpl) fields.filter { it.isInline && it.isNullable } else emptyList()
  // Prototype lookup key: identical to the JS-side module-load registration key.
  val protoFqn = targetFqn ?: fqName.asString()
  val host2JsJniFunctionName = if (host2JsImpl) {
    "Java_" + jniClassName.replace("/", "_").replace("$", "_00024") + "_convertToJs"
  } else null

  val nullablePrimitiveFields = fields.filter { it.isNullable && isKnownType(it.ktType) && isJniPrimitive(it.ktType) }
  val hasAnyField = fields.any { it.ktType == "kotlin.Any" }
  val hasCollectionField = fields.any { it.ktType in kotlinToJvmClass }
  val isObject = annotatedClass.kind == ClassKind.OBJECT
  val isCompanion = isObject && annotatedClass.isCompanion
  val isEnum = annotatedClass.kind == ClassKind.ENUM_CLASS
  val constructorSig = if (isObject || constructorFields.isEmpty()) "()V"
    else "(" + constructorFields.joinToString("") { it.jniTypeChar } + ")V"
  val instanceSig = if (isObject) "L${jniClassName.replace(".", "/")};" else ""

  val cSource = buildString {
    appendLine("// GENERATED FILE. DO NOT MODIFY MANUALLY.")
    appendLine()
    appendLine("#include <jni.h>")
    appendLine("#include \"quickjs/quickjs.h\"")
    appendLine("#include \"bridge_dispatch.h\"")
    appendLine("#ifdef __ANDROID__")
    appendLine("#include <android/log.h>")
    appendLine("#endif")
    appendLine()

    if (js2Host) {
      // Extern declarations for nullable inline class field helpers.
      val nullableInlineFields = fields.filter { it.isInline && it.isNullable && !isKnownType(it.ktType) }
      if (nullableInlineFields.isNotEmpty()) {
        appendLine("// Inline class _fromValue helpers (used for nullable inline class fields)")
        for (f in nullableInlineFields.distinctBy { it.ktType }) {
          val inlinePrefix = cFunctionPrefix(FqName(f.ktType))
          appendLine("extern void ${inlinePrefix}_init(JNIEnv *env);")
          appendLine("extern jobject ${inlinePrefix}_fromValue(JNIEnv *env, JSContext *ctx, JSValue jsVal);")
        }
        appendLine()
      }
    }

    // -- cached JNI references (initialized once by _init, used by _toJavaObject) --
    appendLine("static jclass _cls = NULL;")
    if (js2Host) {
      if (isEnum) {
        appendLine("static jmethodID _valuesMethod = NULL;")
      } else if (!isObject) {
        appendLine("static jmethodID _ctor = NULL;")
      }
      if (isCompanion) {
        appendLine("static jclass _outerCls = NULL;")
        appendLine("static jfieldID _companionField = NULL;")
      } else if (isObject) {
        appendLine("static jfieldID _instField = NULL;")
      }
      for (f in nullablePrimitiveFields) {
        appendLine("static jclass _boxed_${f.name} = NULL;")
        appendLine("static jmethodID _boxedCtor_${f.name} = NULL;")
      }
      if (!isEnum) {
        for (f in bodyFields) {
          appendLine("static jfieldID _fld_${f.name} = NULL;")
        }
      }
      if (hasAnyField || hasCollectionField) {
        appendLine("// Boxed type refs for Any? value dispatch")
        appendLine("static jclass _any_boxed_Integer_cls = NULL;")
        appendLine("static jmethodID _any_boxed_Integer_ctor = NULL;")
        appendLine("static jclass _any_boxed_Double_cls = NULL;")
        appendLine("static jmethodID _any_boxed_Double_ctor = NULL;")
        appendLine("static jclass _any_boxed_Boolean_cls = NULL;")
        appendLine("static jmethodID _any_boxed_Boolean_ctor = NULL;")
        appendLine("static jclass _any_boxed_Float_cls = NULL;")
        appendLine("static jmethodID _any_boxed_Float_ctor = NULL;")
      }
    }
    if (host2JsImpl) {
      // Host2JS: cache a field ID for every field (constructor params included), unbox-impl for
      // nullable inline fields, and the enum ordinal method.
      for (f in host2JsFields) {
        appendLine("static jfieldID _h2j_fld_${f.name} = NULL;")
      }
      for (f in host2JsUnboxFields) {
        appendLine("static jmethodID _h2j_unbox_${f.name} = NULL;")
      }
      if (annotatedClass.kind == ClassKind.ENUM_CLASS) {
        appendLine("static jmethodID _h2j_ordinal = NULL;")
      }
    }
    appendLine()

    // -- _init: cache JNI references (called once from main thread via init_all) --
    appendLine("void ${functionPrefix}_init(JNIEnv *env) {")
    appendLine("    if (_cls != NULL) return;")
    appendLine("    jclass local = (*env)->FindClass(env, \"$jniClassName\");")
    appendLine("    if ((*env)->ExceptionCheck(env)) {")
    appendLine("#ifdef __ANDROID__")
    appendLine("        __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"_init FAILED: FindClass for $jniClassName\");")
    appendLine("#endif")
    appendLine("        // Leave the pending exception pending: it propagates to the JVM and crashes.")
    appendLine("        return;")
    appendLine("    }")
    appendLine("    _cls = (*env)->NewGlobalRef(env, local);")
    if (js2Host) {
      if (isEnum) {
        appendLine("    _valuesMethod = (*env)->GetStaticMethodID(env, _cls, \"values\", \"()[L$jniClassName;\");")
        appendLine("    if ((*env)->ExceptionCheck(env)) {")
        appendLine("        // Let the pending NoSuchMethodError propagate instead of clearing it.")
        appendLine("        _valuesMethod = NULL;")
        appendLine("    }")
      } else if (!isObject) {
        appendLine("    _ctor = (*env)->GetMethodID(env, _cls, \"<init>\", \"$constructorSig\");")
        appendLine("    if ((*env)->ExceptionCheck(env)) {")
        appendLine("        // Let the pending NoSuchMethodError propagate instead of clearing it.")
        appendLine("        _ctor = NULL;")
        appendLine("    }")
      }
      if (isCompanion) {
        val outerJni = buildJniClassName(annotatedClass.parent as IrClass)
        appendLine("    {")
        appendLine("        jclass outerLocal = (*env)->FindClass(env, \"$outerJni\");")
        appendLine("        if ((*env)->ExceptionCheck(env)) { /* pending exception propagates */ return; }")
        appendLine("        _outerCls = (*env)->NewGlobalRef(env, outerLocal);")
        appendLine("        _companionField = (*env)->GetStaticFieldID(env, _outerCls, \"Companion\", \"$instanceSig\");")
        appendLine("    }")
      } else if (isObject) {
        appendLine("    _instField = (*env)->GetStaticFieldID(env, _cls, \"INSTANCE\", \"$instanceSig\");")
      }
      for (f in nullablePrimitiveFields) {
        val info = boxedPrimitiveInfo[f.ktType]!!
        appendLine("    {")
        appendLine("        jclass boxed = (*env)->FindClass(env, \"${info.wrapperClass}\");")
        appendLine("        _boxed_${f.name} = (*env)->NewGlobalRef(env, boxed);")
        appendLine("        _boxedCtor_${f.name} = (*env)->GetMethodID(env, _boxed_${f.name}, \"<init>\", \"${info.ctorSig}\");")
        appendLine("    }")
      }
      if (hasAnyField || hasCollectionField) {
        appendLine("    // Init boxed type refs for Any? value dispatch")
        appendLine("    if (_any_boxed_Integer_cls == NULL) {")
        appendLine("        jclass intLocal = (*env)->FindClass(env, \"java/lang/Integer\");")
        appendLine("        _any_boxed_Integer_cls = (*env)->NewGlobalRef(env, intLocal);")
        appendLine("        _any_boxed_Integer_ctor = (*env)->GetMethodID(env, _any_boxed_Integer_cls, \"<init>\", \"(I)V\");")
        appendLine("    }")
        appendLine("    if (_any_boxed_Double_cls == NULL) {")
        appendLine("        jclass dblLocal = (*env)->FindClass(env, \"java/lang/Double\");")
        appendLine("        _any_boxed_Double_cls = (*env)->NewGlobalRef(env, dblLocal);")
        appendLine("        _any_boxed_Double_ctor = (*env)->GetMethodID(env, _any_boxed_Double_cls, \"<init>\", \"(D)V\");")
        appendLine("    }")
        appendLine("    if (_any_boxed_Boolean_cls == NULL) {")
        appendLine("        jclass boolLocal = (*env)->FindClass(env, \"java/lang/Boolean\");")
        appendLine("        _any_boxed_Boolean_cls = (*env)->NewGlobalRef(env, boolLocal);")
        appendLine("        _any_boxed_Boolean_ctor = (*env)->GetMethodID(env, _any_boxed_Boolean_cls, \"<init>\", \"(Z)V\");")
        appendLine("    }")
        appendLine("    if (_any_boxed_Float_cls == NULL) {")
        appendLine("        jclass fltLocal = (*env)->FindClass(env, \"java/lang/Float\");")
        appendLine("        _any_boxed_Float_cls = (*env)->NewGlobalRef(env, fltLocal);")
        appendLine("        _any_boxed_Float_ctor = (*env)->GetMethodID(env, _any_boxed_Float_cls, \"<init>\", \"(F)V\");")
        appendLine("    }")
      }
      if (!isEnum) {
        for (f in bodyFields) {
          appendLine("    _fld_${f.name} = (*env)->GetFieldID(env, _cls, \"${f.name}\", \"${f.jniFieldType}\");")
          appendLine("    if ((*env)->ExceptionCheck(env)) {")
          appendLine("        // Let the pending NoSuchFieldError propagate instead of clearing it.")
          appendLine("        _fld_${f.name} = NULL;")
          appendLine("    }")
        }
      }
    }
    if (host2JsImpl) {
      for (f in host2JsFields) {
        appendLine("    _h2j_fld_${f.name} = (*env)->GetFieldID(env, _cls, \"${f.name}\", \"${f.jniFieldType}\");")
        appendLine("    if ((*env)->ExceptionCheck(env)) {")
        appendLine("        // Let the pending NoSuchFieldError propagate instead of clearing it.")
        appendLine("        _h2j_fld_${f.name} = NULL;")
        appendLine("    }")
      }
      for (f in host2JsUnboxFields) {
        val underlying = f.underlyingKtType
        val unboxSig = when {
          underlying != null && isKnownType(underlying) && isJniPrimitive(underlying) -> "()" + kotlinToJniFieldType[underlying]
          underlying == "kotlin.String" -> "()Ljava/lang/String;"
          else -> "()Ljava/lang/Object;"
        }
        appendLine("    _h2j_unbox_${f.name} = (*env)->GetMethodID(env, _cls, \"unbox-impl\", \"$unboxSig\");")
        appendLine("    if ((*env)->ExceptionCheck(env)) {")
        appendLine("        // Let the pending NoSuchMethodError propagate instead of clearing it.")
        appendLine("        _h2j_unbox_${f.name} = NULL;")
        appendLine("    }")
      }
      if (annotatedClass.kind == ClassKind.ENUM_CLASS) {
        appendLine("    _h2j_ordinal = (*env)->GetMethodID(env, _cls, \"ordinal\", \"()I\");")
        appendLine("    if ((*env)->ExceptionCheck(env)) {")
        appendLine("        // Let the pending NoSuchMethodError propagate instead of clearing it.")
        appendLine("        _h2j_ordinal = NULL;")
        appendLine("    }")
      }
    }
    appendLine("}")
    appendLine()

    if (js2Host) {
    // -- toJavaObject function --
    // Recursive collection/array/map converters are emitted before the converter that calls them.
    val helpers = StringBuilder()
    for (field in fields) {
      val kt = field.effectiveKtType
      if (kt == "kotlin.collections.List" || kt in MAP_C_TYPES || kt == "kotlin.Array" || kt in PRIMITIVE_ARRAY_ELEMENT_TYPE) {
        emitCValueConverter(helpers, "conv_${field.name}", kt, field.type)
      }
    }
    if (helpers.isNotEmpty()) {
      val withFindMethod = StringBuilder()
      emitCBridgeFindMethod(withFindMethod)
      withFindMethod.append(helpers)
      append(withFindMethod)
      appendLine()
    }

    appendLine("static jobject ${functionPrefix}_toJavaObject(JNIEnv *env, JSContext *ctx, JSValue jsObj) {")
    if (isEnum) {
      appendLine("    if (_cls == NULL || _valuesMethod == NULL) {")
      appendLine("#ifdef __ANDROID__")
      appendLine("        __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"_toJavaObject: cached refs NULL for $jniClassName\");")
      appendLine("#endif")
      appendLine("        return NULL;")
      appendLine("    }")
    } else if (!isObject) {
      appendLine("    if (_cls == NULL || _ctor == NULL) {")
      appendLine("#ifdef __ANDROID__")
      appendLine("        __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"_toJavaObject: cached refs NULL for $jniClassName\");")
      appendLine("#endif")
      appendLine("        return NULL;")
      appendLine("    }")
    } else if (isCompanion) {
      appendLine("    if (_cls == NULL || _outerCls == NULL || _companionField == NULL) {")
      appendLine("#ifdef __ANDROID__")
      appendLine("        __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"_toJavaObject: cached refs NULL for $jniClassName\");")
      appendLine("#endif")
      appendLine("        return NULL;")
      appendLine("    }")
    } else {
      appendLine("    if (_cls == NULL || _instField == NULL) {")
      appendLine("#ifdef __ANDROID__")
      appendLine("        __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"_toJavaObject: cached refs NULL for $jniClassName\");")
      appendLine("#endif")
      appendLine("        return NULL;")
      appendLine("    }")
    }
    appendLine()

    if (isEnum) {
      // Enum — read the JS ordinal and return values()[ordinal].
      appendLine("    JSValue ordinalRaw = JS_GetPropertyStr(ctx, jsObj, \"ordinal_1\");")
      appendLine("    jint ordinal = (jint)(JS_VALUE_GET_NORM_TAG(ordinalRaw) == JS_TAG_FLOAT64 ? JS_VALUE_GET_FLOAT64(ordinalRaw) : JS_VALUE_GET_INT(ordinalRaw));")
      appendLine("    JS_FreeValue(ctx, ordinalRaw);")
      appendLine("    jobjectArray values = (*env)->CallStaticObjectMethod(env, _cls, _valuesMethod);")
      appendLine("    if ((*env)->ExceptionCheck(env)) return NULL;")
      appendLine("    jobject result = (*env)->GetObjectArrayElement(env, values, ordinal);")
      appendLine("    if ((*env)->ExceptionCheck(env)) return NULL;")
      appendLine("    return result;")
    } else {
      if (fields.isNotEmpty()) {
        appendLine("    // Extract field values from JS object")
      }

    // Extract each field from the JS object
    for (field in fields) {
      val nullablePrimitive = field.isNullable && isKnownType(field.ktType) && isJniPrimitive(field.ktType)
      // Non-nullable inline value classes are erased to their underlying JNI primitive on JVM,
      // so dispatch on effectiveKtType (unwrapped) for the primitive branches.
      val cType = if (nullablePrimitive) "jobject"
        else kotlinToCType[field.effectiveKtType] ?: "jobject"
      // JVM value for the field
      val javaVar = "java_${field.name}"

      appendLine("    JSValue js_${field.name} = JS_GetPropertyStr(ctx, jsObj, \"${field.jsPropertyName}\");")
      // For Long-backed inline classes (e.g. Color), the JS object may be unboxed —
      // jsObj IS the Long {low_1, high_1} with no .value wrapper. If .value is
      // undefined, fall back to using jsObj directly as the Long representation.
      if (field.ktType == "kotlin.Long" && isInlineClass(annotatedClass)) {
        appendLine("    if (JS_IsUndefined(js_${field.name})) {")
        appendLine("        JS_FreeValue(ctx, js_${field.name});")
        appendLine("        js_${field.name} = JS_DupValue(ctx, jsObj);")
        appendLine("    }")
      }
      // Inline value classes (e.g. @JvmInline value class Id(val value: Int))
      // may be boxed ({value: ...}) or unboxed (plain int) in Kotlin/JS depending
      // on context. We check the tag: if it's an object, unwrap .value; otherwise use as-is.
      // EXCEPTION: Long-backed inline classes (e.g. Color) — Long is already a JS
      // object {low_1, high_1} in Kotlin/JS, so the object IS the value, not a wrapper.
      if (field.isInline && field.underlyingKtType != "kotlin.Long") {
        appendLine("    if (JS_VALUE_GET_NORM_TAG(js_${field.name}) == JS_TAG_OBJECT) {")
        appendLine("        JSValue _inner = JS_GetPropertyStr(ctx, js_${field.name}, \"value\");")
        appendLine("        JS_FreeValue(ctx, js_${field.name});")
        appendLine("        js_${field.name} = _inner;")
        appendLine("    }")
      }
      appendLine("    $cType $javaVar;")
      appendLine("    {")

      // Open null check for nullable fields
      if (field.isNullable) {
        appendLine("        if (!JS_IsUndefined(js_${field.name}) && !JS_IsNull(js_${field.name})) {")
      }

      when {
        field.isNullable && isKnownType(field.ktType) && isJniPrimitive(field.ktType) -> {
          emitNullablePrimitiveExtraction(this, field)
        }
        field.effectiveKtType == "kotlin.Boolean" -> {
          appendLine("        $javaVar = (jboolean)JS_VALUE_GET_BOOL(js_${field.name});")
        }
        field.effectiveKtType == "kotlin.Byte" -> {
          appendLine("        int tag_${field.name} = JS_VALUE_GET_NORM_TAG(js_${field.name});")
          appendLine("        $javaVar = (jbyte)(tag_${field.name} == JS_TAG_FLOAT64 ? JS_VALUE_GET_FLOAT64(js_${field.name}) : JS_VALUE_GET_INT(js_${field.name}));")
        }
        field.effectiveKtType == "kotlin.Short" -> {
          appendLine("        int tag_${field.name} = JS_VALUE_GET_NORM_TAG(js_${field.name});")
          appendLine("        $javaVar = (jshort)(tag_${field.name} == JS_TAG_FLOAT64 ? JS_VALUE_GET_FLOAT64(js_${field.name}) : JS_VALUE_GET_INT(js_${field.name}));")
        }
        field.effectiveKtType == "kotlin.Int" -> {
          appendLine("        int tag_${field.name} = JS_VALUE_GET_NORM_TAG(js_${field.name});")
          appendLine("        $javaVar = (jint)(tag_${field.name} == JS_TAG_FLOAT64 ? JS_VALUE_GET_FLOAT64(js_${field.name}) : JS_VALUE_GET_INT(js_${field.name}));")
        }
        field.effectiveKtType == "kotlin.Long" -> {
          appendLine("        int tag_${field.name} = JS_VALUE_GET_NORM_TAG(js_${field.name});")
          appendLine("        if (tag_${field.name} == JS_TAG_FLOAT64) {")
          appendLine("            $javaVar = (jlong)JS_VALUE_GET_FLOAT64(js_${field.name});")
          appendLine("        } else if (tag_${field.name} == JS_TAG_INT) {")
          appendLine("            $javaVar = (jlong)JS_VALUE_GET_INT(js_${field.name});")
          appendLine("        } else if (tag_${field.name} == JS_TAG_OBJECT) {")
          appendLine("            /* Kotlin/JS Long: {low_1, high_1} packed representation */")
          appendLine("            JSValue lowVal = JS_GetPropertyStr(ctx, js_${field.name}, \"low_1\");")
          appendLine("            JSValue highVal = JS_GetPropertyStr(ctx, js_${field.name}, \"high_1\");")
          appendLine("            jint low = (JS_VALUE_GET_NORM_TAG(lowVal) == JS_TAG_FLOAT64) ? (jint)JS_VALUE_GET_FLOAT64(lowVal) : (jint)JS_VALUE_GET_INT(lowVal);")
          appendLine("            jint high = (JS_VALUE_GET_NORM_TAG(highVal) == JS_TAG_FLOAT64) ? (jint)JS_VALUE_GET_FLOAT64(highVal) : (jint)JS_VALUE_GET_INT(highVal);")
          appendLine("            $javaVar = ((jlong)high << 32) | ((jlong)low & 0xFFFFFFFF);")
          appendLine("            JS_FreeValue(ctx, lowVal);")
          appendLine("            JS_FreeValue(ctx, highVal);")
          appendLine("        } else {")
          appendLine("#ifdef __ANDROID__")
          appendLine("            __android_log_print(ANDROID_LOG_ERROR, \"BRIDGE\", \"Long field ${field.name}: unexpected JS tag %d\", tag_${field.name});")
          appendLine("#endif")
          appendLine("            $javaVar = 0;")
          appendLine("        }")
        }
        field.effectiveKtType == "kotlin.Float" || field.effectiveKtType == "kotlin.Double" -> {
          val cast = if (field.effectiveKtType == "kotlin.Float") "(jfloat)" else "(jdouble)"
          appendLine("        int tag_${field.name}_d = JS_VALUE_GET_NORM_TAG(js_${field.name});")
          appendLine("        if (tag_${field.name}_d == JS_TAG_FLOAT64) {")
          appendLine("            $javaVar = ${cast}JS_VALUE_GET_FLOAT64(js_${field.name});")
          appendLine("        } else if (tag_${field.name}_d == JS_TAG_INT) {")
          appendLine("            $javaVar = ${cast}JS_VALUE_GET_INT(js_${field.name});")
          appendLine("        } else {")
          appendLine("#ifdef __ANDROID__")
          appendLine("            __android_log_print(ANDROID_LOG_ERROR, \"BRIDGE\", \"Float/Double field ${field.name}: unexpected JS tag %d\", tag_${field.name}_d);")
          appendLine("#endif")
          appendLine("            /* JS_TAG_UNDEFINED=3, JS_TAG_NULL=2 */")
          appendLine("            $javaVar = 0;")
          appendLine("        }")
        }
        field.effectiveKtType == "kotlin.Char" -> {
          appendLine("        int tag_${field.name} = JS_VALUE_GET_NORM_TAG(js_${field.name});")
          appendLine("        $javaVar = (jchar)(tag_${field.name} == JS_TAG_FLOAT64 ? JS_VALUE_GET_FLOAT64(js_${field.name}) : JS_VALUE_GET_INT(js_${field.name}));")
        }
        field.effectiveKtType == "kotlin.String" -> {
          appendLine("        const char *str_${field.name} = JS_ToCString(ctx, js_${field.name});")
          appendLine("        $javaVar = (*env)->NewStringUTF(env, str_${field.name});")
          appendLine("        JS_FreeCString(ctx, str_${field.name});")
        }
        field.effectiveKtType == "kotlin.collections.List" -> {
          val fn = emitCValueConverter(helpers, "conv_${field.name}", field.effectiveKtType, field.type)
          appendLine("        $javaVar = $fn(env, ctx, js_${field.name});")
        }
        field.effectiveKtType in MAP_C_TYPES -> {
          val fn = emitCValueConverter(helpers, "conv_${field.name}", field.effectiveKtType, field.type)
          appendLine("        $javaVar = $fn(env, ctx, js_${field.name});")
        }
        field.effectiveKtType == "kotlin.Any" -> {
          emitAnyFieldExtraction(this, field)
        }
        field.effectiveKtType == "kotlin.Array" -> {
          val fn = emitCValueConverter(helpers, "conv_${field.name}", field.effectiveKtType, field.type)
          appendLine("        $javaVar = $fn(env, ctx, js_${field.name});")
        }
        field.isArray -> {
          val fn = emitCValueConverter(helpers, "conv_${field.name}", field.effectiveKtType, field.type)
          appendLine("        $javaVar = $fn(env, ctx, js_${field.name});")
        }
        field.isInline && field.isNullable -> {
          val inlineCPrefix = cFunctionPrefix(FqName(field.ktType))
          appendLine("        $javaVar = ${inlineCPrefix}_fromValue(env, ctx, js_${field.name});")
        }
        field.isObjectType -> {
          appendLine("        // Look up bridge_dispatch on the sub-object to convert it.")
          appendLine("        JSValue disp_val_${field.name} = JS_GetPropertyStr(ctx, js_${field.name}, \"bridge_dispatch\");")
          appendLine("        if (JS_IsUndefined(disp_val_${field.name})) {")
          appendLine("#ifdef __ANDROID__")
          appendLine("            const char *dbgCtorNm_${field.name} = \"(unknown)\";")
          appendLine("            JSValue dbgCtor_${field.name} = JS_GetPropertyStr(ctx, js_${field.name}, \"constructor\");")
          appendLine("            if (!JS_IsUndefined(dbgCtor_${field.name}) && !JS_IsNull(dbgCtor_${field.name})) {")
          appendLine("                JSValue dbgCtorNmVal_${field.name} = JS_GetPropertyStr(ctx, dbgCtor_${field.name}, \"name\");")
          appendLine("                dbgCtorNm_${field.name} = JS_ToCString(ctx, dbgCtorNmVal_${field.name});")
          appendLine("                JS_FreeValue(ctx, dbgCtorNmVal_${field.name});")
          appendLine("            }")
          appendLine("            __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"bridge_dispatch not found for field ${field.name} (ctor=%s)\", dbgCtorNm_${field.name});")
          appendLine("            if (strcmp(dbgCtorNm_${field.name}, \"(unknown)\") != 0) JS_FreeCString(ctx, dbgCtorNm_${field.name});")
          appendLine("            JS_FreeValue(ctx, dbgCtor_${field.name});")
          appendLine("#endif")
          appendLine("            JS_FreeValue(ctx, disp_val_${field.name});")
          appendLine("            JS_FreeValue(ctx, js_${field.name});")
          appendLine("            return NULL;")
          appendLine("        }")
          appendLine("        // TODO: when pointer compression lands, use JS_VALUE_GET_INT(disp_val_...) to read arena offset")
          appendLine("        BridgeConverterFn disp_${field.name} = bridgeConverterFromJSValue(disp_val_${field.name});")
          appendLine("        $javaVar = disp_${field.name}(env, ctx, js_${field.name});")
          appendLine("        JS_FreeValue(ctx, disp_val_${field.name});")
        }
      }

      // Close null check for nullable fields
      if (field.isNullable) {
        appendLine("        } else {")
        appendLine("            $javaVar = NULL;")
        appendLine("        }")
      }

      appendLine("        JS_FreeValue(ctx, js_${field.name});")
      appendLine("    }")
      appendLine()
    }

    // Create instance using cached class/constructor/field refs.
    appendLine("    // Create instance using cached JNI references")
    if (isCompanion) {
      appendLine("    jobject result = (*env)->GetStaticObjectField(env, _outerCls, _companionField);")
    } else if (isObject) {
      appendLine("    jobject result = (*env)->GetStaticObjectField(env, _cls, _instField);")
    } else {
      if (constructorFields.isEmpty()) {
        appendLine("    jobject result = (*env)->NewObject(env, _cls, _ctor);")
      } else {
        // `java_${it.name}` is what used to be called `javaVar` in the field-extracting loop.
        val args = constructorFields.joinToString(", ") { "java_${it.name}" }
        appendLine("    jobject result = (*env)->NewObject(env, _cls, _ctor, $args);")
      }
    }
    appendLine("    if ((*env)->ExceptionCheck(env)) return NULL;")
    appendLine()

    // Set body fields using cached field IDs
    if (bodyFields.isNotEmpty()) {
      appendLine("    // Set non-constructor fields")
      for (field in bodyFields) {
        val javaVar = "java_${field.name}"  // Same var name as in the field-extracting loop
        val setFn = when {
          field.isNullable && isKnownType(field.ktType) && isJniPrimitive(field.ktType) -> "SetObjectField"
          else -> kotlinToSetFieldFunction[field.ktType] ?: "SetObjectField"
        }
        appendLine("    if (_fld_${field.name} != NULL) {")
        appendLine("        (*env)->$setFn(env, result, _fld_${field.name}, $javaVar);")
        appendLine("    }")
      }
      appendLine()
    }

    appendLine("    return result;")
    }
    appendLine("}")

    if (isInlineClass(annotatedClass) && !isObject && constructorFields.size == 1) {
      val underlyingField = constructorFields[0]
      // The underlying type may itself be an inline class (e.g. SpaceArrangement(val spacing: Dp));
      // use effectiveKtType to reach the primitive C type.
      val underlyingCType = kotlinToCType[underlyingField.effectiveKtType] ?: "jobject"
      appendLine()
      appendLine("jobject ${functionPrefix}_fromValue(JNIEnv *env, JSContext *ctx, JSValue jsVal) {")
      appendLine("    ${functionPrefix}_init(env);")
      appendLine("    if (_cls == NULL || _ctor == NULL) return NULL;")
      appendLine("    $underlyingCType java_v;")
      appendLine("    {")
      appendLine("        int tag_v = JS_VALUE_GET_NORM_TAG(jsVal);")
      appendLine("        if (tag_v == JS_TAG_FLOAT64) {")
      appendLine("            java_v = ($underlyingCType)JS_VALUE_GET_FLOAT64(jsVal);")
      appendLine("        } else if (tag_v == JS_TAG_INT) {")
      appendLine("            java_v = ($underlyingCType)JS_VALUE_GET_INT(jsVal);")
      if (underlyingField.effectiveKtType == "kotlin.Long") {
        appendLine("        } else if (tag_v == JS_TAG_OBJECT) {")
        appendLine("            /* Kotlin/JS Long: {low_1, high_1} packed representation */")
        appendLine("            JSValue lv = JS_GetPropertyStr(ctx, jsVal, \"low_1\");")
        appendLine("            JSValue hv = JS_GetPropertyStr(ctx, jsVal, \"high_1\");")
        appendLine("            java_v = (($underlyingCType)JS_VALUE_GET_INT(hv) << 32) | (($underlyingCType)JS_VALUE_GET_INT(lv) & 0xFFFFFFFF);")
        appendLine("            JS_FreeValue(ctx, lv);")
        appendLine("            JS_FreeValue(ctx, hv);")
      }
      appendLine("        } else {")
      appendLine("#ifdef __ANDROID__")
      appendLine("            __android_log_print(ANDROID_LOG_ERROR, \"BRIDGE\", \"_fromValue: unexpected JS tag %d\", tag_v);")
      appendLine("#endif")
      appendLine("            java_v = 0;")
      appendLine("        }")
      appendLine("    }")
      appendLine("    jobject result = (*env)->NewObject(env, _cls, _ctor, java_v);")
      appendLine("    if ((*env)->ExceptionCheck(env)) return NULL;")
      appendLine("    return result;")
      appendLine("}")
    }
    } // end js2Host sections

    if (host2JsImpl) {
      emitHost2JsConvertToJs(this, host2JsJniFunctionName!!, functionPrefix, annotatedClass, host2JsFields, host2JsUnboxFields, protoFqn, jniClassName)
    }

    appendLine()
    appendLine("// Register this class in the shared bridge dispatch table (Context.cpp).")
    appendLine("__attribute__((used, constructor))")
    appendLine("void ${functionPrefix}_bridge_register(void) {")
    appendLine("    addBridgeInit(${functionPrefix}_init);")
    if (js2Host) {
      appendLine("    addBridgeEntry(\"$jsClassName\", ${functionPrefix}_toJavaObject);")
    }
    appendLine("}")
  }

  val outputFile = File(outputDir, cFileName(fqName))
  outputFile.parentFile.mkdirs()
  outputFile.writeText(cSource)
}

/**
 * Emit the JNI implementation of the IR-injected `convertToJs(J)J` member: build a new JS
 * instance with the retained guest class prototype (via bridgeNewJsObject) and set every field
 * as a JS property (recursively converting values via bridgeAnyToJs / bridgeLongToJs). Errors
 * are loud: a pending Java exception is left pending and 0 is returned (crashes at the JNI
 * boundary); a missing prototype throws IllegalStateException.
 */
private fun emitHost2JsConvertToJs(
  sb: StringBuilder,
  jniFunctionName: String,
  functionPrefix: String,
  annotatedClass: IrClass,
  fields: List<FieldInfo>,
  unboxFields: List<FieldInfo>,
  protoFqn: String,
  jniClassName: String,
) {
  val isEnum = annotatedClass.kind == ClassKind.ENUM_CLASS
  sb.appendLine()
  sb.appendLine("// Host2JS: builds a JS counterpart of this object with the guest class prototype.")
  sb.appendLine("JNIEXPORT jlong JNICALL $jniFunctionName(JNIEnv *env, jobject self, jlong ctxPtr) {")
  sb.appendLine("    ${functionPrefix}_init(env);")
  sb.appendLine("    JSContext *ctx = (JSContext *)ctxPtr;")
  sb.appendLine("    if (_cls == NULL) {")
  sb.appendLine("        (*env)->ThrowNew(env, (*env)->FindClass(env, \"java/lang/IllegalStateException\"), \"host2js: _init failed for $jniClassName\");")
  sb.appendLine("        return 0;")
  sb.appendLine("    }")
  sb.appendLine("    // New instance whose prototype is the EXISTING retained guest prototype for \"$protoFqn\".")
  sb.appendLine("    JSValue result = bridgeNewJsObject(ctx, \"$protoFqn\");")
  sb.appendLine("    if (JS_IsUndefined(result)) {")
  sb.appendLine("        (*env)->ThrowNew(env, (*env)->FindClass(env, \"java/lang/IllegalStateException\"), \"host2js: no registered prototype for $protoFqn\");")
  sb.appendLine("        return 0;")
  sb.appendLine("    }")
  if (isEnum) {
    sb.appendLine("    jint ordinal = (*env)->CallIntMethod(env, self, _h2j_ordinal);")
    sb.appendLine("    JS_SetPropertyStr(ctx, result, \"ordinal_1\", JS_NewInt32(ctx, ordinal));")
  } else {
    for (field in fields) {
      emitHost2JsField(sb, field)
    }
  }
  sb.appendLine("    return (jlong)(intptr_t)JS_VALUE_GET_PTR(result);")
  sb.appendLine("}")
}

/** Emit the field conversion for one field inside a generated convertToJs body. */
private fun emitHost2JsField(sb: StringBuilder, field: FieldInfo) {
  val jsName = field.jsPropertyName
  val fieldName = field.name
  val effective = field.effectiveKtType

  val nonNullablePrimitive = !field.isNullable && isKnownType(effective) && isJniPrimitive(effective)
  if (nonNullablePrimitive) {
    if (effective == "kotlin.Long") {
      sb.appendLine("    {")
      sb.appendLine("        JSValue _v = bridgeLongToJs(env, ctx, (*env)->GetLongField(env, self, _h2j_fld_$fieldName));")
      sb.appendLine("        if ((*env)->ExceptionCheck(env)) { JS_FreeValue(ctx, result); return 0; }")
      sb.appendLine("        JS_SetPropertyStr(ctx, result, \"$jsName\", _v);")
      sb.appendLine("    }")
    } else {
      val getFn = when (effective) {
        "kotlin.Boolean" -> "GetBooleanField"
        "kotlin.Byte" -> "GetByteField"
        "kotlin.Char" -> "GetCharField"
        "kotlin.Short" -> "GetShortField"
        "kotlin.Float" -> "GetFloatField"
        "kotlin.Double" -> "GetDoubleField"
        else -> "GetIntField"
      }
      val build = when (effective) {
        "kotlin.Boolean" -> "JS_NewBool(ctx, (*env)->$getFn(env, self, _h2j_fld_$fieldName))"
        "kotlin.Float" -> "JS_NewFloat64(ctx, (jdouble)(*env)->$getFn(env, self, _h2j_fld_$fieldName))"
        "kotlin.Double" -> "JS_NewFloat64(ctx, (*env)->$getFn(env, self, _h2j_fld_$fieldName))"
        else -> "JS_NewInt32(ctx, (jint)(*env)->$getFn(env, self, _h2j_fld_$fieldName))"
      }
      sb.appendLine("    JS_SetPropertyStr(ctx, result, \"$jsName\", $build);")
    }
    return
  }

  if (field.isInline && field.isNullable) {
    // Nullable inline class: boxed holder field; unbox via the cached unbox-impl.
    sb.appendLine("    {")
    sb.appendLine("        jobject b = (*env)->GetObjectField(env, self, _h2j_fld_$fieldName);")
    sb.appendLine("        if (b == NULL) {")
    sb.appendLine("            JS_SetPropertyStr(ctx, result, \"$jsName\", JS_NULL);")
    sb.appendLine("        } else {")
    val underlying = field.underlyingKtType
    when {
      underlying == "kotlin.Long" -> {
        sb.appendLine("            jlong u = (*env)->CallLongMethod(env, b, _h2j_unbox_$fieldName);")
        sb.appendLine("            JSValue _v = bridgeLongToJs(env, ctx, u);")
        sb.appendLine("            if ((*env)->ExceptionCheck(env)) { (*env)->DeleteLocalRef(env, b); JS_FreeValue(ctx, result); return 0; }")
        sb.appendLine("            JS_SetPropertyStr(ctx, result, \"$jsName\", _v);")
      }
      underlying != null && isKnownType(underlying) && isJniPrimitive(underlying) -> {
        val call = when (underlying) {
          "kotlin.Boolean" -> "CallBooleanMethod"
          "kotlin.Float" -> "CallFloatMethod"
          "kotlin.Double" -> "CallDoubleMethod"
          else -> "CallIntMethod"
        }
        val cType = when (underlying) {
          "kotlin.Boolean" -> "jboolean"
          "kotlin.Float" -> "jfloat"
          "kotlin.Double" -> "jdouble"
          else -> "jint"
        }
        val build = when (underlying) {
          "kotlin.Boolean" -> "JS_NewBool(ctx, u)"
          "kotlin.Float", "kotlin.Double" -> "JS_NewFloat64(ctx, (jdouble)u)"
          else -> "JS_NewInt32(ctx, (jint)u)"
        }
        sb.appendLine("            $cType u = (*env)->$call(env, b, _h2j_unbox_$fieldName);")
        sb.appendLine("            JS_SetPropertyStr(ctx, result, \"$jsName\", $build);")
      }
      else -> {
        // Reference-backed value class: unbox to the underlying reference and convert.
        sb.appendLine("            jobject u = (*env)->CallObjectMethod(env, b, _h2j_unbox_$fieldName);")
        sb.appendLine("            JSValue _v = bridgeAnyToJs(env, ctx, u);")
        sb.appendLine("            if (u) (*env)->DeleteLocalRef(env, u);")
        sb.appendLine("            if ((*env)->ExceptionCheck(env)) { (*env)->DeleteLocalRef(env, b); JS_FreeValue(ctx, result); return 0; }")
        sb.appendLine("            JS_SetPropertyStr(ctx, result, \"$jsName\", _v);")
      }
    }
    sb.appendLine("            (*env)->DeleteLocalRef(env, b);")
    sb.appendLine("        }")
    sb.appendLine("    }")
    return
  }

  // Everything else: nullable primitives, String, Any, collections, arrays, object fields.
  sb.appendLine("    {")
  sb.appendLine("        jobject f = (*env)->GetObjectField(env, self, _h2j_fld_$fieldName);")
  sb.appendLine("        if (f == NULL) {")
  sb.appendLine("            JS_SetPropertyStr(ctx, result, \"$jsName\", JS_NULL);")
  sb.appendLine("        } else {")
  sb.appendLine("            JSValue _v = bridgeAnyToJs(env, ctx, f);")
  sb.appendLine("            (*env)->DeleteLocalRef(env, f);")
  sb.appendLine("            if ((*env)->ExceptionCheck(env)) { JS_FreeValue(ctx, result); return 0; }")
  sb.appendLine("            JS_SetPropertyStr(ctx, result, \"$jsName\", _v);")
  sb.appendLine("        }")
  sb.appendLine("    }")
}

// -- FQN-toJavaObject registration via shared bridgeTable (Context.cpp) --

/** Emit boxing extraction for a nullable primitive field (e.g. Int? → Integer). */
internal fun emitNullablePrimitiveExtraction(
  sb: StringBuilder,
  field: FieldInfo,
) {
  val info = boxedPrimitiveInfo[field.ktType]!!
  val javaVar = "java_${field.name}"

  if (field.ktType == "kotlin.Long") {
    // Long needs tag-based extraction
    sb.appendLine("            int tag_${field.name} = JS_VALUE_GET_NORM_TAG(js_${field.name});")
    sb.appendLine("            jlong longVal;")
    sb.appendLine("            if (tag_${field.name} == JS_TAG_FLOAT64) {")
    sb.appendLine("                longVal = (jlong)JS_VALUE_GET_FLOAT64(js_${field.name});")
    sb.appendLine("            } else {")
    sb.appendLine("                longVal = (jlong)JS_VALUE_GET_INT(js_${field.name});")
    sb.appendLine("            }")
    sb.appendLine("            $javaVar = (*env)->NewObject(env, _boxed_${field.name}, _boxedCtor_${field.name}, longVal);")
  } else if (field.ktType == "kotlin.Float" || field.ktType == "kotlin.Double") {
    // JS numbers are tagged INT or FLOAT64; read the tag first, then box.
    val cType = info.cType
    sb.appendLine("            int tag_${field.name} = JS_VALUE_GET_NORM_TAG(js_${field.name});")
    sb.appendLine("            $cType numVal_${field.name};")
    sb.appendLine("            if (tag_${field.name} == JS_TAG_INT) {")
    sb.appendLine("                numVal_${field.name} = ($cType)JS_VALUE_GET_INT(js_${field.name});")
    sb.appendLine("            } else {")
    sb.appendLine("                numVal_${field.name} = ($cType)JS_VALUE_GET_FLOAT64(js_${field.name});")
    sb.appendLine("            }")
    sb.appendLine("            $javaVar = (*env)->NewObject(env, _boxed_${field.name}, _boxedCtor_${field.name}, numVal_${field.name});")
  } else {
    val getter = info.jsGetter
    val cast = info.jsCast
    if (getter == "JS_VALUE_GET_INT") {
      // JS numbers may be FLOAT64-tagged even for integral values; a raw int read
      // would return the double's low 32 bits (0). Read the tag first.
      sb.appendLine("            int tag_${field.name} = JS_VALUE_GET_NORM_TAG(js_${field.name});")
      sb.appendLine("            $javaVar = (*env)->NewObject(env, _boxed_${field.name}, _boxedCtor_${field.name}, ${cast}(tag_${field.name} == JS_TAG_FLOAT64 ? JS_VALUE_GET_FLOAT64(js_${field.name}) : JS_VALUE_GET_INT(js_${field.name})));")
    } else {
      sb.appendLine(
        "            $javaVar = (*env)->NewObject(env, _boxed_${field.name}, _boxedCtor_${field.name}, ${cast}${getter}(js_${field.name}));"
      )
    }
  }
}

// -- Any? field extraction: dispatch on JS tag --

internal fun emitAnyFieldExtraction(
  sb: StringBuilder,
  field: FieldInfo,
) {
  val javaVar = "java_${field.name}"
  sb.appendLine("        // Any? field — delegate to bridgeForAny")
  sb.appendLine("        $javaVar = bridgeForAny(env, ctx, js_${field.name});")
}

// -- Collection field extraction (List, Set, Map from Kotlin stdlib) --

/** Map types recognized by the recursive C converters. */
private val MAP_C_TYPES = setOf(
  "kotlin.collections.Map", "kotlin.collections.MutableMap",
  "kotlin.collections.HashMap", "kotlin.collections.LinkedHashMap",
)

/** Emits the shared prototype-method discovery helper (once per file with collection fields). */
private fun emitCBridgeFindMethod(helpers: StringBuilder) {
  helpers.appendLine(
    """
    static JSValue bridgeFindMethod(JSContext* ctx, JSValue obj, const char* prefix) {
      JSValue ctor = JS_GetPropertyStr(ctx, obj, "constructor");
      JSValue proto = JS_GetPropertyStr(ctx, ctor, "prototype");
      JS_FreeValue(ctx, ctor);
      while (JS_IsObject(proto)) {
        JSPropertyEnum* ptab; uint32_t plen;
        if (JS_GetOwnPropertyNames(ctx, &ptab, &plen, proto, JS_GPN_STRING_MASK) == 0) {
          for (uint32_t i = 0; i < plen; i++) {
            const char* nm = JS_AtomToCString(ctx, ptab[i].atom);
            if (nm != NULL && strncmp(nm, prefix, strlen(prefix)) == 0) {
              JSValue fn = JS_GetPropertyStr(ctx, obj, nm);
              JS_FreeCString(ctx, nm);
              JS_FreePropertyEnum(ctx, ptab, plen);
              JS_FreeValue(ctx, proto);
              return fn;
            }
            JS_FreeCString(ctx, nm);
          }
          JS_FreePropertyEnum(ctx, ptab, plen);
        }
        JSValue parent = JS_GetPropertyStr(ctx, proto, "__proto__");
        JS_FreeValue(ctx, proto);
        proto = parent;
      }
      JS_FreeValue(ctx, proto);
      return JS_DupValue(ctx, JS_UNDEFINED);
    }
    """.trimIndent(),
  )
  helpers.appendLine()
}

/**
 * Boxed Float/Double converter. JS numbers are tagged either JS_TAG_INT or JS_TAG_FLOAT64
 * (QuickJS stores int32-sized integral numbers as JS_TAG_INT), so reading the float64 slot
 * of an int-tagged value yields garbage. Dispatch on the tag, as the field-level converters do.
 */
private fun emitCFloatDoubleBoxedConverter(
  helpers: StringBuilder,
  name: String,
  boxedClass: String,
  ctorSig: String,
  cType: String,
) {
  helpers.appendLine(
    """
    static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
      int tag = JS_VALUE_GET_NORM_TAG(jsVal);
      $cType val = (tag == JS_TAG_INT) ? ($cType)JS_VALUE_GET_INT(jsVal) : ($cType)JS_VALUE_GET_FLOAT64(jsVal);
      jclass c = (*env)->FindClass(env, "$boxedClass");
      jmethodID m = (*env)->GetMethodID(env, c, "<init>", "$ctorSig");
      return (*env)->NewObject(env, c, m, val);
    }
    """.trimIndent(),
  )
  helpers.appendLine()
}

private fun emitCBoxedConverter(
  helpers: StringBuilder,
  name: String,
  boxedClass: String,
  ctorSig: String,
  valueExpr: String,
) {
  helpers.appendLine(
    """
    static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
      jclass c = (*env)->FindClass(env, "$boxedClass");
      jmethodID m = (*env)->GetMethodID(env, c, "<init>", "$ctorSig");
      return (*env)->NewObject(env, c, m, $valueExpr);
    }
    """.trimIndent(),
  )
  helpers.appendLine()
}

/**
 * Emits a C static function converting a JS value of [ktType] into a jobject (borrowed input;
 * the caller owns and frees the JSValue). Recurses for nested collections. Returns the function name.
 */
private fun emitCValueConverter(
  helpers: StringBuilder,
  name: String,
  ktType: String,
  irType: IrType?,
): String {
  when (ktType) {
    "kotlin.String" -> {
      helpers.appendLine(
        """
        static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
          const char* s = JS_ToCString(ctx, jsVal);
          jobject r = (*env)->NewStringUTF(env, s);
          JS_FreeCString(ctx, s);
          return r;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    "kotlin.Int" -> emitCBoxedConverter(helpers, name, "java/lang/Integer", "(I)V", "JS_VALUE_GET_INT(jsVal)")
    "kotlin.Float" -> emitCFloatDoubleBoxedConverter(helpers, name, "java/lang/Float", "(F)V", "jfloat")
    "kotlin.Double" -> emitCFloatDoubleBoxedConverter(helpers, name, "java/lang/Double", "(D)V", "jdouble")
    "kotlin.Boolean" -> emitCBoxedConverter(helpers, name, "java/lang/Boolean", "(Z)V", "JS_VALUE_GET_BOOL(jsVal)")
    "kotlin.Long" -> emitCBoxedConverter(helpers, name, "java/lang/Long", "(J)V", "JS_VALUE_GET_INT(jsVal)")
    "kotlin.Byte" -> emitCBoxedConverter(helpers, name, "java/lang/Byte", "(B)V", "(jbyte)JS_VALUE_GET_INT(jsVal)")
    "kotlin.Short" -> emitCBoxedConverter(helpers, name, "java/lang/Short", "(S)V", "(jshort)JS_VALUE_GET_INT(jsVal)")
    "kotlin.Char" -> emitCBoxedConverter(helpers, name, "java/lang/Character", "(C)V", "(jchar)JS_VALUE_GET_INT(jsVal)")
    "kotlin.Any" -> {
      helpers.appendLine(
        """
        static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
          int tag = JS_VALUE_GET_NORM_TAG(jsVal);
          switch (tag) {
            case JS_TAG_INT: {
              jclass c = (*env)->FindClass(env, "java/lang/Integer");
              jmethodID m = (*env)->GetMethodID(env, c, "<init>", "(I)V");
              return (*env)->NewObject(env, c, m, JS_VALUE_GET_INT(jsVal));
            }
            case JS_TAG_FLOAT64: {
              jclass c = (*env)->FindClass(env, "java/lang/Double");
              jmethodID m = (*env)->GetMethodID(env, c, "<init>", "(D)V");
              return (*env)->NewObject(env, c, m, JS_VALUE_GET_FLOAT64(jsVal));
            }
            case JS_TAG_BOOL: {
              jclass c = (*env)->FindClass(env, "java/lang/Boolean");
              jmethodID m = (*env)->GetMethodID(env, c, "<init>", "(Z)V");
              return (*env)->NewObject(env, c, m, JS_VALUE_GET_BOOL(jsVal));
            }
            case JS_TAG_STRING: {
              const char* s = JS_ToCString(ctx, jsVal);
              jobject r = (*env)->NewStringUTF(env, s);
              JS_FreeCString(ctx, s);
              return r;
            }
            default: {
              JSValue disp = JS_GetPropertyStr(ctx, jsVal, "bridge_dispatch");
              if (!JS_IsUndefined(disp)) {
                BridgeConverterFn fn = bridgeConverterFromJSValue(disp);
                JS_FreeValue(ctx, disp);
                return fn(env, ctx, jsVal);
              }
              JS_FreeValue(ctx, disp);
              return NULL;
            }
          }
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    "kotlin.collections.List" -> {
      val elementType = typeArgument(irType, 0)
      val elementKtType = effectiveClassFqn(elementType)
      val elementName = "${name}_element"
      emitCValueConverter(helpers, elementName, elementKtType, elementType)
      helpers.appendLine(
        """
        static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
          jclass alc = (*env)->FindClass(env, "java/util/ArrayList");
          jmethodID alc_init = (*env)->GetMethodID(env, alc, "<init>", "()V");
          jmethodID alc_add = (*env)->GetMethodID(env, alc, "add", "(Ljava/lang/Object;)Z");
          jobject result = (*env)->NewObject(env, alc, alc_init);
          JSValue arr = jsVal;
          JSValue wrap = JS_GetPropertyStr(ctx, jsVal, "array_1");
          if (!JS_IsUndefined(wrap)) { arr = wrap; }
          JSValue lenVal = JS_GetPropertyStr(ctx, arr, "length");
          jint len = JS_VALUE_GET_INT(lenVal);
          JS_FreeValue(ctx, lenVal);
          for (jint i = 0; i < len; i++) {
            JSValue elem = JS_GetPropertyUint32(ctx, arr, i);
            jobject je = $elementName(env, ctx, elem);
            (*env)->CallBooleanMethod(env, result, alc_add, je);
            (*env)->DeleteLocalRef(env, je);
            JS_FreeValue(ctx, elem);
          }
          JS_FreeValue(ctx, wrap);
          return result;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    "kotlin.Array" -> {
      val elementType = typeArgument(irType, 0)
      val elementKtType = effectiveClassFqn(elementType)
      val elementName = "${name}_element"
      emitCValueConverter(helpers, elementName, elementKtType, elementType)
      helpers.appendLine(
        """
        static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
          jclass oc = (*env)->FindClass(env, "java/lang/Object");
          JSValue lenVal = JS_GetPropertyStr(ctx, jsVal, "length");
          jint len = JS_VALUE_GET_INT(lenVal);
          JS_FreeValue(ctx, lenVal);
          jobjectArray result = (*env)->NewObjectArray(env, len, oc, NULL);
          for (jint i = 0; i < len; i++) {
            JSValue elem = JS_GetPropertyUint32(ctx, jsVal, i);
            jobject je = $elementName(env, ctx, elem);
            (*env)->SetObjectArrayElement(env, result, i, je);
            (*env)->DeleteLocalRef(env, je);
            JS_FreeValue(ctx, elem);
          }
          return result;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    in MAP_C_TYPES -> {
      val keyType = typeArgument(irType, 0)
      val valueType = typeArgument(irType, 1)
      val keyName = "${name}_key"
      val valueName = "${name}_value"
      emitCValueConverter(helpers, keyName, effectiveClassFqn(keyType), keyType)
      emitCValueConverter(helpers, valueName, effectiveClassFqn(valueType), valueType)
      helpers.appendLine(
        """
        static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
          jclass hc = (*env)->FindClass(env, "java/util/HashMap");
          jmethodID hc_init = (*env)->GetMethodID(env, hc, "<init>", "()V");
          jmethodID hc_put = (*env)->GetMethodID(env, hc, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
          jobject result = (*env)->NewObject(env, hc, hc_init);
          JSValue entriesFn = bridgeFindMethod(ctx, jsVal, "get_entries_");
          if (JS_IsUndefined(entriesFn)) { JS_FreeValue(ctx, entriesFn); return result; }
          JSValue entries = JS_Call(ctx, entriesFn, jsVal, 0, NULL);
          JS_FreeValue(ctx, entriesFn);
          JSValue iterFn = bridgeFindMethod(ctx, entries, "iterator_");
          if (JS_IsUndefined(iterFn)) { JS_FreeValue(ctx, iterFn); JS_FreeValue(ctx, entries); return result; }
          JSValue iterator = JS_Call(ctx, iterFn, entries, 0, NULL);
          JS_FreeValue(ctx, iterFn);
          JS_FreeValue(ctx, entries);
          JSValue hasNextFn = bridgeFindMethod(ctx, iterator, "hasNext_");
          JSValue nextFn = bridgeFindMethod(ctx, iterator, "next_");
          if (JS_IsUndefined(hasNextFn) || JS_IsUndefined(nextFn)) {
            JS_FreeValue(ctx, nextFn); JS_FreeValue(ctx, hasNextFn); JS_FreeValue(ctx, iterator);
            return result;
          }
          while (1) {
            JSValue hn = JS_Call(ctx, hasNextFn, iterator, 0, NULL);
            int h = JS_VALUE_GET_BOOL(hn);
            JS_FreeValue(ctx, hn);
            if (!h) break;
            JSValue entry = JS_Call(ctx, nextFn, iterator, 0, NULL);
            JSValue keyFn = bridgeFindMethod(ctx, entry, "get_key_");
            JSValue valueFn = bridgeFindMethod(ctx, entry, "get_value_");
            JSValue keyVal = JS_Call(ctx, keyFn, entry, 0, NULL);
            JSValue valueVal = JS_Call(ctx, valueFn, entry, 0, NULL);
            JS_FreeValue(ctx, keyFn);
            JS_FreeValue(ctx, valueFn);
            jobject jk = $keyName(env, ctx, keyVal);
            jobject jv = $valueName(env, ctx, valueVal);
            (*env)->CallObjectMethod(env, result, hc_put, jk, jv);
            (*env)->DeleteLocalRef(env, jk);
            (*env)->DeleteLocalRef(env, jv);
            JS_FreeValue(ctx, valueVal);
            JS_FreeValue(ctx, keyVal);
            JS_FreeValue(ctx, entry);
          }
          JS_FreeValue(ctx, nextFn);
          JS_FreeValue(ctx, hasNextFn);
          JS_FreeValue(ctx, iterator);
          return result;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    in PRIMITIVE_ARRAY_ELEMENT_TYPE -> {
      val arrayType = kotlinToCType[ktType]!!
      val info = primitiveArrayJniInfo[ktType]!!
      // Float/Double elements: JS numbers may be INT-tagged (QuickJS stores integral
      // numbers as JS_TAG_INT); reading the float64 slot then yields garbage.
      val elemRead = if (info.jsGetterTemplate == "JS_VALUE_GET_FLOAT64") {
        "elems[i] = (JS_VALUE_GET_NORM_TAG(elem) == JS_TAG_INT) ? ${info.jsGetterCast}JS_VALUE_GET_INT(elem) : ${info.jsGetterCast}JS_VALUE_GET_FLOAT64(elem);"
      } else {
        "elems[i] = ${info.jsGetterCast}${info.jsGetterTemplate}(elem);"
      }
      helpers.appendLine(
        """
        static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
          JSValue lenVal = JS_GetPropertyStr(ctx, jsVal, "length");
          jint len = JS_VALUE_GET_INT(lenVal);
          JS_FreeValue(ctx, lenVal);
          ${arrayType} arr = (*env)->${info.newArrayFn}(env, len);
          ${info.jniElementType}* elems = (*env)->${info.getElementsFn}(env, arr, NULL);
          for (jint i = 0; i < len; i++) {
            JSValue elem = JS_GetPropertyUint32(ctx, jsVal, i);
            $elemRead
            JS_FreeValue(ctx, elem);
          }
          (*env)->${info.releaseElementsFn}(env, arr, elems, 0);
          return arr;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    else -> {
      // Object: bridge_dispatch lookup
      helpers.appendLine(
        """
        static jobject $name(JNIEnv* env, JSContext* ctx, JSValue jsVal) {
          JSValue disp = JS_GetPropertyStr(ctx, jsVal, "bridge_dispatch");
          if (!JS_IsUndefined(disp)) {
            BridgeConverterFn fn = bridgeConverterFromJSValue(disp);
            JS_FreeValue(ctx, disp);
            return fn(env, ctx, jsVal);
          }
          JS_FreeValue(ctx, disp);
          return NULL;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
  }
  return name
}
// -- C bridge orchestration --

/**
 * Generate all per-class C bridge files (each self-registers via constructor) for the union of
 * @WithJS2HostBridge and @WithHost2JSBridge classes. Value classes get no host2js JNI impl on
 * JVM (their `convertToJs` member is not injected), so they only contribute when js2Host.
 */
internal fun generateCBridges(
  outputDir: String,
  js2HostClasses: List<IrClass>,
  host2JsClasses: List<IrClass>,
) {
  val js2HostSet = js2HostClasses.toSet()
  val host2JsSet = host2JsClasses.filterNot { isInlineClass(it) }.toSet()
  for (clazz in (js2HostClasses + host2JsClasses).distinct()) {
    // Interfaces can't be instantiated or dispatched (the native generator excludes them too);
    // generating a bridge for one would fail _init with NoSuchMethodError on the constructor.
    if (clazz.kind == ClassKind.INTERFACE) continue
    generateBridgeFile(outputDir, clazz, js2Host = clazz in js2HostSet, host2Js = clazz in host2JsSet)
  }
}

/**
 * Writes the fully-qualified names of the backing FIELDS that the generated C bridge reads
 * by a `_1`-mangled JS name (private/value-class/override fields). The Kotlin/JS production
 * compiler minimizes these names, which breaks the bridge; the JS link must pass them via
 * `-Xir-keep` to keep the non-minified names. Emitted alongside the C files as a plain-text
 * resource so consumers can aggregate it from published artifacts.
 */
internal fun generateKeepNames(outputDir: String, annotatedClasses: List<IrClass>) {
  val names = linkedSetOf<String>()
  for (clazz in annotatedClasses) {
    if (clazz.kind == ClassKind.INTERFACE) continue
    val fqName = clazz.fqNameWhenAvailable?.asString() ?: continue
    if (clazz.kind == ClassKind.ENUM_CLASS) {
      // Enum ordinals are read by the C bridge via a hardcoded `ordinal_1` (not via extractFields).
      names += "$fqName.ordinal"
    } else {
      for (field in extractFields(clazz, includeValBodyFields = true)) {
        if (field.jsPropertyName.endsWith("_1")) {
          names += "$fqName.${field.name}"
        }
      }
    }
  }
  // stdlib fields read directly by the generated C bridge for Long/List values.
  names += "kotlin.Long.low"
  names += "kotlin.Long.high"
  names += "kotlin.collections.ArrayList.array"
  File(outputDir, KEEP_NAMES_FILE).writeText(names.joinToString("\n", postfix = "\n"))
}

// -- field extraction --

