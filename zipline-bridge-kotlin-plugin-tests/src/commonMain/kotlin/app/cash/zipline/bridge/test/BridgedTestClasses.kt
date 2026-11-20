/*
 * Test classes for the bridge compiler plugin end-to-end tests.
 *
 * Each class exercises a distinct generator branch:
 * - BridgedData: primitive + String fields
 * - BridgedInline: @JvmInline value class wrapper
 * - BridgedListHolder: List<Int> field (regression for the array_1 double-free)
 * - BridgedNested: object field dispatched through the nested bridge
 * - BridgedNullable: nullable String and Int fields
 */
package app.cash.zipline.bridge.test

import app.cash.zipline.bridge.support.WithHost2JSBridge
import app.cash.zipline.bridge.support.WithJS2HostBridge
import kotlin.jvm.JvmInline

@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedData(
  val id: Int,
  val name: String,
  val active: Boolean,
  val ratio: Double,
)

@WithJS2HostBridge
@WithHost2JSBridge
@JvmInline
value class BridgedInline(val raw: Int)

@WithJS2HostBridge
@WithHost2JSBridge
@JvmInline
value class BridgedFloat(val raw: Float)

@WithJS2HostBridge
@WithHost2JSBridge
@JvmInline
value class BridgedDouble(val raw: Double)

/** Nested inline: a value class whose underlying type is itself a value class (like SpaceArrangement(spacing: Dp)). */
@WithJS2HostBridge
@WithHost2JSBridge
@JvmInline
value class BridgedNestedInline(val inner: BridgedDouble)

@WithJS2HostBridge
@WithHost2JSBridge
enum class BridgedEnum { FIRST, SECOND, THIRD }

@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedEnumHolder(val value: BridgedEnum)

/**
 * An enum whose entries carry data, which is what an enum looks like in real designs (a label and a
 * size the widgets read). `BridgedEnum` above has no parameters, so without this fixture nothing in the
 * suite distinguishes the two shapes.
 *
 * What the bridge does with it today: the guest's value crosses as an ordinal and the host resolves
 * `values()[ordinal]`. So the entry's fields are never read, and no constructor runs on the host's
 * behalf at all. That is *not* a proof that the crossing is correct -- see the two notes on
 * `BridgeEndToEndTest.bridgedSizedEnumHolder`. The entry arguments here are deliberately distinct from
 * each other so that a test can compare payloads across the crossing once there is something to compare.
 *
 * CLV-180 owns the behaviour; these fixtures are expected to change with it.
 */
@WithJS2HostBridge
@WithHost2JSBridge
enum class BridgedSizedEnum(val label: String, val size: Int) {
  SMALL("small", 12),
  LARGE("large", 24),
}

@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedSizedEnumHolder(val value: BridgedSizedEnum)

/**
 * Inline classes can only be bridged as fields (or list elements): Kotlin/JS inlines a
 * standalone value-class return value to the underlying primitive, which carries no
 * bridge_dispatch property.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedInlineHolder(val inlineValue: BridgedInline)

@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedFloatHolder(val floatValue: BridgedFloat)

@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedDoubleHolder(val doubleValue: BridgedDouble)

@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedNestedInlineHolder(val nested: BridgedNestedInline?)

@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedListHolder(val items: List<Int>)

/**
 * A function-typed field cannot cross the bridge - a guest lambda has no host representation - so
 * it decodes to null. Regression for the native generator dropping such classes entirely, which
 * left the host without a converter for a class the author annotated.
 */
@WithJS2HostBridge
data class BridgedCallbackHolder(
  val name: String,
  val onDone: (() -> Unit)? = null,
)

@WithJS2HostBridge
data class BridgedFloatListHolder(val items: List<Float>)

@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedNested(val outer: BridgedData)

@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedNullable(val text: String?, val count: Int?)

/**
 * Test class for arrays and lists of primitives
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedArray(
  val intArray: IntArray,
  val stringArray: Array<String>,
  val booleanArray: BooleanArray,
  val doubleArray: DoubleArray,
  val floatArray: FloatArray,
  val byteArray: ByteArray,
  val shortArray: ShortArray,
  val charArray: CharArray,
  val primitiveList: List<Int>,
  val stringList: List<String>,
)

/**
 * Test class for nested arrays/lists
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedNestedStructure(
  val nestedArray: Array<Array<Int>>,
  val nestedList: List<List<String>>,
  val mixedStructure: Array<List<IntArray>>,
)

/**
 * Test class for empty collections
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedEmptyCollections(
  val emptyArray: IntArray,
  val emptyList: List<String>,
  val emptyMap: Map<String, Int>,
)

/**
 * Test class for inheritance scenarios including overrides
 */
