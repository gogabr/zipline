package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrDeclarationParent
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.name.FqName
import java.io.File

// -- C file naming and JNI class name helpers --

internal const val KEEP_NAMES_FILE = "bridge-keep-names.txt"

internal fun cFunctionPrefix(fqName: FqName): String =
  fqName.asString().replace(".", "_")

internal fun cFileName(fqName: FqName): String =
  cFunctionPrefix(fqName) + ".cpp"

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
  val classPart = classSegments.joinToString("\$")

  return if (packagePart.isEmpty()) classPart else "$packagePart/$classPart"
}

// -- C/JNI bridge code generation (Android .so) --

/** Map types recognized by the C converter (mirrors the native generator). */
private val MAP_C_TYPES = setOf(
  "kotlin.collections.Map", "kotlin.collections.MutableMap",
  "kotlin.collections.HashMap", "kotlin.collections.LinkedHashMap",
)

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
  // Everything emitted for the JVM side - FindClass, JNI symbol mangling, field and method
  // signatures - needs the *internal* name; a dotted name is rejected by ART as an illegal class
  // name. The dotted form ([protoFqn]) remains the bridge key shared with the guest's registration.
  val jniClassName = targetFqn?.replace('.', '/') ?: buildJniClassName(annotatedClass)
  val jsClassName = fqName.asString()
  // Prototype lookup key: identical to the JS-side module-load registration key.
  val protoFqn = targetFqn ?: fqName.asString()

  // The host2js JNI impl is emitted for non-value classes only (JVM value classes are converted
  // through their underlying value, and carry no member); classes with kotlin.Function*-typed
  // fields cannot express those fields as JS values and are skipped by both backends.
  val host2JsImpl = host2Js && !isInlineClass(annotatedClass) &&
    !fields.any { it.isObjectType && it.ktType.startsWith("kotlin.Function") }
  val host2JsFields = if (host2JsImpl && annotatedClass.kind != ClassKind.ENUM_CLASS) fields else emptyList()
  val host2JsUnboxFields = if (host2JsImpl) fields.filter { it.isInline && it.isNullable } else emptyList()
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
  val instanceSig = if (isObject) "L$jniClassName;" else ""

  val cSource = buildString {
    appendLine("#include <jni.h>")
    appendLine("#include <jsi/jsi.h>")
    appendLine("#include <cmath>")
    appendLine("#include <cstdlib>")
    appendLine("#include <string>")
    appendLine("#include <cstring>")
    appendLine("#include \"bridge_dispatch.h\"")
    appendLine("namespace jsi = facebook::jsi;")
    appendLine()
    // Extern declarations for nullable inline class field helpers.
    val nullableInlineFields = fields.filter { it.isInline && it.isNullable && !isKnownType(it.ktType) }
    if (nullableInlineFields.isNotEmpty()) {
      appendLine("// Inline class _fromValue helpers (used for nullable inline class fields)")
      for (f in nullableInlineFields.distinctBy { it.ktType }) {
        val inlinePrefix = cFunctionPrefix(FqName(f.ktType))
        appendLine("extern void ${inlinePrefix}_init(JNIEnv *env);")
        appendLine("extern jobject ${inlinePrefix}_fromValue(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal);")
      }
      appendLine()
    }

    // -- cached JNI references (initialized once by _init, used by _toJavaObject) --
    appendLine("static jclass _cls = NULL;")
    if (!isObject && !isEnum) appendLine("static jmethodID _ctor = NULL;")
    if (isEnum) appendLine("static jmethodID _valuesMethod = NULL;")
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
    if (host2JsImpl) {
      // Host2JS: cache a field ID for every field (constructor params included), unbox-impl for
      // nullable inline fields, and the enum ordinal method.
      for (f in host2JsFields) {
        appendLine("static jfieldID _h2j_fld_${f.name} = NULL;")
      }
      for (f in host2JsUnboxFields) {
        appendLine("static jmethodID _h2j_unbox_${f.name} = NULL;")
      }
    }
    appendLine()

    // -- _init: cache JNI references (called once from main thread via init_all) --
    appendLine("void ${functionPrefix}_init(JNIEnv *env) {")
    appendLine("    if (_cls != NULL) return;")
    appendLine("    jclass local = env->FindClass(\"$jniClassName\");")
    appendLine("    if (env->ExceptionCheck()) {")
    appendLine("#ifdef __ANDROID__")
    appendLine("        __android_log_print(ANDROID_LOG_WARN, \"BRIDGE\", \"_init FAILED: FindClass for $jniClassName\");")
    appendLine("#endif")
    appendLine("        // Leave the pending exception pending: it propagates to the JVM and crashes.")
    appendLine("        return;")
    appendLine("    }")
    appendLine("    _cls = (jclass)env->NewGlobalRef(local);")
    if (!isObject && !isEnum) {
      appendLine("    _ctor = env->GetMethodID(_cls, \"<init>\", \"$constructorSig\");")
      appendLine("    if (env->ExceptionCheck()) {")
      appendLine("        // Let the pending NoSuchMethodError propagate instead of clearing it.")
      appendLine("        _ctor = NULL;")
      appendLine("    }")
    }
    if (isEnum) {
      appendLine("    _valuesMethod = env->GetStaticMethodID(_cls, \"values\", \"()[L$jniClassName;\");")
      appendLine("    if (env->ExceptionCheck()) {")
      appendLine("        // Let the pending NoSuchMethodError propagate instead of clearing it.")
      appendLine("        _valuesMethod = NULL;")
      appendLine("    }")
    }
    if (isCompanion) {
      val outerJni = buildJniClassName(annotatedClass.parent as IrClass)
      appendLine("    {")
      appendLine("        jclass outerLocal = env->FindClass(\"$outerJni\");")
      appendLine("        if (env->ExceptionCheck()) { /* pending exception propagates */ return; }")
      appendLine("        _outerCls = (jclass)env->NewGlobalRef(outerLocal);")
      appendLine("        _companionField = env->GetStaticFieldID(_outerCls, \"Companion\", \"$instanceSig\");")
      appendLine("    }")
    } else if (isObject) {
      appendLine("    _instField = env->GetStaticFieldID(_cls, \"INSTANCE\", \"$instanceSig\");")
    }
    for (f in nullablePrimitiveFields) {
      val info = boxedPrimitiveInfo[f.ktType]!!
      appendLine("    {")
      appendLine("        jclass boxed = env->FindClass(\"${info.wrapperClass}\");")
      appendLine("        _boxed_${f.name} = (jclass)env->NewGlobalRef(boxed);")
      appendLine("        _boxedCtor_${f.name} = env->GetMethodID(_boxed_${f.name}, \"<init>\", \"${info.ctorSig}\");")
      appendLine("    }")
    }
    if (hasAnyField || hasCollectionField) {
      appendLine("    // Init boxed type refs for Any? value dispatch")
      appendLine("    if (_any_boxed_Integer_cls == NULL) {")
      appendLine("        jclass intLocal = env->FindClass(\"java/lang/Integer\");")
      appendLine("        _any_boxed_Integer_cls = (jclass)env->NewGlobalRef(intLocal);")
      appendLine("        _any_boxed_Integer_ctor = env->GetMethodID(_any_boxed_Integer_cls, \"<init>\", \"(I)V\");")
      appendLine("    }")
      appendLine("    if (_any_boxed_Double_cls == NULL) {")
      appendLine("        jclass dblLocal = env->FindClass(\"java/lang/Double\");")
      appendLine("        _any_boxed_Double_cls = (jclass)env->NewGlobalRef(dblLocal);")
      appendLine("        _any_boxed_Double_ctor = env->GetMethodID(_any_boxed_Double_cls, \"<init>\", \"(D)V\");")
      appendLine("    }")
      appendLine("    if (_any_boxed_Boolean_cls == NULL) {")
      appendLine("        jclass boolLocal = env->FindClass(\"java/lang/Boolean\");")
      appendLine("        _any_boxed_Boolean_cls = (jclass)env->NewGlobalRef(boolLocal);")
      appendLine("        _any_boxed_Boolean_ctor = env->GetMethodID(_any_boxed_Boolean_cls, \"<init>\", \"(Z)V\");")
      appendLine("    }")
      appendLine("    if (_any_boxed_Float_cls == NULL) {")
      appendLine("        jclass fltLocal = env->FindClass(\"java/lang/Float\");")
      appendLine("        _any_boxed_Float_cls = (jclass)env->NewGlobalRef(fltLocal);")
      appendLine("        _any_boxed_Float_ctor = env->GetMethodID(_any_boxed_Float_cls, \"<init>\", \"(F)V\");")
      appendLine("    }")
    }
    if (!isEnum) {
      for (f in bodyFields) {
        appendLine("    _fld_${f.name} = env->GetFieldID(_cls, \"${f.name}\", \"${f.jniFieldType}\");")
        appendLine("    if (env->ExceptionCheck()) {")
        appendLine("        // Let the pending NoSuchFieldError propagate instead of clearing it.")
        appendLine("        _fld_${f.name} = NULL;")
        appendLine("    }")
      }
    }
    if (host2JsImpl) {
      for (f in host2JsFields) {
        appendLine("    _h2j_fld_${f.name} = env->GetFieldID(_cls, \"${f.name}\", \"${f.jniFieldType}\");")
        appendLine("    if (env->ExceptionCheck()) {")
        appendLine("        // Let the pending NoSuchFieldError propagate instead of clearing it.")
        appendLine("        _h2j_fld_${f.name} = NULL;")
        appendLine("    }")
      }
      for (f in host2JsUnboxFields) {
        val underlying = f.underlyingKtType
        val unboxSig = when {
          underlying != null && isKnownType(underlying) && isJniPrimitive(underlying) ->
            "()" + kotlinToJniFieldType[underlying]
          underlying == "kotlin.String" -> "()Ljava/lang/String;"
          else -> "()Ljava/lang/Object;"
        }
        // unbox-impl lives on the INLINE class, not the holder (the field's JVM type is the boxed
        // inline class), and FindClass takes an internal name, never a JNI descriptor.
        val inlineJni = f.irClass?.let { buildJniClassName(it) } ?: f.ktType.replace('.', '/')
        appendLine("    {")
        appendLine("        jclass _inlineCls = env->FindClass(\"$inlineJni\");")
        appendLine("        if (env->ExceptionCheck()) {")
        appendLine("            // Let the pending ClassNotFoundException propagate instead of clearing it.")
        appendLine("            _h2j_unbox_${f.name} = NULL;")
        appendLine("        } else {")
        appendLine("            _h2j_unbox_${f.name} = env->GetMethodID(_inlineCls, \"unbox-impl\", \"$unboxSig\");")
        appendLine("            if (env->ExceptionCheck()) {")
        appendLine("                // Let the pending NoSuchMethodError propagate instead of clearing it.")
        appendLine("                _h2j_unbox_${f.name} = NULL;")
        appendLine("            }")
        appendLine("            env->DeleteLocalRef(_inlineCls);")
        appendLine("        }")
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
        if (kt == "kotlin.collections.List" || kt == "kotlin.collections.MutableList" || kt in MAP_C_TYPES || kt == "kotlin.Array" || kt in PRIMITIVE_ARRAY_ELEMENT_TYPE) {
          emitCValueConverter(helpers, "conv_${field.name}", kt, field.type)
        }
      }
      if (helpers.isNotEmpty()) {
        append(helpers)
        appendLine()
      }

      appendLine("static jobject ${functionPrefix}_toJavaObject(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsObj) {")
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
        // Enum - the guest reports the ordinal through its value ops: Kotlin/JS mangles the ordinal
        // field's name and production builds drop it, so a host-side field read would only work in
        // development. A value that is not an enum instance yields -1 and fails loudly below.
        appendLine("    jint ordinal = jsiBridgeEnumOrdinal(env, rt, jsObj);")
        appendLine("    if (env->ExceptionCheck()) return NULL;")
        appendLine("    if (ordinal < 0) {")
        appendLine("        env->ThrowNew(env->FindClass(\"java/lang/IllegalStateException\"),")
        appendLine("            \"host bridge: the guest value for $jsClassName is not an enum instance\");")
        appendLine("        return NULL;")
        appendLine("    }")
        appendLine("    jobjectArray values = (jobjectArray)env->CallStaticObjectMethod(_cls, _valuesMethod);")
        appendLine("    if (env->ExceptionCheck()) return NULL;")
        appendLine("    // Out-of-range index here would abort the VM inside GetObjectArrayElement.")
        appendLine("    if (ordinal >= env->GetArrayLength(values)) {")
        appendLine("        std::string _msg = \"host bridge: ordinal \" + std::to_string(ordinal) +")
        appendLine("            \" is out of range for $jsClassName\";")
        appendLine("        env->ThrowNew(env->FindClass(\"java/lang/IllegalStateException\"), _msg.c_str());")
        appendLine("        return NULL;")
        appendLine("    }")
        appendLine("    jobject result = env->GetObjectArrayElement(values, ordinal);")
        appendLine("    if (env->ExceptionCheck()) return NULL;")
        appendLine("    return result;")
        appendLine("}")
      } else {
        if (fields.isNotEmpty()) {
          appendLine("    // Extract field values from JS object")
        }
  
        // Extract each field from the JS object
        for (field in fields) {
          val nullablePrimitive = field.isNullable && isKnownType(field.ktType) && isJniPrimitive(field.ktType)
          // Non-nullable inline value classes are erased to their underlying JNI primitive on JVM,
          // so dispatch on effectiveKtType (unwrapped) for the primitive branches.
          val isCollectionField = field.isArray ||
            field.effectiveKtType == "kotlin.collections.List" ||
            field.effectiveKtType == "kotlin.collections.MutableList" ||
            field.effectiveKtType in MAP_C_TYPES
          val cType = if (nullablePrimitive || isCollectionField) "jobject"
            else kotlinToCType[field.effectiveKtType] ?: "jobject"
          val javaVar = "java_${field.name}"
  
          appendLine("    jsi::Value js_${field.name} = jsObj.asObject(rt).getProperty(rt, \"${field.jsPropertyName}\");")
          // The guest bundle and this host are deployed independently: a guest that predates a
          // @HostName rename still carries only the old name, and the current one reads undefined.
          if (field.legacyJsName != null) {
            appendLine("    if (js_${field.name}.isUndefined()) {")
            appendLine("        js_${field.name} = jsObj.asObject(rt).getProperty(rt, \"${field.legacyJsName}\");")
            appendLine("    }")
          }
          // For Long-backed inline classes (e.g. Color), the JS object may be unboxed —
          // *jsObj IS the Long {low_1, high_1} with no .value wrapper. If .value is
          // undefined, fall back to using the object directly as the Long representation.
          if (field.ktType == "kotlin.Long" && isInlineClass(annotatedClass)) {
            appendLine("    if (js_${field.name}.isUndefined()) {")
            appendLine("        js_${field.name} = jsi::Value(rt, jsObj);")
            appendLine("    }")
          }
          // Inline value classes (e.g. @JvmInline value class Id(val value: Int))
          // may be boxed ({value: ...}) or unboxed (plain int) in Kotlin/JS depending
          // on context. We check: if it's an object, unwrap .value; otherwise use as-is.
          // EXCEPTION: Long-backed inline classes (e.g. Color) — Long is already a JS
          // object {low_1, high_1} in Kotlin/JS, so the object IS the value, not a wrapper.
          if (field.isInline && field.underlyingKtType != "kotlin.Long") {
            appendLine("    if (js_${field.name}.isObject()) {")
            appendLine("        js_${field.name} = js_${field.name}.asObject(rt).getProperty(rt, \"value\");")
            appendLine("    }")
          }
          appendLine("    $cType $javaVar;")
          appendLine("    {")
  
          // Open null check for nullable fields
          if (field.isNullable) {
            appendLine("        if (!js_${field.name}.isUndefined() && !js_${field.name}.isNull()) {")
          }
  
          when {
            field.isNullable && isKnownType(field.ktType) && isJniPrimitive(field.ktType) -> {
              emitNullablePrimitiveExtraction(this, field)
            }
            field.effectiveKtType == "kotlin.Boolean" -> {
              appendLine("        $javaVar = (jboolean)js_${field.name}.asBool();")
            }
            field.effectiveKtType == "kotlin.Byte" -> {
              appendLine("        $javaVar = (jbyte)jsi_value_get_int(js_${field.name});")
            }
            field.effectiveKtType == "kotlin.Short" -> {
              appendLine("        $javaVar = (jshort)jsi_value_get_int(js_${field.name});")
            }
            field.effectiveKtType == "kotlin.Int" -> {
              appendLine("        $javaVar = (jint)jsi_value_get_int(js_${field.name});")
            }
            field.effectiveKtType == "kotlin.Long" -> {
              appendLine("        int tag_${field.name} = jsi_value_tag(rt, js_${field.name});")
              appendLine("        if (tag_${field.name} == JS_TAG_FLOAT64) {")
              appendLine("            $javaVar = (jlong)jsi_value_get_float64(js_${field.name});")
              appendLine("        } else if (tag_${field.name} == JS_TAG_INT) {")
              appendLine("            $javaVar = (jlong)jsi_value_get_int(js_${field.name});")
              appendLine("        } else if (tag_${field.name} == JS_TAG_OBJECT) {")
              appendLine("            /* Boxed kotlin.Long: the guest reports its 32-bit halves (mangled fields). */")
              appendLine("            $javaVar = jsiBridgeLongValue(env, rt, js_${field.name});")
              appendLine("            if (env->ExceptionCheck()) return NULL;")
              appendLine("        } else {")
              appendLine("#ifdef __ANDROID__")
              appendLine("            __android_log_print(ANDROID_LOG_ERROR, \"BRIDGE\", \"Long field ${field.name}: unexpected JS tag %d\", tag_${field.name});")
              appendLine("#endif")
              appendLine("            $javaVar = 0;")
              appendLine("        }")
            }
            field.effectiveKtType == "kotlin.Float" || field.effectiveKtType == "kotlin.Double" -> {
              val cast = if (field.effectiveKtType == "kotlin.Float") "(jfloat)" else "(jdouble)"
              appendLine("        int tag_${field.name}_d = jsi_value_tag(rt, js_${field.name});")
              appendLine("        if (tag_${field.name}_d == JS_TAG_FLOAT64) {")
              appendLine("            $javaVar = ${cast}jsi_value_get_float64(js_${field.name});")
              appendLine("        } else if (tag_${field.name}_d == JS_TAG_INT) {")
              appendLine("            $javaVar = ${cast}jsi_value_get_int(js_${field.name});")
              appendLine("        } else {")
              appendLine("#ifdef __ANDROID__")
              appendLine("            __android_log_print(ANDROID_LOG_ERROR, \"BRIDGE\", \"Float/Double field ${field.name}: unexpected JS tag %d\", tag_${field.name}_d);")
              appendLine("#endif")
              appendLine("            /* JS_TAG_UNDEFINED=3, JS_TAG_NULL=2 */")
              appendLine("            $javaVar = 0;")
              appendLine("        }")
            }
            field.effectiveKtType == "kotlin.Char" -> {
              appendLine("        $javaVar = (jchar)jsi_value_get_int(js_${field.name});")
            }
            field.effectiveKtType == "kotlin.String" -> {
              appendLine("        std::string str_${field.name} = js_${field.name}.asString(rt).utf8(rt);")
              appendLine("        $javaVar = env->NewStringUTF(str_${field.name}.c_str());")
            }
            field.effectiveKtType in MAP_C_TYPES ||
              field.effectiveKtType == "kotlin.collections.List" ||
              field.effectiveKtType == "kotlin.collections.MutableList" ||
              field.isArray -> {
              appendLine("        $javaVar = conv_${field.name}(env, rt, js_${field.name});")
            }
            field.effectiveKtType == "kotlin.Any" -> {
              emitAnyFieldExtraction(this, field)
            }
            field.isInline && field.isNullable -> {
              val inlineCPrefix = cFunctionPrefix(FqName(field.ktType))
              appendLine("        $javaVar = ${inlineCPrefix}_fromValue(env, rt, js_${field.name});")
            }
            field.isObjectType -> {
              appendLine("        // Look up bridge_dispatch on the sub-object to convert it; an object the host")
              appendLine("        // itself built has none, and the shared decoder handles that shape (and names")
              appendLine("        // the class when nothing can decode it).")
              appendLine("        intptr_t _bridge_ptr_${field.name} = jsi_get_bridge_dispatch(rt, js_${field.name});")
              appendLine("        if (_bridge_ptr_${field.name} == 0) {")
              appendLine("            $javaVar = jsi_value_to_boxed(env, rt, js_${field.name});")
              appendLine("            if (env->ExceptionCheck()) return NULL;")
              appendLine("        } else {")
              appendLine("            JniBridgeDispatch *disp_${field.name} = (JniBridgeDispatch *)_bridge_ptr_${field.name};")
              appendLine("            $javaVar = disp_${field.name}->toJavaObject(env, rt, js_${field.name});")
              appendLine("        }")
            }
          }
  
          // Close null check for nullable fields
          if (field.isNullable) {
            appendLine("        } else {")
            appendLine("            $javaVar = NULL;")
            appendLine("        }")
          }
  
          appendLine("    }")
          appendLine()
        }
  
        // Create instance using cached class/constructor/field refs.
        appendLine("    // Create instance using cached JNI references")
        if (isCompanion) {
          appendLine("    jobject result = env->GetStaticObjectField(_outerCls, _companionField);")
        } else if (isObject) {
          appendLine("    jobject result = env->GetStaticObjectField(_cls, _instField);")
        } else {
          if (constructorFields.isEmpty()) {
            appendLine("    jobject result = env->NewObject(_cls, _ctor);")
          } else {
            val args = constructorFields.joinToString(", ") { "java_${it.name}" }
            appendLine("    jobject result = env->NewObject(_cls, _ctor, $args);")
          }
        }
        appendLine("    if (env->ExceptionCheck()) return NULL;")
        appendLine()
  
        // Set body fields using cached field IDs
        if (!isEnum && bodyFields.isNotEmpty()) {
          appendLine("    // Set non-constructor fields")
          for (field in bodyFields) {
            val javaVar = "java_${field.name}"
            val setFn = when {
              field.isNullable && isKnownType(field.ktType) && isJniPrimitive(field.ktType) -> "SetObjectField"
              else -> kotlinToSetFieldFunction[field.effectiveKtType] ?: "SetObjectField"
            }
            appendLine("    if (_fld_${field.name} != NULL) {")
            appendLine("        env->$setFn(result, _fld_${field.name}, $javaVar);")
            appendLine("    }")
          }
          appendLine()
        }
  
        appendLine("    return result;")
        appendLine("}")
      } // end else (non-enum)

      if (isInlineClass(annotatedClass) && !isObject && constructorFields.size == 1) {
        val underlyingField = constructorFields[0]
        val underlyingCType = kotlinToCType[underlyingField.effectiveKtType] ?: "jobject"
        appendLine()
        appendLine("jobject ${functionPrefix}_fromValue(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {")
        appendLine("    ${functionPrefix}_init(env);")
        appendLine("    if (_cls == NULL || _ctor == NULL) return NULL;")
        appendLine("    $underlyingCType java_v;")
        appendLine("    {")
        appendLine("        int tag_v = jsi_value_tag(rt, jsVal);")
        appendLine("        if (tag_v == JS_TAG_FLOAT64) {")
        appendLine("            java_v = ($underlyingCType)jsi_value_get_float64(jsVal);")
        appendLine("        } else if (tag_v == JS_TAG_INT) {")
        appendLine("            java_v = ($underlyingCType)jsi_value_get_int(jsVal);")
        if (underlyingField.effectiveKtType == "kotlin.Long") {
          appendLine("        } else if (tag_v == JS_TAG_OBJECT) {")
          appendLine("            /* Boxed kotlin.Long: the guest reports its 32-bit halves (mangled fields). */")
          appendLine("            java_v = ($underlyingCType)jsiBridgeLongValue(env, rt, jsVal);")
          appendLine("            if (env->ExceptionCheck()) return NULL;")
        }
        appendLine("        } else {")
        appendLine("#ifdef __ANDROID__")
        appendLine("            __android_log_print(ANDROID_LOG_ERROR, \"BRIDGE\", \"_fromValue: unexpected JS tag %d\", tag_v);")
        appendLine("#endif")
        appendLine("            java_v = 0;")
        appendLine("        }")
        appendLine("    }")
        appendLine("    jobject result = env->NewObject(_cls, _ctor, java_v);")
        appendLine("    if (env->ExceptionCheck()) return NULL;")
        appendLine("    return result;")
        appendLine("}")
      }
    } // end if (js2Host)

    if (host2JsImpl) {
      emitHost2JsConvertToJs(
        this, host2JsJniFunctionName!!, functionPrefix, annotatedClass,
        host2JsFields, host2JsUnboxFields, protoFqn, jniClassName,
      )
    }

    appendLine()
    appendLine("// Register this class in the shared bridge dispatch table (ContextJni).")
    appendLine("__attribute__((used, constructor))")
    appendLine("void ${functionPrefix}_bridge_register(void) {")
    appendLine("    addBridgeInit(${functionPrefix}_init);")
    if (js2Host) {
      // Key must match the guest's __bridgeRegister argument (targetFqn ?: own FQN), i.e.
      // [protoFqn] - not the annotated class's own FQN. Otherwise a targetFqn-remapped class
      // registers under one key and looks itself up under another.
      appendLine("    addBridgeEntry(\"$protoFqn\", ${functionPrefix}_toJavaObject);")
    }
    appendLine("}")
  }

  val outputFile = File(outputDir, cFileName(fqName))
  outputFile.parentFile.mkdirs()
  outputFile.writeText(cSource)
}

