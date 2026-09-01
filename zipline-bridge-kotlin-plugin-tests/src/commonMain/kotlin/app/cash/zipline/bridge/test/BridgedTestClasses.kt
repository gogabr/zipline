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

import app.cash.zipline.bridge.support.WithJS2HostBridge
import kotlin.jvm.JvmInline

@WithJS2HostBridge
data class BridgedData(
  val id: Int,
  val name: String,
  val active: Boolean,
  val ratio: Double,
)

@WithJS2HostBridge
@JvmInline
value class BridgedInline(val raw: Int)

@WithJS2HostBridge
@JvmInline
value class BridgedFloat(val raw: Float)

@WithJS2HostBridge
@JvmInline
value class BridgedDouble(val raw: Double)

/** Nested inline: a value class whose underlying type is itself a value class (like SpaceArrangement(spacing: Dp)). */
@WithJS2HostBridge
@JvmInline
value class BridgedNestedInline(val inner: BridgedDouble)

@WithJS2HostBridge
enum class BridgedEnum { FIRST, SECOND, THIRD }

@WithJS2HostBridge
data class BridgedEnumHolder(val value: BridgedEnum)

/**
 * Inline classes can only be bridged as fields (or list elements): Kotlin/JS inlines a
 * standalone value-class return value to the underlying primitive, which carries no
 * bridge_dispatch property.
 */
@WithJS2HostBridge
data class BridgedInlineHolder(val inlineValue: BridgedInline)

@WithJS2HostBridge
data class BridgedFloatHolder(val floatValue: BridgedFloat)

@WithJS2HostBridge
data class BridgedDoubleHolder(val doubleValue: BridgedDouble)

@WithJS2HostBridge
data class BridgedNestedInlineHolder(val nested: BridgedNestedInline?)

@WithJS2HostBridge
data class BridgedListHolder(val items: List<Int>)
@WithJS2HostBridge
data class BridgedFloatListHolder(val items: List<Float>)

@WithJS2HostBridge
data class BridgedNested(val outer: BridgedData)

@WithJS2HostBridge
data class BridgedNullable(val text: String?, val count: Int?)

/**
 * Test class for arrays and lists of primitives
 */
@WithJS2HostBridge
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
data class BridgedNestedStructure(
  val nestedArray: Array<Array<Int>>,
  val nestedList: List<List<String>>,
  val mixedStructure: Array<List<IntArray>>,
)

/**
 * Test class for empty collections
 */
@WithJS2HostBridge
data class BridgedEmptyCollections(
  val emptyArray: IntArray,
  val emptyList: List<String>,
  val emptyMap: Map<String, Int>,
)

/**
 * Test class for inheritance scenarios including overrides
 */
@WithJS2HostBridge
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
interface BridgedInterface {
  val interfaceProperty: String
  fun interfaceMethod(): Int
}

@WithJS2HostBridge
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
data class BridgedGenericClass<T>(
  val value: T,
  val list: List<T>
)

/**
 * Test class for multiple type parameters
 */
@WithJS2HostBridge
data class BridgedMultiGenericClass<T, U>(
  val first: T,
  val second: U,
  val both: Map<T, U>
)

/**
 * Test class for bounded generics (where T : SomeClass)
 */
@WithJS2HostBridge
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
data class BridgedBoundedGenericClass<T : BridgedBoundedGenericBase>(
  val value: T,
  val list: List<T>
)

/**
 * Test class for nested generics
 */
@WithJS2HostBridge
data class BridgedNestedGeneric(
  val mapOfLists: Map<String, List<Int>>,
  val listOfMaps: List<Map<String, Int>>,
  val complexNested: Map<String, List<Map<Int, String>>>
)

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
  val listHolder = BridgedListHolder(items = listOf(1, 2, 3))
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
    stringList = listOf("a", "b", "c")
  )
  
  val nestedStructure = BridgedNestedStructure(
    nestedArray = arrayOf(arrayOf(1, 2), arrayOf(3, 4)),
    nestedList = listOf(listOf("a", "b"), listOf("c", "d")),
    mixedStructure = arrayOf(listOf(intArrayOf(1, 2), intArrayOf(3, 4)))
  )
  
  val emptyCollections = BridgedEmptyCollections(
    emptyArray = intArrayOf(),
    emptyList = listOf(),
    emptyMap = mapOf()
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
    complexNested = mapOf("outer" to listOf(mapOf(1 to "one", 2 to "two")))
  )
}