@WithJS2HostBridge
@WithHost2JSBridge
open class BridgedBaseClass {
  open val baseProperty: String = "base"
  val baseField: Int = 10

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is BridgedBaseClass) return false
    return baseProperty == other.baseProperty && baseField == other.baseField
  }

  override fun hashCode(): Int {
    var result = baseProperty.hashCode()
    result = 31 * result + baseField
    return result
  }
}

@WithJS2HostBridge
@WithHost2JSBridge
open class BridgedInheritanceChild : BridgedBaseClass() {
  val childField: Int = 1

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is BridgedInheritanceChild) return false
    return super.equals(other) && childField == other.childField
  }

  override fun hashCode(): Int = 31 * super.hashCode() + childField
}

@WithJS2HostBridge
@WithHost2JSBridge
class BridgedDeepInheritance : BridgedInheritanceChild() {
  val deepField: Double = 3.14

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is BridgedDeepInheritance) return false
    return super.equals(other) && deepField == other.deepField
  }

  override fun hashCode(): Int = 31 * super.hashCode() + deepField.hashCode()
}

/**
 * Test class for property overrides with backing fields
 */
@WithJS2HostBridge
@WithHost2JSBridge
open class BridgedOverrideBase {
  open val overriddenProperty: String = "base"
  open var overriddenVar: Int = 5
  val regularField: Boolean = true

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is BridgedOverrideBase) return false
    return overriddenProperty == other.overriddenProperty &&
      overriddenVar == other.overriddenVar &&
      regularField == other.regularField
  }

  override fun hashCode(): Int {
    var result = overriddenProperty.hashCode()
    result = 31 * result + overriddenVar
    result = 31 * result + regularField.hashCode()
    return result
  }
}

@WithJS2HostBridge
@WithHost2JSBridge
class BridgedOverrideChild : BridgedOverrideBase() {
  override val overriddenProperty: String = "overridden"
  override var overriddenVar: Int = 10
  val childField: String = "child"

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is BridgedOverrideChild) return false
    return super.equals(other) && childField == other.childField
  }

  override fun hashCode(): Int = 31 * super.hashCode() + childField.hashCode()
}

/**
 * Test class for interfaces and interface implementation
 */
@WithJS2HostBridge
@WithHost2JSBridge
interface BridgedInterface {
  val interfaceProperty: String
  fun interfaceMethod(): Int
}

@WithJS2HostBridge
@WithHost2JSBridge
class BridgedInterfaceImplementation : BridgedInterface {
  override val interfaceProperty: String = "implemented"
  override fun interfaceMethod(): Int = 42

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is BridgedInterfaceImplementation) return false
    return interfaceProperty == other.interfaceProperty
  }

  override fun hashCode(): Int = interfaceProperty.hashCode()
}

/**
 * Test class for generic types with type parameters
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedGenericClass<T>(
  val value: T,
  val list: List<T>,
)

/**
 * Test class for multiple type parameters
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedMultiGenericClass<T, U>(
  val first: T,
  val second: U,
  val both: Map<T, U>,
)

/**
 * Test class for bounded generics (where T : SomeClass)
 */
@WithJS2HostBridge
@WithHost2JSBridge
open class BridgedBoundedGenericBase {
  open val baseProperty: String = "base"

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is BridgedBoundedGenericBase) return false
    return baseProperty == other.baseProperty
  }

  override fun hashCode(): Int = baseProperty.hashCode()
}

@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedBoundedGenericClass<T : BridgedBoundedGenericBase>(
  val value: T,
  val list: List<T>,
)

/**
 * Test class for nested generics
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedNestedGeneric(
  val mapOfLists: Map<String, List<Int>>,
  val listOfMaps: List<Map<String, Int>>,
  val complexNested: Map<String, List<Map<Int, String>>>,
)

/**
 * Host2JS Long coverage: a Long-backed inline class, a holder with a plain Long field, and a
 * holder of the Long inline class. The Long must round-trip as a real kotlin.Long (via the
 * guest's newLong factory), including negative values (low/high wire format handles any Long).
 */
@WithHost2JSBridge
@JvmInline
value class BridgedLongInline(val raw: Long)

@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedLongHolder(val v: Long)

@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedLongInlineHolder(val inline: BridgedLongInline)