// -- FQN-toJavaObject registration via shared bridgeTable (Context.cpp) --

private fun emitCBoxedConverter(
  helpers: StringBuilder,
  name: String,
  boxedClass: String,
  ctorSig: String,
  valueExpr: String,
) {
  helpers.appendLine(
    """
    static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
      jclass c = env->FindClass("$boxedClass");
      jmethodID m = env->GetMethodID(c, "<init>", "$ctorSig");
      return env->NewObject(c, m, $valueExpr);
    }
    """.trimIndent(),
  )
  helpers.appendLine()
}

/**
 * Emits a C static function converting a JS value of [ktType] into a jobject. Recurses for
 * nested collections. Returns the function name.
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
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          std::string s = jsVal.asString(rt).utf8(rt);
          jobject r = env->NewStringUTF(s.c_str());
          return r;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    "kotlin.Int" -> emitCBoxedConverter(helpers, name, "java/lang/Integer", "(I)V", "jsi_value_get_int(jsVal)")
    "kotlin.Float" -> emitCBoxedConverter(helpers, name, "java/lang/Float", "(F)V", "jsi_value_get_float64(jsVal)")
    "kotlin.Double" -> emitCBoxedConverter(helpers, name, "java/lang/Double", "(D)V", "jsi_value_get_float64(jsVal)")
    "kotlin.Boolean" -> emitCBoxedConverter(helpers, name, "java/lang/Boolean", "(Z)V", "jsi_value_get_bool(jsVal)")
    "kotlin.Byte" -> emitCBoxedConverter(helpers, name, "java/lang/Byte", "(B)V", "(jbyte)jsi_value_get_int(jsVal)")
    "kotlin.Short" -> emitCBoxedConverter(helpers, name, "java/lang/Short", "(S)V", "(jshort)jsi_value_get_int(jsVal)")
    "kotlin.Char" -> emitCBoxedConverter(helpers, name, "java/lang/Character", "(C)V", "(jchar)jsi_value_get_int(jsVal)")
    "kotlin.Long" -> {
      helpers.appendLine(
        """
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          return jsi_value_to_boxed(env, rt, jsVal);
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    "kotlin.Any" -> {
      helpers.appendLine(
        """
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          return jsi_value_to_boxed(env, rt, jsVal);
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
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          // JS arrays are indexed directly; other Kotlin/JS lists (EmptyList, Singletons) go
          // through the guest's accessors.
          return jsiCollectionToJava(env, rt, jsVal, BRIDGE_COLLECTION_LIST, NULL, $elementName);
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
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          jclass oc = env->FindClass("java/lang/Object");
          if (!jsVal.isObject()) return env->NewObjectArray(0, oc, NULL);
          jsi::Value lenVal = jsVal.asObject(rt).getProperty(rt, "length");
          if (!lenVal.isNumber()) return env->NewObjectArray(0, oc, NULL);
          jint len = (jint)lenVal.asNumber();
          jobjectArray result = env->NewObjectArray(len, oc, NULL);
          for (jint i = 0; i < len; i++) {
            jsi::Value elem = jsVal.asObject(rt).getProperty(rt, std::to_string(i).c_str());
            jobject je = $elementName(env, rt, elem);
            env->SetObjectArrayElement(result, i, je);
            if (je) env->DeleteLocalRef(je);
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
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          // The guest identifies the collection and drives the iteration: Kotlin/JS mangles the
          // stdlib member names (and production builds drop them), so a host-side walk of a
          // Kotlin/JS Map works in development and yields an empty map in production.
          return jsiCollectionToJava(env, rt, jsVal, BRIDGE_COLLECTION_MAP, $keyName, $valueName);
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    in PRIMITIVE_ARRAY_ELEMENT_TYPE -> {
      val arrayType = kotlinToCType[ktType]!!
      val info = primitiveArrayJniInfo[ktType]!!
      helpers.appendLine(
        """
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          if (!jsVal.isObject()) return env->${info.newArrayFn}(0);
          jsi::Value lenVal = jsVal.asObject(rt).getProperty(rt, "length");
          if (!lenVal.isNumber()) return env->${info.newArrayFn}(0);
          jint len = (jint)lenVal.asNumber();
          $arrayType arr = env->${info.newArrayFn}(len);
          ${info.jniElementType}* elems = env->${info.getElementsFn}(arr, NULL);
          for (jint i = 0; i < len; i++) {
            jsi::Value elem = jsVal.asObject(rt).getProperty(rt, std::to_string(i).c_str());
            elems[i] = ${info.jsGetterCast}${info.jsGetterTemplate}(elem);
          }
          env->${info.releaseElementsFn}(arr, elems, 0);
          return arr;
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
    else -> {
      helpers.appendLine(
        """
        static jobject $name(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal) {
          intptr_t ptr = jsi_get_bridge_dispatch(rt, jsVal);
          if (ptr != 0) {
            JniBridgeDispatch *disp = (JniBridgeDispatch*)ptr;
            return disp->toJavaObject(env, rt, jsVal);
          }
          return jsi_value_to_boxed(env, rt, jsVal);
        }
        """.trimIndent(),
      )
      helpers.appendLine()
    }
  }
  return name
}

/** Emit boxing extraction for a nullable primitive field (e.g. Int? → Integer). */
internal fun emitNullablePrimitiveExtraction(
  sb: StringBuilder,
  field: FieldInfo,
) {
  val info = boxedPrimitiveInfo[field.ktType]!!
  val javaVar = "java_${field.name}"

  if (field.ktType == "kotlin.Long") {
    // Long needs tag-based extraction
    sb.appendLine("            int tag_${field.name} = jsi_value_tag(rt, js_${field.name});")
    sb.appendLine("            jlong longVal;")
    sb.appendLine("            if (tag_${field.name} == JS_TAG_FLOAT64) {")
    sb.appendLine("                longVal = (jlong)jsi_value_get_float64(js_${field.name});")
    sb.appendLine("            } else {")
    sb.appendLine("                longVal = (jlong)jsi_value_get_int(js_${field.name});")
    sb.appendLine("            }")
    sb.appendLine("            $javaVar = env->NewObject(_boxed_${field.name}, _boxedCtor_${field.name}, longVal);")
  } else {
    sb.appendLine(
      "            $javaVar = env->NewObject(_boxed_${field.name}, _boxedCtor_${field.name}, ${info.jsCast}${info.jsGetter}(js_${field.name}));"
    )
  }
}

