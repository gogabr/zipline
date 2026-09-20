package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrTypeParameter
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.classFqName
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable

// -- field extraction data class --

/**
 * Carries the raw IR type plus lazily-computed type metadata for a single bridged field.
 * The heavy lifting (inline unwrapping, wrapper chains, array element descriptors) is
 * computed on demand from the IR type.
 */
data class FieldInfo(
  val name: String,
  val type: IrType,
  val isConstructorParam: Boolean,
  val jsPropertyName: String,
  /**
   * The JS property name this field carried before `@HostName` renamed it; null when it was never
   * renamed (or the annotated name is its current name). The host readers fall back to it and the
   * host writers define it as an accessor alias beside [jsPropertyName].
   */
  val legacyJsName: String? = null,
) {
  val ktType: String by lazy {
    // Type parameters (T, U, …) have no classFqName: use the upper bound (or Any when unbounded)
    // so bounded generics like BridgedBoundedGenericClass<T : Base> can infer T.
    val direct = type.classFqName?.asString()
    val tp = (type as? IrSimpleType)?.classifier?.owner as? IrTypeParameter
    val bound = tp?.superTypes?.firstOrNull()?.classFqName?.asString()
    direct ?: bound ?: "kotlin.Any"
  }

  val isNullable: Boolean by lazy { (type as? IrSimpleType)?.isMarkedNullable() ?: false }

  val irClass: IrClass? by lazy { (type as? IrSimpleType)?.getClass() }

  val isInline: Boolean by lazy { irClass?.let { isInlineClass(it) } ?: false }

  /** Recursively unwrapped primitive underlying type of an inline value class. */
  val underlyingKtType: String? by lazy {
    if (isInline) unwrapInlineUnderlying(irClass) else null
  }

  /**
   * Wrapper chain of an inline field from outermost to innermost, e.g. for
   * value class Outer(val inner: Inner) where Inner is a value class: [Outer, Inner].
   * Used to reconstruct the nested wrappers around the primitive value.
   */
  val inlineWrapperChain: List<String> by lazy {
    if (isInline) {
      val chain = mutableListOf<String>()
      var clazz = irClass
      while (clazz != null) {
        val fqn = clazz.fqNameWhenAvailable?.asString() ?: break
        chain.add(fqn)
        clazz = inlineUnderlyingClass(clazz)?.takeIf { isInlineClass(it) }
      }
      chain
    } else {
      emptyList()
    }
  }

  val wrapperKtType: String? by lazy { if (isInline) ktType else null }

  val effectiveKtType: String by lazy {
    val underlying = underlyingKtType
    if (isInline && !isNullable && underlying != null && isKnownType(underlying)) underlying else ktType
  }

  val jniFieldType: String by lazy {
    val typeStr = effectiveKtType
    when {
      isKnownType(typeStr) -> {
        // Nullable primitives are stored as their boxed JNI type (e.g. Integer for Int?).
        if (isNullable && isJniPrimitive(typeStr)) {
          boxedJniDescriptor[typeStr] ?: kotlinToJniFieldType[typeStr]!!
        } else if (typeStr == "kotlin.Array") {
          // Array<T> descriptor must carry the element type (e.g. [Ljava/lang/String;), recursively.
          "[" + elementJniDescriptor(arrayElementIrType)
        } else {
          kotlinToJniFieldType[typeStr]!!
        }
      }
      else -> irClass?.let { jniTypeDescriptorForClass(it) } ?: jniFieldDescriptor(typeStr)
    }
  }

  // Full JNI descriptor for constructor signatures; may be multi-character (e.g. "Ljava/lang/Integer;").
  val jniTypeChar: String by lazy { jniFieldType }

  val isPrimitive: Boolean by lazy {
    isKnownType(effectiveKtType) && isJniPrimitive(effectiveKtType) && !isNullable
  }

  val isObjectType: Boolean by lazy { !isKnownType(effectiveKtType) && !isArray }

  val isArray: Boolean by lazy { effectiveKtType in arrayKotlinTypes }

  /** Element type for Array<T> and List<T>; null when the generic argument is not available. */
  private val elementIrType: IrType? by lazy {
    if (isArray || effectiveKtType == "kotlin.collections.List") {
      (type as? IrSimpleType)?.arguments
        ?.firstOrNull()
        ?.let { (it as? IrTypeProjection)?.type ?: (it as? IrType) }
    } else null
  }

  /** Raw IR type of the element for Array<T>/List<T>; null when unavailable. */
  val arrayElementIrType: IrType? by lazy { elementIrType }
}

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

/** JNI reference descriptor for an array element type (boxed primitives, String, objects, nested arrays). */
internal fun elementJniDescriptor(elementType: IrType?): String {
  val kt = effectiveClassFqn(elementType)
  val elemClass = (elementType as? IrSimpleType)?.getClass()
  return when {
    kt == "kotlin.Array" -> "[" + elementJniDescriptor(typeArgument(elementType, 0))
    kt == "kotlin.String" -> "Ljava/lang/String;"
    kt in boxedJniDescriptor -> boxedJniDescriptor[kt]!!
    elemClass != null -> jniTypeDescriptorForClass(elemClass)
    else -> "Ljava/lang/Object;"
  }
}