/**
 * The compose-live Color shape (io.composelive.nodes.foundation.common.Color): a Long-backed value
 * class the guest BOXES, so a `List<BridgedLongBox>` arrives as an array of instances carrying their
 * registered converter rather than as inlined Longs. `solid` covers the field read, `gradient` the
 * element read; reading a boxed instance numerically loses the payload (zero -> transparent).
 */
@WithJS2HostBridge
@WithHost2JSBridge
@JvmInline
value class BridgedLongBox(val value: Long) {
  companion object {
    val Unspecified = BridgedLongBox(Int.MAX_VALUE.toLong())
  }
}

@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedLongBoxHolder(
  val solid: BridgedLongBox,
  val gradient: List<BridgedLongBox>,
)

/**
 * Phase 0 spike types: sealed payloads shaped exactly like the compose-live event args that
 * need direct host->guest transport (BridgedImageState mirrors io.composelive AsyncImageState;
 * BridgedLottieState mirrors wb LottieAnimationLoadState, whose children are `data object`s;
 * the BridgedAnnotation* family mirrors AnnotatedStringRange/StringAnnotation/TextStyle
 * nesting: a data class child of a sealed interface whose payload contains another bridged
 * data class). Proves the plugin host2js path handles object-kind declarations, sealed-interface
 * children, and data classes nested inside sealed children.
 */

@WithJS2HostBridge
@WithHost2JSBridge
public sealed interface BridgedImageState {
  @WithJS2HostBridge
  @WithHost2JSBridge
  object Empty : BridgedImageState

  @WithJS2HostBridge
  @WithHost2JSBridge
  object Loading : BridgedImageState

  @WithJS2HostBridge
  @WithHost2JSBridge
  object Success : BridgedImageState

  @WithJS2HostBridge
  @WithHost2JSBridge
  data class Error(val message: String?) : BridgedImageState
}

@WithJS2HostBridge
@WithHost2JSBridge
public sealed interface BridgedLottieState {
  @WithJS2HostBridge
  @WithHost2JSBridge
  data object Loading : BridgedLottieState

  @WithJS2HostBridge
  @WithHost2JSBridge
  data object Success : BridgedLottieState

  @WithJS2HostBridge
  @WithHost2JSBridge
  data class Error(val message: String?) : BridgedLottieState
}

/** Data class with structured (non-primitive) fields, mirroring compose-live TextStyle reachable from StringAnnotation children. */
@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedTextStyle(
  val bold: Boolean,
  val fontSize: BridgedFloat,
  val raw: Long,
  val align: BridgedEnum?,
)

@WithJS2HostBridge
@WithHost2JSBridge
public sealed interface BridgedStringAnnotation {
  @WithJS2HostBridge
  @WithHost2JSBridge
  data class Link(val url: String, val style: BridgedTextStyle?) : BridgedStringAnnotation

  @WithJS2HostBridge
  @WithHost2JSBridge
  data class Style(val style: BridgedTextStyle) : BridgedStringAnnotation
}

/** Mirrors compose-live AnnotatedStringRange: top-level data class whose payload is a sealed-interface child value. */
@WithJS2HostBridge
@WithHost2JSBridge
data class BridgedAnnotationRange(
  val start: Int,
  val end: Int,
  val annotation: BridgedStringAnnotation,
)

/**
 * Two bridged classes that share a simple name, one at the top level and one nested inside an
 * unannotated class — the compose-live `Alignment` / `LineHeightStyle.Alignment` shape.
 *
 * Nothing may key a converter, a bridge key, a generated file or a JNI symbol on the simple name:
 * both classes are annotated, both cross the wire, and each must decode as itself.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class Alignment(val value: Int)

class LineHeightStyle {
  @WithJS2HostBridge
  @WithHost2JSBridge
  data class Alignment(val value: Int)
}

/** Canonical values used both by the guest providers and the host assertions. */
object BridgedTestValues {
  val data = BridgedData(id = 7, name = "seven", active = true, ratio = 1.5)
  val inline = BridgedInline(raw = 42)
  val inlineHolder = BridgedInlineHolder(inlineValue = inline)
  val float = BridgedFloat(raw = 1.5f)
  val floatHolder = BridgedFloatHolder(floatValue = float)
  val double = BridgedDouble(raw = 2.5)
  val doubleHolder = BridgedDoubleHolder(doubleValue = double)
  val nestedInline = BridgedNestedInline(inner = double)
  val nestedInlineHolder = BridgedNestedInlineHolder(nested = nestedInline)
  val nestedInlineHolderNull = BridgedNestedInlineHolder(nested = null)
  val enumSecond = BridgedEnum.SECOND
  val enumHolder = BridgedEnumHolder(value = enumSecond)
  val sizedLarge = BridgedSizedEnum.LARGE
  val sizedHolder = BridgedSizedEnumHolder(value = sizedLarge)
  val listHolder = BridgedListHolder(items = listOf(1, 2, 3))
  val callbackHolder = BridgedCallbackHolder(name = "done", onDone = null)
  val floatList = BridgedFloatListHolder(items = listOf(0, 1).map { it.toFloat() } + listOf(0.5f))
  val nested = BridgedNested(outer = data)
  val nullableNull = BridgedNullable(text = null, count = null)
  val nullableValue = BridgedNullable(text = "x", count = 1)