// -- Any? field extraction: delegate to jsi_value_to_boxed --

internal fun emitAnyFieldExtraction(
  sb: StringBuilder,
  field: FieldInfo,
) {
  val javaVar = "java_${field.name}"
  sb.appendLine("        // Any? field — delegate to jsi_value_to_boxed")
  sb.appendLine("        $javaVar = jsi_value_to_boxed(env, rt, js_${field.name});")
}

// -- C bridge orchestration --

/** Generate all per-class C bridge files (each self-registers via constructor). */
internal fun generateCBridges(
  outputDir: String,
  js2HostClasses: List<IrClass>,
  host2JsClasses: List<IrClass>,
) {
  val js2HostSet = js2HostClasses.toSet()
  val host2JsSet = host2JsClasses.toSet()
  for (clazz in (js2HostClasses + host2JsClasses).distinct()) {
    // Interfaces have no JS constructor to export; skip them.
    if (clazz.kind == ClassKind.INTERFACE) continue
    generateBridgeFile(
      outputDir, clazz,
      js2Host = clazz in js2HostSet,
      host2Js = clazz in host2JsSet,
    )
  }
}

/**
 * Emit the JNI implementation of the IR-injected `convertToJs(J)J` member: build a new JS
 * instance carrying the guest class's own prototype (via jsiHost2JsNewObject) and define every
 * field as an own data property, converting values recursively through the shared host->JS
 * helpers.
 *
 * The returned long is a pointer to a heap `jsi::Value`; `jsiHost2JsAnyToJs` takes ownership and
 * deletes it. Errors are loud: a pending Java exception is left pending and 0 is returned, which
 * the caller turns into the pending exception (never a silent `undefined` payload field).
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
  // extern "C": JNI resolves a native method by its unmangled symbol, and this file is C++.
  sb.appendLine("extern \"C\" JNIEXPORT jlong JNICALL $jniFunctionName(JNIEnv *env, jobject self, jlong rtPtr) {")
  sb.appendLine("    ${functionPrefix}_init(env);")
  sb.appendLine("    if (_cls == NULL) {")
  sb.appendLine("        env->ThrowNew(env->FindClass(\"java/lang/IllegalStateException\"), \"host2js: _init failed for $jniClassName\");")
  sb.appendLine("        return 0;")
  sb.appendLine("    }")
  sb.appendLine("    jsi::Runtime &rt = *reinterpret_cast<jsi::Runtime *>(rtPtr);")
  sb.appendLine("    // A JS exception escaping into JNI is undefined behaviour, so everything below is")
  sb.appendLine("    // guarded and reported as a Java exception.")
  sb.appendLine("    try {")
  if (isEnum) {
    // An enum has no host->JS representation: the guest's instances are the singletons from its
    // own values(), so an object built here would only *look* like one - its ordinal would be a
    // property the guest never reads, and identity/`when` comparisons against the real constants
    // would fail. Refuse instead of shipping a lookalike (see host2JsRefuseEnum on Kotlin/Native).
    sb.appendLine("    env->ThrowNew(env->FindClass(\"java/lang/IllegalStateException\"),")
    sb.appendLine("        \"host2js: $protoFqn is an enum; the guest would only see a lookalike, not an enum instance\");")
    sb.appendLine("    return 0;")
  } else {
    sb.appendLine("    // New instance whose prototype is the EXISTING retained guest prototype for \"$protoFqn\".")
    sb.appendLine("    jsi::Value result = jsiHost2JsNewObject(env, rt, \"$protoFqn\");")
    sb.appendLine("    if (env->ExceptionCheck()) return 0;")
    for (field in fields) {
      emitHost2JsField(sb, field)
    }
    sb.appendLine("    return static_cast<jlong>(reinterpret_cast<intptr_t>(new jsi::Value(std::move(result))));")
  }
  sb.appendLine("    } catch (const jsi::JSError &e) {")
  sb.appendLine("        env->ThrowNew(env->FindClass(\"java/lang/IllegalStateException\"), e.getMessage().c_str());")
  sb.appendLine("        return 0;")
  sb.appendLine("    } catch (const std::exception &e) {")
  sb.appendLine("        env->ThrowNew(env->FindClass(\"java/lang/IllegalStateException\"), e.what());")
  sb.appendLine("        return 0;")
  sb.appendLine("    }")
  sb.appendLine("}")
}

/** Emit the field conversion for one field inside a generated convertToJs body. */
private fun emitHost2JsField(sb: StringBuilder, field: FieldInfo) {
  val jsName = field.jsPropertyName
  val fieldName = field.name
  val effective = field.effectiveKtType

  // A missing cached id means _init could not resolve the field (its pending exception was
  // reported then); dereferencing NULL here would abort the VM.
  sb.appendLine("    if (_h2j_fld_$fieldName == NULL) {")
  sb.appendLine("        env->ThrowNew(env->FindClass(\"java/lang/IllegalStateException\"), \"host2js: no cached field id for $fieldName\");")
  sb.appendLine("        return 0;")
  sb.appendLine("    }")

  // Define the pre-rename name as an accessor beside the current one, so guest code that predates
  // the rename keeps reading the payload field it knows. Defined before the value branches (whose
  // arms return early) and once per field.
  field.legacyJsName?.let {
    sb.appendLine("    jsiHost2JsDefineFieldAlias(rt, result, \"$it\", \"$jsName\");")
  }

  val nonNullablePrimitive = !field.isNullable && isKnownType(effective) && isJniPrimitive(effective)
  if (nonNullablePrimitive) {
    if (effective == "kotlin.Long") {
      sb.appendLine("    {")
      sb.appendLine("        jsi::Value _v = jsiHost2JsLongToJs(env, rt, env->GetLongField(self, _h2j_fld_$fieldName));")
      sb.appendLine("        if (env->ExceptionCheck()) return 0;")
      sb.appendLine("        jsiHost2JsDefineProperty(rt, result, \"$jsName\", std::move(_v));")
      sb.appendLine("    }")
    } else {
      val getField = when (effective) {
        "kotlin.Boolean" -> "GetBooleanField"
        "kotlin.Byte" -> "GetByteField"
        "kotlin.Char" -> "GetCharField"
        "kotlin.Short" -> "GetShortField"
        "kotlin.Float" -> "GetFloatField"
        "kotlin.Double" -> "GetDoubleField"
        else -> "GetIntField"
      }
      val build = when (effective) {
        "kotlin.Boolean" -> "jsi::Value(env->$getField(self, _h2j_fld_$fieldName) == JNI_TRUE)"
        else -> "jsi::Value(static_cast<double>(env->$getField(self, _h2j_fld_$fieldName)))"
      }
      sb.appendLine("    jsiHost2JsDefineProperty(rt, result, \"$jsName\", $build);")
    }
    return
  }

  if (field.isInline && field.isNullable) {
    // Nullable inline class: a boxed holder field; unbox through the cached unbox-impl.
    sb.appendLine("    {")
    sb.appendLine("        jobject b = env->GetObjectField(self, _h2j_fld_$fieldName);")
    sb.appendLine("        if (b == NULL) {")
    sb.appendLine("            jsiHost2JsDefineProperty(rt, result, \"$jsName\", jsi::Value::null());")
    sb.appendLine("        } else {")
    val underlying = field.underlyingKtType
    when {
      underlying == "kotlin.Long" -> {
        sb.appendLine("            jlong u = env->CallLongMethod(b, _h2j_unbox_$fieldName);")
        sb.appendLine("            if (env->ExceptionCheck()) { env->DeleteLocalRef(b); return 0; }")
        sb.appendLine("            jsi::Value _v = jsiHost2JsLongToJs(env, rt, u);")
        sb.appendLine("            if (env->ExceptionCheck()) { env->DeleteLocalRef(b); return 0; }")
        sb.appendLine("            jsiHost2JsDefineProperty(rt, result, \"$jsName\", std::move(_v));")
      }
      underlying != null && isKnownType(underlying) && isJniPrimitive(underlying) -> {
        val call = when (underlying) {
          "kotlin.Boolean" -> "CallBooleanMethod"
          "kotlin.Float" -> "CallFloatMethod"
          "kotlin.Double" -> "CallDoubleMethod"
          "kotlin.Char" -> "CallCharMethod"
          "kotlin.Short" -> "CallShortMethod"
          "kotlin.Byte" -> "CallByteMethod"
          else -> "CallIntMethod"
        }
        val cType = when (underlying) {
          "kotlin.Boolean" -> "jboolean"
          "kotlin.Float" -> "jfloat"
          "kotlin.Double" -> "jdouble"
          "kotlin.Char" -> "jchar"
          "kotlin.Short" -> "jshort"
          "kotlin.Byte" -> "jbyte"
          else -> "jint"
        }
        val build = when (underlying) {
          "kotlin.Boolean" -> "jsi::Value(u == JNI_TRUE)"
          else -> "jsi::Value(static_cast<double>(u))"
        }
        sb.appendLine("            $cType u = env->$call(b, _h2j_unbox_$fieldName);")
        sb.appendLine("            if (env->ExceptionCheck()) { env->DeleteLocalRef(b); return 0; }")
        sb.appendLine("            jsiHost2JsDefineProperty(rt, result, \"$jsName\", $build);")
      }
      else -> {
        // Reference-backed value class: unbox to the underlying reference and convert it.
        sb.appendLine("            jobject u = env->CallObjectMethod(b, _h2j_unbox_$fieldName);")
        sb.appendLine("            if (env->ExceptionCheck()) { env->DeleteLocalRef(b); return 0; }")
        sb.appendLine("            jsi::Value _v = jsiHost2JsAnyToJs(env, rt, u);")
        sb.appendLine("            if (u) env->DeleteLocalRef(u);")
        sb.appendLine("            if (env->ExceptionCheck()) { env->DeleteLocalRef(b); return 0; }")
        sb.appendLine("            jsiHost2JsDefineProperty(rt, result, \"$jsName\", std::move(_v));")
      }
    }
    sb.appendLine("            env->DeleteLocalRef(b);")
    sb.appendLine("        }")
    sb.appendLine("    }")
    return
  }

  // Everything else: nullable primitives, String, Any, collections, arrays, object fields.
  sb.appendLine("    {")
  sb.appendLine("        jobject f = env->GetObjectField(self, _h2j_fld_$fieldName);")
  sb.appendLine("        if (f == NULL) {")
  sb.appendLine("            jsiHost2JsDefineProperty(rt, result, \"$jsName\", jsi::Value::null());")
  sb.appendLine("        } else {")
  sb.appendLine("            jsi::Value _v = jsiHost2JsAnyToJs(env, rt, f);")
  sb.appendLine("            env->DeleteLocalRef(f);")
  sb.appendLine("            if (env->ExceptionCheck()) return 0;")
  sb.appendLine("            jsiHost2JsDefineProperty(rt, result, \"$jsName\", std::move(_v));")
  sb.appendLine("        }")
  sb.appendLine("    }")
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
    // Enums need no kept name: the guest reports the ordinal through its value ops, and an enum
    // never crosses host -> JS (see CGenerator.emitHost2JsConvertToJs).
    if (clazz.kind == ClassKind.ENUM_CLASS) continue
    for (field in extractFields(clazz, includeValBodyFields = true)) {
      if (field.jsPropertyName.endsWith("_1")) {
        names += "$fqName.${field.name}"
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