/** The single regular primary-constructor parameter type's class of an inline class, if any. */
private fun inlineUnderlyingClass(clazz: IrClass?): IrClass? =
  clazz?.declarations
    ?.filterIsInstance<IrConstructor>()
    ?.firstOrNull { it.isPrimary }
    ?.parameters
    ?.firstOrNull { it.kind == IrParameterKind.Regular }
    ?.type
    ?.let { (it as? IrSimpleType)?.getClass() }

/** Recursively unwraps nested inline classes down to the primitive underlying FQN. */
internal fun unwrapInlineUnderlying(clazz: IrClass?): String? {
  val underlyingClass = inlineUnderlyingClass(clazz)
  return if (underlyingClass != null && isInlineClass(underlyingClass)) {
    unwrapInlineUnderlying(underlyingClass)
  } else {
    clazz?.declarations
      ?.filterIsInstance<IrConstructor>()
      ?.firstOrNull { it.isPrimary }
      ?.parameters
      ?.firstOrNull { it.kind == IrParameterKind.Regular }
      ?.type?.classFqName?.asString()
  }
}

// -- JNI info data classes --

data class PrimitiveArrayJniInfo(
  val jniElementType: String,
  val newArrayFn: String,
  val getElementsFn: String,
  val releaseElementsFn: String,
  val jsGetterTemplate: String,
  val jsGetterCast: String,
)

data class BoxedPrimitiveInfo(
  val wrapperClass: String,
  val ctorSig: String,
  val cType: String,
  val jsGetter: String,
  val jsCast: String,
)

// -- type mapping tables --

val kotlinToJniFieldType = mapOf(
  "kotlin.Boolean" to "Z", "kotlin.Byte" to "B", "kotlin.Char" to "C",
  "kotlin.Short" to "S", "kotlin.Int" to "I", "kotlin.Long" to "J",
  "kotlin.Float" to "F", "kotlin.Double" to "D",
  "kotlin.String" to "Ljava/lang/String;", "kotlin.Any" to "Ljava/lang/Object;",
  "kotlin.BooleanArray" to "[Z", "kotlin.ByteArray" to "[B",
  "kotlin.CharArray" to "[C", "kotlin.ShortArray" to "[S",
  "kotlin.IntArray" to "[I", "kotlin.LongArray" to "[J",
  "kotlin.FloatArray" to "[F", "kotlin.DoubleArray" to "[D",
  "kotlin.Array" to "[Ljava/lang/Object;",
)

val kotlinToCType = mapOf(
  "kotlin.Boolean" to "jboolean", "kotlin.Byte" to "jbyte",
  "kotlin.Char" to "jchar", "kotlin.Short" to "jshort",
  "kotlin.Int" to "jint", "kotlin.Long" to "jlong",
  "kotlin.Float" to "jfloat", "kotlin.Double" to "jdouble",
  "kotlin.String" to "jstring",
  "kotlin.BooleanArray" to "jbooleanArray", "kotlin.ByteArray" to "jbyteArray",
  "kotlin.CharArray" to "jcharArray", "kotlin.ShortArray" to "jshortArray",
  "kotlin.IntArray" to "jintArray", "kotlin.LongArray" to "jlongArray",
  "kotlin.FloatArray" to "jfloatArray", "kotlin.DoubleArray" to "jdoubleArray",
  "kotlin.Array" to "jobjectArray",
)

val kotlinToSetFieldFunction = mapOf(
  "kotlin.Boolean" to "SetBooleanField", "kotlin.Byte" to "SetByteField",
  "kotlin.Char" to "SetCharField", "kotlin.Short" to "SetShortField",
  "kotlin.Int" to "SetIntField", "kotlin.Long" to "SetLongField",
  "kotlin.Float" to "SetFloatField", "kotlin.Double" to "SetDoubleField",
  "kotlin.String" to "SetObjectField",
  "kotlin.BooleanArray" to "SetObjectField", "kotlin.ByteArray" to "SetObjectField",
  "kotlin.CharArray" to "SetObjectField", "kotlin.ShortArray" to "SetObjectField",
  "kotlin.IntArray" to "SetObjectField", "kotlin.LongArray" to "SetObjectField",
  "kotlin.FloatArray" to "SetObjectField", "kotlin.DoubleArray" to "SetObjectField",
  "kotlin.Array" to "SetObjectField",
)

