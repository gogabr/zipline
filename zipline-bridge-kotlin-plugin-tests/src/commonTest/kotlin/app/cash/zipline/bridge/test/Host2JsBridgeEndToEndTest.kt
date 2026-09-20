/*
 * End-to-end tests for the @WithHost2JSBridge bridge compiler plugin.
 *
 * Each test takes a host value, pushes it into the guest as a JS object (host->JS via
 * convertToJs / bridgeAnyToJs), and reads it back (JS->host via bridgeForAny). The round-trip
 * proves the full path: prototype-based instance creation with the guest class prototype
 * (whose bridge_dispatch is inherited), real Kotlin/JS collections and Longs via the guest
 * runtime factories, and the JS->host readers consuming those exact shapes.
 *
 * The same tests run on both backends (jvmTest via the production engine's callGuestFunction,
 * nativeTest via the Kotlin/Native anyToJs path).
 */
package app.cash.zipline.bridge.test

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The guest app module id, as assigned by ZiplineCompiler (./<entry file>.js). */
private const val GUEST_MODULE = "./zipline-root-zipline-bridge-kotlin-plugin-tests.js"

/** Host backend for host->JS round trips. */
expect class TestHost2Js() {
  fun loadGuest()
  fun roundTrip(value: Any): Any? // host object -> JS (convertToJs) -> back (bridgeForAny)
  fun toJson(value: Any): String  // host object -> JS -> JSON.stringify (debugging/triage aid)
  /** True if [name] names a callable function on globalThis. */
  fun hasGlobalFunction(name: String): Boolean
  /** Convert [args] host->JS and call globalThis[name] with them; converts the result back. */
  fun callGuestFunction(name: String, args: List<Any?>): Any?
  /** Evaluate [script] in the guest runtime, dispatching any result through the bridge. */
  fun evaluateForBridge(script: String): Any?
  fun close()
}

class Host2JsBridgeEndToEndTest {
  private lateinit var host: TestHost2Js