  // New test values for collections
  val array = BridgedArray(
    intArray = intArrayOf(1, 2, 3),
    stringArray = arrayOf("a", "b", "c"),
    booleanArray = booleanArrayOf(true, false),
    doubleArray = doubleArrayOf(0.0, 1.0, 2.5),
    floatArray = floatArrayOf(0f, 1.5f, 1f),
    byteArray = byteArrayOf(1, 2, 3),
    shortArray = shortArrayOf(10, 20, 30),
    charArray = charArrayOf('a', 'b', 'c'),
    primitiveList = listOf(1, 2, 3),
    stringList = listOf("a", "b", "c"),
  )

  val nestedStructure = BridgedNestedStructure(
    nestedArray = arrayOf(arrayOf(1, 2), arrayOf(3, 4)),
    nestedList = listOf(listOf("a", "b"), listOf("c", "d")),
    mixedStructure = arrayOf(listOf(intArrayOf(1, 2), intArrayOf(3, 4))),
  )

  val emptyCollections = BridgedEmptyCollections(
    emptyArray = intArrayOf(),
    emptyList = listOf(),
    emptyMap = mapOf(),
  )

  // New test values for inheritance
  val baseClass = BridgedBaseClass()
  val inheritanceChild = BridgedInheritanceChild()
  val deepInheritance = BridgedDeepInheritance()
  val overrideBase = BridgedOverrideBase()
  val overrideChild = BridgedOverrideChild()
  val interfaceImpl = BridgedInterfaceImplementation()

  // New test values for generics
  val genericInt = BridgedGenericClass(42, listOf(1, 2, 3))
  val genericString = BridgedGenericClass("hello", listOf("a", "b", "c"))
  val multiGeneric = BridgedMultiGenericClass("key", 42, mapOf("key" to 42))
  val boundedGeneric = BridgedBoundedGenericClass(BridgedBoundedGenericBase(), listOf(BridgedBoundedGenericBase()))
  val nestedGeneric = BridgedNestedGeneric(
    mapOfLists = mapOf("list1" to listOf(1, 2, 3)),
    listOfMaps = listOf(mapOf("a" to 1, "b" to 2)),
    complexNested = mapOf("outer" to listOf(mapOf(1 to "one", 2 to "two"))),
  )

  // Long values: a large positive (needs both low/high halves) and a negative.
  val longInline = BridgedLongInline(raw = 1234567890123L)
  val longHolder = BridgedLongHolder(v = -5L)
  val longInlineHolder = BridgedLongInlineHolder(inline = longInline)

  // Color-like payloads: values that need both halves of the Long and are far apart, so a
  // truncated or zeroed read is not mistaken for a pass.
  val longBoxSolid = BridgedLongBox(value = 0xFF11223344L)
  val longBoxHolder = BridgedLongBoxHolder(
    solid = longBoxSolid,
    gradient = listOf(BridgedLongBox(0xFF00FF00L), BridgedLongBox(0x80000000FFL)),
  )

  // Phase 0 spike values: sealed payloads with object/data-object/data-class children.
  val imageEmpty = BridgedImageState.Empty
  val imageLoading = BridgedImageState.Loading
  val imageSuccess = BridgedImageState.Success
  val imageError = BridgedImageState.Error(message = "load failed")
  val imageErrorNull = BridgedImageState.Error(message = null)
  val lottieLoading = BridgedLottieState.Loading
  val lottieError = BridgedLottieState.Error(message = "nope")

  // `align` stays null: a non-null enum cannot cross host -> JS (see
  // Host2JsBridgeEndToEndTest.enumCannotCrossToGuest), and this payload exists to pin the
  // Long/inline/nullable-structured field shapes.
  val textStyle = BridgedTextStyle(bold = true, fontSize = BridgedFloat(raw = 16f), raw = 0x0F00L, align = null)
  val annotationLink = BridgedStringAnnotation.Link(url = "https://example.com", style = textStyle)
  val annotationLinkNullStyle = BridgedStringAnnotation.Link(url = "https://example.com", style = null)
  val annotationStyle = BridgedStringAnnotation.Style(style = textStyle)
  val annotationRange = BridgedAnnotationRange(start = 1, end = 5, annotation = annotationLink)

