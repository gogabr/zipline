/*
 * End-to-end tests for the @WithHost2JSBridge bridge compiler plugin.
 *
 * Each test takes a host value, pushes it into the guest as a JS object (host->JS via
 * convertToJs / bridgeAnyToJs), and reads it back (JS->host via bridgeForAny). The round-trip
 * proves the full path: prototype-based instance creation with the guest class prototype
 * (whose bridge_dispatch is inherited), real Kotlin/JS collections and Longs via the guest
 * runtime factories, and the JS->host readers consuming those exact shapes.
 *
 * The same tests run on both backends (jvmTest via JNI hooks, nativeTest via the Kotlin/Native
 * anyToJs path).
 */
package app.cash.zipline.bridge.test

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The guest app module id, as assigned by ZiplineCompiler (./<entry file>.js). */
private const val GUEST_MODULE = "./zipline-root-zipline-bridge-kotlin-plugin-tests.js"

/** Host backend for host->JS round trips. */
expect class TestHost2Js() {
  fun loadGuest()
  /** Construct every host2js test class guest-side (fires companion-constructor registration). */
  fun warmUp()
  fun roundTrip(value: Any): Any? // host object -> JS (convertToJs) -> back (bridgeForAny)
  fun toJson(value: Any): String  // host object -> JS -> JSON.stringify (debugging/triage aid)
  fun close()
}

class Host2JsBridgeEndToEndTest {
  private lateinit var host: TestHost2Js

  @BeforeTest
  fun setUp() {
    host = TestHost2Js()
    host.loadGuest()
    // Companion-based registration fires on guest construction; construct every test class
    // once so prototype/factory registration is in place before any host->JS conversion.
    host.warmUp()
  }

  @AfterTest
  fun tearDown() {
    host.close()
  }

  private fun roundTrip(value: Any): Any? = host.roundTrip(value)

  @Test
  fun data() {
    assertEquals(BridgedTestValues.data, roundTrip(BridgedTestValues.data))
  }

  @Test
  fun long() {
    assertEquals(BridgedTestValues.longHolder, roundTrip(BridgedTestValues.longHolder))
  }

  @Test
  fun longInline() {
    assertEquals(BridgedTestValues.longInlineHolder, roundTrip(BridgedTestValues.longInlineHolder))
  }

  @Test
  fun inlineHolder() {
    assertEquals(BridgedTestValues.inlineHolder, roundTrip(BridgedTestValues.inlineHolder))
  }

  @Test
  fun floatHolder() {
    assertEquals(BridgedTestValues.floatHolder, roundTrip(BridgedTestValues.floatHolder))
  }

  @Test
  fun doubleHolder() {
    assertEquals(BridgedTestValues.doubleHolder, roundTrip(BridgedTestValues.doubleHolder))
  }

  @Test
  fun nestedInlineHolder() {
    assertEquals(BridgedTestValues.nestedInlineHolder, roundTrip(BridgedTestValues.nestedInlineHolder))
  }

  @Test
  fun nestedInlineHolderNull() {
    assertEquals(BridgedTestValues.nestedInlineHolderNull, roundTrip(BridgedTestValues.nestedInlineHolderNull))
  }

  @Test
  fun enumHolder() {
    assertEquals(BridgedTestValues.enumHolder, roundTrip(BridgedTestValues.enumHolder))
  }

  @Test
  fun listHolder() {
    assertEquals(BridgedTestValues.listHolder, roundTrip(BridgedTestValues.listHolder))
  }

  @Test
  fun nested() {
    assertEquals(BridgedTestValues.nested, roundTrip(BridgedTestValues.nested))
  }

  @Test
  fun nullableNull() {
    assertEquals(BridgedTestValues.nullableNull, roundTrip(BridgedTestValues.nullableNull))
  }

  @Test
  fun nullableValue() {
    assertEquals(BridgedTestValues.nullableValue, roundTrip(BridgedTestValues.nullableValue))
  }