val kotlinToJvmClass = mapOf(
  "kotlin.collections.List" to "java/util/List",
  "kotlin.collections.MutableList" to "java/util/List",
  "kotlin.collections.Map" to "java/util/Map",
  "kotlin.collections.MutableMap" to "java/util/Map",
  "kotlin.collections.Set" to "java/util/Set",
  "kotlin.collections.MutableSet" to "java/util/Set",
  "kotlin.collections.Collection" to "java/util/Collection",
  "kotlin.collections.MutableCollection" to "java/util/Collection",
  "kotlin.collections.Iterable" to "java/lang/Iterable",
  "kotlin.Function0" to "kotlin/jvm/functions/Function0",
  "kotlin.Function1" to "kotlin/jvm/functions/Function1",
  "kotlin.Function2" to "kotlin/jvm/functions/Function2",
  "kotlin.Function3" to "kotlin/jvm/functions/Function3",
  "kotlin.Function4" to "kotlin/jvm/functions/Function4",
  "kotlin.Function5" to "kotlin/jvm/functions/Function5",
  "kotlin.Function6" to "kotlin/jvm/functions/Function6",
)

val arrayKotlinTypes = setOf(
  "kotlin.BooleanArray", "kotlin.ByteArray", "kotlin.CharArray",
  "kotlin.ShortArray", "kotlin.IntArray", "kotlin.LongArray",
  "kotlin.FloatArray", "kotlin.DoubleArray", "kotlin.Array",
)

val primitiveArrayJniInfo = mapOf(
  "kotlin.BooleanArray" to PrimitiveArrayJniInfo("jboolean", "NewBooleanArray", "GetBooleanArrayElements", "ReleaseBooleanArrayElements", "jsi_value_get_bool", "(jboolean)"),
  "kotlin.ByteArray" to PrimitiveArrayJniInfo("jbyte", "NewByteArray", "GetByteArrayElements", "ReleaseByteArrayElements", "jsi_value_get_int", "(jbyte)"),
  "kotlin.CharArray" to PrimitiveArrayJniInfo("jchar", "NewCharArray", "GetCharArrayElements", "ReleaseCharArrayElements", "jsi_value_get_int", "(jchar)"),
  "kotlin.ShortArray" to PrimitiveArrayJniInfo("jshort", "NewShortArray", "GetShortArrayElements", "ReleaseShortArrayElements", "jsi_value_get_int", "(jshort)"),
  "kotlin.IntArray" to PrimitiveArrayJniInfo("jint", "NewIntArray", "GetIntArrayElements", "ReleaseIntArrayElements", "jsi_value_get_int", "(jint)"),
  "kotlin.LongArray" to PrimitiveArrayJniInfo("jlong", "NewLongArray", "GetLongArrayElements", "ReleaseLongArrayElements", "jsi_value_get_int", "(jlong)"),
  "kotlin.FloatArray" to PrimitiveArrayJniInfo("jfloat", "NewFloatArray", "GetFloatArrayElements", "ReleaseFloatArrayElements", "jsi_value_get_float64", "(jfloat)"),
  "kotlin.DoubleArray" to PrimitiveArrayJniInfo("jdouble", "NewDoubleArray", "GetDoubleArrayElements", "ReleaseDoubleArrayElements", "jsi_value_get_float64", "(jdouble)"),
)

val boxedPrimitiveInfo = mapOf(
  "kotlin.Int" to BoxedPrimitiveInfo("java/lang/Integer", "(I)V", "jint", "jsi_value_get_int", "(jint)"),
  "kotlin.Float" to BoxedPrimitiveInfo("java/lang/Float", "(F)V", "jfloat", "jsi_value_get_float64", "(jfloat)"),
  "kotlin.Double" to BoxedPrimitiveInfo("java/lang/Double", "(D)V", "jdouble", "jsi_value_get_float64", "(jdouble)"),
  "kotlin.Long" to BoxedPrimitiveInfo("java/lang/Long", "(J)V", "jlong", "jsi_value_get_int", "(jlong)"),
  "kotlin.Short" to BoxedPrimitiveInfo("java/lang/Short", "(S)V", "jshort", "jsi_value_get_int", "(jshort)"),
  "kotlin.Byte" to BoxedPrimitiveInfo("java/lang/Byte", "(B)V", "jbyte", "jsi_value_get_int", "(jbyte)"),
  "kotlin.Boolean" to BoxedPrimitiveInfo("java/lang/Boolean", "(Z)V", "jboolean", "jsi_value_get_bool", "(jboolean)"),
  "kotlin.Char" to BoxedPrimitiveInfo("java/lang/Character", "(C)V", "jchar", "jsi_value_get_int", "(jchar)"),
)

val boxedJniDescriptor = mapOf(
  "kotlin.Boolean" to "Ljava/lang/Boolean;", "kotlin.Byte" to "Ljava/lang/Byte;",
  "kotlin.Char" to "Ljava/lang/Character;", "kotlin.Short" to "Ljava/lang/Short;",
  "kotlin.Int" to "Ljava/lang/Integer;", "kotlin.Long" to "Ljava/lang/Long;",
  "kotlin.Float" to "Ljava/lang/Float;", "kotlin.Double" to "Ljava/lang/Double;",
)