  @BeforeTest
  fun setUp() {
    host = TestHost2Js()
    host.loadGuest()
    // No guest-side warm-up: @WithHost2JSBridge classes register themselves at module load, so
    // every conversion below works without the guest ever touching the class.
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
  fun enumCannotCrossToGuest() {
    // An enum has no host->JS representation: the guest's instances are the singletons from its
    // own `values()`, so nothing the host builds can be used as that enum (identity and `when`
    // comparisons would fail). The conversion refuses instead of shipping a lookalike. The
    // direction that does work, guest -> host, is covered by BridgeEndToEndTest.bridgedEnum and
    // .bridgedEnumHolder.
    //
    // JVM reports the refusal directly (IllegalStateException from the generated C); Kotlin/Native
    // wraps a conversion failure in JsException, so assert the behaviour both surfaces: a failure
    // that names the enum.
    val failure = assertFails { roundTrip(BridgedTestValues.enumHolder) }
    assertTrue(
      failure.message.orEmpty().contains("is an enum"),
      "expected the refusal to name the enum, got: ${failure.message}",
    )
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

  @Test
  fun imageStateObjects() {
    // Sealed interface with `object` children: the singleton must round-trip as the same object.
    assertEquals(BridgedTestValues.imageEmpty, roundTrip(BridgedTestValues.imageEmpty))
    assertEquals(BridgedTestValues.imageLoading, roundTrip(BridgedTestValues.imageLoading))
    assertEquals(BridgedTestValues.imageSuccess, roundTrip(BridgedTestValues.imageSuccess))
  }

  @Test
  fun imageStateError() {
    // Data class child of the sealed interface, with nullable String payload.
    assertEquals(BridgedTestValues.imageError, roundTrip(BridgedTestValues.imageError))
    assertEquals(BridgedTestValues.imageErrorNull, roundTrip(BridgedTestValues.imageErrorNull))
  }

  @Test
  fun lottieStateDataObjects() {
    // `data object` children (mirrors wb LottieAnimationLoadState).
    assertEquals(BridgedTestValues.lottieLoading, roundTrip(BridgedTestValues.lottieLoading))
    assertEquals(BridgedTestValues.lottieError, roundTrip(BridgedTestValues.lottieError))
  }

  @Test
  fun stringAnnotationNested() {
    // Data class child of a sealed interface whose payload holds another bridged data class
    // (Long, inline, enum, nullable-structured fields) - the AnnotatedStringRange shape.
    assertEquals(BridgedTestValues.annotationRange, roundTrip(BridgedTestValues.annotationRange))
    assertEquals(BridgedTestValues.annotationLinkNullStyle, roundTrip(BridgedTestValues.annotationLinkNullStyle))
  }

  @Test
  fun stringAnnotationStyle() {
    assertEquals(BridgedTestValues.annotationStyle, roundTrip(BridgedTestValues.annotationStyle))
  }

  @Test
  fun guestSeesTheConvertedFields() {
    // The guest's own view of a converted value: JSON.stringify shows the field names the guest
    // will read (stable @JsName properties), with the host's values in them.
    val json = host.toJson(BridgedTestValues.longHolder)
    assertEquals(true, json.contains("\"v\":"), "json was: $json")
  }

  // -- Phase 1: host->guest call API (hasGlobalFunction / callGuestFunction) --

  /** An unannotated host class: converting it must throw, never fall back to a sentinel. */
  class NotBridged(val payload: String)

  @Test
  fun hasGlobalFunctionProbe() {
    host.evaluateForBridge("globalThis.__testSink = function (a, b, c) { return a.name + '|' + b + '|' + c; }; 0")
    assertEquals(true, host.hasGlobalFunction("__testSink"))
    assertEquals(false, host.hasGlobalFunction("__no_such_sink"))
  }

  @Test
  fun callGuestFunctionRoundTrip() {
    host.evaluateForBridge("globalThis.__testSink = function (a, b, c) { return a.name + '|' + b + '|' + c; }; 0")
    val result = host.callGuestFunction(
      "__testSink",
      listOf(BridgedTestValues.data, 42, "x"),
    )
    assertEquals("seven|42|x", result)
  }

  @Test
  fun callGuestFunctionUnknownFunctionThrows() {
    // Loud on both backends, though with the backend's own exception type: the JVM reports
    // IllegalStateException from ContextJni, Kotlin/Native a JsException.
    val e = assertFailsWith<RuntimeException> {
      host.callGuestFunction("__no_such_sink", emptyList())
    }
    assertEquals(true, e.message!!.contains("__no_such_sink"), "message was: " + e.message)
  }

  @Test
  fun callGuestFunctionUnbridgedArgThrows() {
    host.evaluateForBridge("globalThis.__testSink = function (a) { return 'unreachable'; }; 0")
    // The JVM surfaces the missing convertToJs member as a NoSuchMethodError, Kotlin/Native an
    // IllegalStateException; both name the offending argument class.
    val e = assertFailsWith<Throwable> {
      host.callGuestFunction("__testSink", listOf(NotBridged("nope")))
    }
    assertEquals(true, e.message!!.contains("NotBridged"), "message was: " + e.message)
  }

  @Test
  fun guestFunctionReturningUnitDecodes() {
    // A Unit-returning guest function (the direct-event sink is one) is not an unbridged payload
    // class; it decodes to null, as it always did.
    host.evaluateForBridge(
      "globalThis.__provideUnit = function () { " +
        "return require('$GUEST_MODULE').app.cash.zipline.bridge.test.provideUnit(); " +
        "}; 0",
    )
    try {
      // The value really is a kotlin.Unit instance (JS class "Unit"), not undefined - otherwise
      // this test would pass without the exemption.
      assertEquals(
        "Unit",
        host.evaluateForBridge(
          "require('$GUEST_MODULE').app.cash.zipline.bridge.test.provideUnit().constructor.name",
        ),
      )
      assertEquals(null, host.callGuestFunction("__provideUnit", emptyList()))
    } finally {
      host.evaluateForBridge("delete globalThis.__provideUnit")
    }
  }

  @Test
  fun untypedGuestListDecodes() {
    // Guest-authored Lists (ArrayList, singleton, empty) arriving through the untyped channel must
    // decode into real Lists. No guest-side reshaping: this is the bridge's own job, and it has to
    // hold for lists nested inside a payload just as much as for a top-level property value.
    for ((sink, expected) in listOf(
      "provideGuestList" to listOf("a", "b"),
      "provideGuestSingletonList" to listOf("only"),
      "provideGuestEmptyList" to emptyList<String>(),
    )) {
      host.evaluateForBridge(
        "globalThis.__$sink = function () { " +
          "return require('$GUEST_MODULE').app.cash.zipline.bridge.test.$sink(); " +
          "}; 0",
      )
      assertEquals(expected, host.callGuestFunction("__$sink", emptyList()))
    }
  }

  @Test
  fun untypedGuestMapDecodes() {
    // A guest Kotlin/JS Map arriving through the untyped channel (a property change value, or a
    // call result) must decode into a real Map. It used to decode to null, which surfaced as
    // "null cannot be cast to non-null type kotlin.collections.Map" at the consumer.
    host.evaluateForBridge(
      "globalThis.__provideGuestMap = function () { " +
        "return require('$GUEST_MODULE').app.cash.zipline.bridge.test.provideGuestMap(); " +
        "}; 0",
    )
    try {
      val result = host.callGuestFunction("__provideGuestMap", emptyList())
      val map = result as? Map<*, *> ?: error("expected a Map, got $result")
      assertEquals(setOf("a", "b"), map.keys)
      assertEquals("1", map["a"])
      assertEquals("2", map["b"])
    } finally {
      host.evaluateForBridge("delete globalThis.__provideGuestMap")
    }
  }

  @Test
  fun unbridgedGuestClassDecodesLoudly() {
    // A Kotlin/JS instance of an unannotated class has no bridge converter and no
    // bridge_dispatch, so host-side decoding must name the class instead of silently producing
    // null (which used to surface as a cast/NPE far from the cause).
    host.evaluateForBridge(
      "globalThis.__provideNotBridgedGuest = function () { " +
        "return require('$GUEST_MODULE').app.cash.zipline.bridge.test.provideNotBridgedGuest(); " +
        "}; 0",
    )
    try {
      val e = assertFailsWith<IllegalStateException> {
        host.callGuestFunction("__provideNotBridgedGuest", emptyList())
      }
      assertEquals(true, e.message!!.contains("NotBridgedGuest"), "message was: " + e.message)
      assertEquals(
        true,
        e.message!!.contains("no converter registered"),
        "message was: " + e.message,
      )
    } finally {
      host.evaluateForBridge("delete globalThis.__provideNotBridgedGuest")
    }
  }

  @Test
  fun missingGuestRegistrationFailsLoudly() {
    // A host that never loaded the guest module has no retained class prototypes. Creating a
    // bridge object must crash naming the class, never hand back a JS sentinel (undefined/null)
    // that a caller could mistake for converted data.
    val unregisteredHost = TestHost2Js()
    try {
      // Both backends fail loudly with the same message; the JVM surfaces the
      // IllegalStateException itself, Kotlin/Native wraps it in a JsException that also names the
      // argument it could not convert.
      val e = assertFailsWith<RuntimeException> {
        unregisteredHost.roundTrip(BridgedTestValues.data)
      }
      assertEquals(true, e.message!!.contains("no registered prototype"), "message was: " + e.message)
      assertEquals(true, e.message!!.contains("BridgedData"), "message was: " + e.message)
    } finally {
      unregisteredHost.close()
    }
  }
}