  @Test
  fun array() {
    val actual = roundTrip(BridgedTestValues.array) as BridgedArray
    val expected = BridgedTestValues.array
    assertContentEquals(expected.intArray, actual.intArray)
    assertContentEquals(expected.stringArray, actual.stringArray)
    assertContentEquals(expected.booleanArray, actual.booleanArray)
    assertContentEquals(expected.doubleArray, actual.doubleArray)
    assertContentEquals(expected.floatArray, actual.floatArray)
    assertContentEquals(expected.byteArray, actual.byteArray)
    assertContentEquals(expected.shortArray, actual.shortArray)
    assertContentEquals(expected.charArray, actual.charArray)
    assertEquals(expected.primitiveList, actual.primitiveList)
    assertEquals(expected.stringList, actual.stringList)
  }

  @Test
  fun nestedStructure() {
    val actual = roundTrip(BridgedTestValues.nestedStructure) as BridgedNestedStructure
    val expected = BridgedTestValues.nestedStructure
    expected.nestedArray.forEachIndexed { i, row ->
      assertContentEquals(row, actual.nestedArray[i])
    }
    assertEquals(expected.nestedList, actual.nestedList)
    expected.mixedStructure.forEachIndexed { i, row ->
      row.forEachIndexed { j, arr ->
        assertContentEquals(arr, actual.mixedStructure[i][j])
      }
    }
  }

  @Test
  fun emptyCollections() {
    // Data-class equality on array fields is reference-based; compare contents per field.
    val actual = roundTrip(BridgedTestValues.emptyCollections) as BridgedEmptyCollections
    val expected = BridgedTestValues.emptyCollections
    assertContentEquals(expected.emptyArray, actual.emptyArray)
    assertEquals(expected.emptyList, actual.emptyList)
    assertEquals(expected.emptyMap, actual.emptyMap)
  }

  @Test
  fun baseClass() {
    assertEquals(BridgedTestValues.baseClass, roundTrip(BridgedTestValues.baseClass))
  }

  @Test
  fun inheritanceChild() {
    assertEquals(BridgedTestValues.inheritanceChild, roundTrip(BridgedTestValues.inheritanceChild))
  }

  @Test
  fun deepInheritance() {
    assertEquals(BridgedTestValues.deepInheritance, roundTrip(BridgedTestValues.deepInheritance))
  }

  @Test
  fun genericClassInt() {
    // Erased type parameters: JS numbers arrive as their numeric equivalent, compare via Number.
    val actual = roundTrip(BridgedTestValues.genericInt) as BridgedGenericClass<*>
    assertEquals(42.0, (actual.value as Number).toDouble())
    assertEquals(listOf(1.0, 2.0, 3.0), actual.list.map { (it as Number).toDouble() })
  }

  @Test
  fun genericClassString() {
    assertEquals(BridgedTestValues.genericString, roundTrip(BridgedTestValues.genericString))
  }

  @Test
  fun multiGeneric() {
    val actual = roundTrip(BridgedTestValues.multiGeneric) as BridgedMultiGenericClass<*, *>
    assertEquals("key", actual.first)
    assertEquals(42.0, (actual.second as Number).toDouble())
    assertEquals(
      mapOf("key" to 42.0),
      actual.both.entries.associate { it.key.toString() to (it.value as Number).toDouble() },
    )
  }

  @Test
  fun nestedGeneric() {
    val actual = roundTrip(BridgedTestValues.nestedGeneric) as BridgedNestedGeneric
    // Real LinkedHashMap instances round-trip; numbers in erased positions arrive as numeric.
    val mapOfLists = actual.mapOfLists.mapValues { (_, v) -> v.map { (it as Number).toDouble() } }
    assertEquals(mapOf("list1" to listOf(1.0, 2.0, 3.0)), mapOfLists)
    val listOfMaps = actual.listOfMaps.map { m -> m.entries.associate { it.key.toString() to (it.value as Number).toDouble() } }
    assertEquals(listOf(mapOf("a" to 1.0, "b" to 2.0)), listOfMaps)
    val complexNested = actual.complexNested.mapValues { (_, v) ->
      v.map { m -> m.mapKeys { (it.key as Number).toDouble() } }
    }
    assertEquals(mapOf("outer" to listOf(mapOf(1.0 to "one", 2.0 to "two"))), complexNested)
  }
}