  // Distinct values, so a decode that resolves to the wrong class (or to both names) cannot pass.
  val alignmentTopLevel = Alignment(value = 1)
  val alignmentNested = LineHeightStyle.Alignment(value = 2)
}

/**
 * The narrow scalars: `Byte`, `Short` and `Char`, plus a nullable one. All three cross as plain
 * numbers, which is why the gap went unseen: the JNI host reads them with
 * `SetByteField`/`SetShortField`/`SetCharField` and writes them back with the matching getters, while
 * the Kotlin/Native decoder had no arm for them at all — they fell through to the generic one, whose
 * answer is `Any?` against a `Byte` property, so the generated code did not compile. `Char` needs
 * more than an arm: Kotlin/JS makes it an inline class, so the guest cannot cast a wire number into
 * one and has to build it (see `bridgeChar`).
 */
@WithHost2JSBridge
@WithJS2HostBridge
public class BridgedNarrowHolder(
  public val b: Byte,
  public val s: Short,
  public val c: Char,
  public val opt: Char? = null,
) {
  override fun toString(): String = "BridgedNarrowHolder(b=$b, s=$s, c=$c, opt=$opt)"
}

/**
 * Every scalar the wire represents as a number but the type system refuses to treat as an `Int`: the
 * narrow ones (`Byte`, `Short`, `Char`) and the unsigned ones. Values chosen to stress sign and
 * width — `-1` as a `Byte`, `60_000` in a `Short`-sized field, a `UInt` above `Int.MAX_VALUE`, a
 * `ULong` above `Long.MAX_VALUE`.
 *
 * Task 5.15. The Kotlin/Native host did not compile at all for the `UByte` field (its generated
 * code produced a `Byte` for a `UByte` property) and refused the `UInt` field outright when the
 * value reached the guest as an argument (`HOST2JS: no bridge for 'kotlin.UInt'`). `UInt` and
 * `ULong` worked by accident: the wrapper arms for their underlying `Int` and `Long` happened to
 * exist, and `inlineWrapperChain` built the wrapper around them.
 */

@WithHost2JSBridge
@WithJS2HostBridge
public class BridgedNarrowScalarProbe(
  public val b: Byte,
  public val s: Short,
  public val c: Char,
  public val u: UInt,
  public val ub: UByte,
  public val us: UShort,
  public val ul: ULong,
) {
  override fun toString(): String = "BridgedNarrowScalarProbe(b=$b, s=$s, c=$c, u=$u, ub=$ub, us=$us, ul=$ul)"
}

/**
 * Strings that sit on an encoding boundary: a NUL, a supplementary character, and multi-byte code
 * points below the surrogate range. The tail is there so that truncation shows up as *missing text*
 * rather than as a length nobody thought to compare.
 *
 * Task 5.16. Java's modified UTF-8 and standard UTF-8 disagree in exactly two places -- NUL and
 * anything above U+FFFF -- and the JNI bridge was handing one to a consumer expecting the other.
 */
@WithHost2JSBridge
@WithJS2HostBridge
public class BridgedEncodingProbe(
  public val s: String,
  public val tail: String,
) {
  override fun toString(): String = "BridgedEncodingProbe(len=${s.length}, last=${s.lastOrNull()?.code ?: -1}, tail=$tail)"
}

/**
 * Mutable body properties: not constructor parameters, so not in `bridgeCreate`'s signature, and
 * assignable only after construction — which means the receiver has to do the assigning, in both
 * directions. The four types are here on purpose: the absence-tolerant reads answer with null for
 * some of them and with a zero for others, and a receiver that assigns blindly would write a null
 * into `extra` or a 0 into `count` whenever a peer predates the property.
 *
 * It bridges both ways so the round trip covers the decode as well as the construction.
 */
@WithHost2JSBridge
@WithJS2HostBridge
public class BridgedMutableBody(public val a: Int) {
  public var extra: String = "guest-default"
  public var tags: List<String> = listOf("guest")
  public var w: BridgedInline = BridgedInline(1)
  public var count: Int = 0

  override fun toString(): String = "BridgedMutableBody(a=$a, extra=$extra, tags=$tags, w=$w, count=$count)"
}
