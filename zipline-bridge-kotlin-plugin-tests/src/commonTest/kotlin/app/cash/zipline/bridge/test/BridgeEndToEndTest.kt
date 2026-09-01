/*
 * End-to-end tests for the @WithJS2HostBridge bridge compiler plugin.
 *
 * The same tests run on both backends:
 * - jvmTest (JVM/JNI): evaluateForBridge goes through Context::toJavaObject, which dispatches
 *   to the generated C converters in libbridgetests.dylib.
 * - nativeTest (Kotlin/Native): evaluateForBridge evaluates the script and runs the raw
 *   JS value through bridgeForAny, which dispatches to the generated X_toKotlin converters.
 *
 * The JS guest (jsMain) constructs each value; constructing it triggers the bridge registration
 * injected by the compiler plugin, so the host can dispatch the result back to a Kotlin object.
 */
package app.cash.zipline.bridge.test

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The guest app module id, as assigned by ZiplineCompiler (./<entry file>.js). */
private const val GUEST_MODULE = "./zipline-root-zipline-bridge-kotlin-plugin-tests.js"

/** Host backend: loads the guest bytecode and evaluates JS through the bridge dispatcher. */
expect class TestHost() {
  fun loadGuest()
  fun evaluateOne(script: String): Any?
  fun close()
}

/** Backend base64 decoder for the embedded guest bytecode. */
expect fun decodeGuestBase64(encoded: String): ByteArray

class BridgeEndToEndTest {
  private lateinit var host: TestHost

  @BeforeTest
  fun setUp() {
    host = TestHost()
    host.loadGuest()
  }

  @AfterTest
  fun tearDown() {
    host.close()
  }

  private fun evalOne(provider: String): Any? {
    return host.evaluateOne("require('$GUEST_MODULE').app.cash.zipline.bridge.test.$provider()")
  }

  @Test
  fun bridgedData() {
    assertEquals(BridgedTestValues.data, evalOne("provideBridgedData"))
  }

  @Test
  fun bridgedInlineHolder() {
    assertEquals(BridgedTestValues.inlineHolder, evalOne("provideBridgedInlineHolder"))
  }

  @Test
  fun bridgedNestedInlineHolder() {
    assertEquals(BridgedTestValues.nestedInlineHolder, evalOne("provideBridgedNestedInlineHolder"))
  }

  @Test
  fun bridgedNestedInlineHolderNull() {
    assertEquals(BridgedTestValues.nestedInlineHolderNull, evalOne("provideBridgedNestedInlineHolderNull"))
  }

  @Test
  fun bridgedEnum() {
    assertEquals(BridgedTestValues.enumSecond, evalOne("provideBridgedEnum"))
  }

  @Test
  fun bridgedEnumHolder() {
    assertEquals(BridgedTestValues.enumHolder, evalOne("provideBridgedEnumHolder"))
  }

  @Test
  fun bridgedListHolder() {
    assertEquals(BridgedTestValues.listHolder, evalOne("provideBridgedListHolder"))
  }

  @Test
  fun bridgedFloatListHolder() {
    // Integral Float values (0f/1f) are stored as INT-tagged JS numbers and must be
    // converted to Float, not read through the float64 slot (regression for
    // RelativeLinearGradient.stops).
    assertEquals(BridgedTestValues.floatList, evalOne("provideBridgedFloatListHolder"))
  }
  fun bridgedNested() {
    assertEquals(BridgedTestValues.nested, evalOne("provideBridgedNested"))
  }

  @Test
  fun bridgedNullableNull() {
    assertEquals(BridgedTestValues.nullableNull, evalOne("provideBridgedNullableNull"))
  }

  @Test
  fun bridgedNullableValue() {
    assertEquals(BridgedTestValues.nullableValue, evalOne("provideBridgedNullableValue"))
  }

  // New tests for collections
  @Test
  fun bridgedArray() {
    // Data class equality on array fields is reference-based; compare contents.
    val actual = evalOne("provideBridgedArray") as BridgedArray
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
  fun bridgedNestedStructure() {
    val actual = evalOne("provideBridgedNestedStructure") as BridgedNestedStructure
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
  fun bridgedEmptyCollections() {
    val actual = evalOne("provideBridgedEmptyCollections") as BridgedEmptyCollections
    val expected = BridgedTestValues.emptyCollections
    assertContentEquals(expected.emptyArray, actual.emptyArray)
    assertEquals(expected.emptyList, actual.emptyList)
    assertEquals(expected.emptyMap, actual.emptyMap)
  }

  // New tests for inheritance
  @Test
  fun bridgedBaseClass() {
    assertEquals(BridgedTestValues.baseClass, evalOne("provideBridgedBaseClass"))
  }

  @Test
  fun bridgedInheritanceChild() {
    assertEquals(BridgedTestValues.inheritanceChild, evalOne("provideBridgedInheritanceChild"))
  }

  @Test
  fun bridgedDeepInheritance() {
    assertEquals(BridgedTestValues.deepInheritance, evalOne("provideBridgedDeepInheritance"))
  }

  @Test
  fun bridgedOverrideBase() {
    assertEquals(BridgedTestValues.overrideBase, evalOne("provideBridgedOverrideBase"))
  }

  @Test
  fun bridgedOverrideChild() {
    assertEquals(BridgedTestValues.overrideChild, evalOne("provideBridgedOverrideChild"))
  }

  @Test
  fun bridgedInterfaceImplementation() {
    assertEquals(BridgedTestValues.interfaceImpl, evalOne("provideBridgedInterfaceImplementation"))
  }

  // New tests for generics
  @Test
  fun bridgedGenericClassInt() {
    // Erased type parameters: JS numbers arrive as their numeric equivalent
    // (Integer on JVM, Double on Kotlin/Native), so compare via Number coercion.
    val actual = evalOne("provideBridgedGenericClassInt") as BridgedGenericClass<*>
    assertEquals(42.0, (actual.value as Number).toDouble())
    assertEquals(listOf(1.0, 2.0, 3.0), actual.list.map { (it as Number).toDouble() })
  }

  @Test
  fun bridgedGenericClassString() {
    assertEquals(BridgedTestValues.genericString, evalOne("provideBridgedGenericClassString"))
  }

  @Test
  fun bridgedMultiGenericClass() {
    val actual = evalOne("provideBridgedMultiGenericClass") as BridgedMultiGenericClass<*, *>
    assertEquals("key", actual.first)
    assertEquals(42.0, (actual.second as Number).toDouble())
    assertEquals(mapOf("key" to 42.0), actual.both.entries.associate { it.key.toString() to (it.value as Number).toDouble() })
  }

  @Test
  fun bridgedBoundedGenericClass() {
    assertEquals(BridgedTestValues.boundedGeneric, evalOne("provideBridgedBoundedGenericClass"))
  }

  @Test
  fun bridgedNestedGeneric() {
    val actual = evalOne("provideBridgedNestedGeneric") as BridgedNestedGeneric
    // Numbers in erased generic positions arrive as numeric types; compare via Double coercion.
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
