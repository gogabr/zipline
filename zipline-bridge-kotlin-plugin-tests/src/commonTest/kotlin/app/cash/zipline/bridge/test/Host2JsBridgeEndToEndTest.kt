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

import app.cash.zipline.ZiplineApiMismatchException
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
  fun toJson(value: Any): String // host object -> JS -> JSON.stringify (debugging/triage aid)

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

  /**
   * A registration that arrives empty has to fail at load, in the host, where nothing can swallow it.
   * The guest wraps its own `__bridgeRegisterRuntime` call in a tolerant try/catch, so an exception
   * raised inside the hook is lost -- that is what made the ES-class lowering defect travel from a
   * detail of plugin IR all the way to a device, and arrive named only as
   * "the guest provides no create_<fqn>".
   *
   * Calling the hook with `undefined` is precisely what a broken construction does: it is how the
   * app-shaped bundle failed, and the es2015 skew modules fail the same way.
   */
  @Test
  fun theHostRefusesAModuleWhoseRegistrationArrivesEmpty() {
    assertEquals(BridgedTestValues.data, roundTrip(BridgedTestValues.data))

    val recorded = host.evaluateForBridge(
      """
      delete globalThis.__zipline_bridgeFactoriesProblem;
      __bridgeRegisterRuntime(undefined);
      typeof globalThis.__zipline_bridgeFactoriesProblem;
      """,
    )
    assertEquals("string", recorded, "the host did not record the bad registration")

    try {
      val e = assertFailsWith<IllegalStateException> { host.loadGuest() }
      assertTrue(e.message!!.contains("published unusable host->JS runtime factories"), "was: " + e.message)
      assertTrue(e.message!!.contains("called with undefined"), "was: " + e.message)
    } finally {
      // The record is sticky by design, so clear it: the other rows must not inherit this verdict.
      host.evaluateForBridge("delete globalThis.__zipline_bridgeFactoriesProblem; 0")
    }
  }

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
  fun parameterisedEnumCannotCrossToGuestEither() {
    // The refusal is on the class kind, not on whether the entries happen to carry data: an enum
    // with fields is exactly as much a singleton problem as one without, and its fields are what a
    // host-built lookalike would appear to carry convincingly. Asserted rather than assumed, because
    // `BridgedEnum` -- the only enum fixture until now -- cannot tell the two cases apart.
    // CLV-180 is expected to INVERT this test: it exists to record that the host->guest refusal in
    // `Host2JsGenerator` is keyed on class kind, so it covers parameterised enums as well as plain ones,
    // and not to argue that refusing is right. When enums can cross that way, this becomes a positive
    // crossing assertion next to `enumCannotCrossToGuest`'s replacement.
    val failure = assertFails { roundTrip(BridgedTestValues.sizedHolder) }
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
  fun sameSimpleName() {
    // Both classes cross host -> JS and back; the payload class for each is looked up by its own
    // bridge key, so a simple-name collision would decode one as the other (or fail loudly).
    assertEquals(BridgedTestValues.alignmentTopLevel, roundTrip(BridgedTestValues.alignmentTopLevel))
    assertEquals(BridgedTestValues.alignmentNested, roundTrip(BridgedTestValues.alignmentNested))
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
      val e = assertFailsWith<ZiplineApiMismatchException> {
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

  /**
   * A host-side bridge refusal must not poison the host that suffered it. The JNI decoders carried on
   * constructing Java objects after a Java-side converter had thrown, which HotSpot tolerates and ART
   * aborts on -- `JNI DETECTED ERROR IN APPLICATION: JNI NewObjectV called with pending exception` --
   * so the abort was the last word and the cause, which had been named correctly, scrolled past it.
   * What this pins is the contract the guards preserve: the refusal arrives in Kotlin as itself, and
   * the next call across the same engine still works.
   */
  @Test
  fun hostBridgeRefusalDoesNotPoisonTheHost() {
    host.evaluateForBridge(
      "globalThis.__provideNotBridgedGuest = function () { " +
        "return require('$GUEST_MODULE').app.cash.zipline.bridge.test.provideNotBridgedGuest(); " +
        "}; 0",
    )
    host.evaluateForBridge("globalThis.__hostStillWorks = function () { return 'alive'; }; 0")
    try {
      assertFailsWith<ZiplineApiMismatchException> {
        host.callGuestFunction("__provideNotBridgedGuest", emptyList())
      }
      // The refusal arrived, and it arrived alone: the same engine still answers the next call.
      assertEquals("alive", host.callGuestFunction("__hostStillWorks", emptyList()))
    } finally {
      host.evaluateForBridge("delete globalThis.__provideNotBridgedGuest")
      host.evaluateForBridge("delete globalThis.__hostStillWorks")
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

  /**
   * A mutable body field crosses host → guest, and only when the host actually has one.
   *
   * The converter emits body `var`s (JSON of the carrier shows `{"a_1":3,"extra_1":"from-host"}`), and
   * the guest's factory now applies the ones that arrived *after* running the constructor — the guest
   * constructor owns the initializer an absent field keeps, so the order is not interchangeable.
   *
   * This is the shape that made the create-factory a narrowing of the stamped path: stamping defined
   * every field onto the object, so `extra` crossed without any work, while a constructor-only factory
   * dropped it silently. Nothing caught it because no bridged class here had a mutable body field —
   * which is why the fixture stays and the skew pair sits beside it: presence is asserted here,
   * absence needs a peer that does not declare the field at all.
   */
  @Test
  fun mutableBodyFieldFromHostReachesTheGuest() {
    val payload = BridgedMutableBody(a = 3).apply {
      extra = "from-host"
      tags = listOf("a", "b")
      w = BridgedInline(9)
      count = 7
    }
    assertEquals("3|from-host|a+b|9|7", host.callGuestFunction("describeMutableBody", listOf(payload)))
  }

  /**
   * `Byte`, `Short` and `Char` into the guest. All three are numbers on the wire, so what is being
   * pinned is not the encoding but the decoding: the receiver has to narrow back to the type the
   * property declares, and the guest's runtime — which calls them numbers too — cannot be the reason
   * one host manages it and the other cannot.
   */
  @Test
  fun narrowScalarFieldsReachTheGuest() {
    val payload = BridgedNarrowHolder(b = 7, s = -9, c = 'z', opt = 'X')
    assertEquals("7|-9|z|X", host.callGuestFunction("describeNarrowHolder", listOf(payload)))
  }

  @Test
  fun narrowScalarFieldsAbsentFromTheGuestSide() {
    // The nullable one is the interesting case: absent-as-null has to stay distinguishable from the
    // zero a narrow read answers with, or a missing `Char` would silently become `'\u0000'`.
    //
    // `é` rather than an ASCII letter, because the value crosses as a code point and the round trip
    // through `Int.toChar()` is only tested by a number that is not also a small integer in disguise.
    val payload = BridgedNarrowHolder(b = -1, s = 2, c = 'é')
    assertEquals("-1|2|é|-", host.callGuestFunction("describeNarrowHolder", listOf(payload)))
  }

  /**
   * Task 5.15, the construction half. A `UByte` field used to make the Kotlin/Native host's
   * generated code fail to compile, and a `UInt` field made the same host refuse the payload before
   * the guest ever saw it. Both hosts are expected to answer identically, so a difference between
   * them is a bug in one host and not a documented difference.
   */
  @Test fun narrowAndUnsignedScalarFieldsReachTheGuest() {
    val payload = BridgedNarrowScalarProbe(
      b = -1,
      s = 300,
      c = 'Z',
      u = 4_000_000_000u,
      ub = 200u,
      us = 60_000u,
      ul = 18_000_000_000_000_000_000uL,
    )
    assertEquals(
      "-1|300|Z|4000000000|200|60000|18000000000000000000",
      host.callGuestFunction("describeNarrowScalarProbe", listOf(payload)),
    )
  }

  @Test fun narrowAndUnsignedScalarFieldsSurviveARoundTrip() {
    val payload = BridgedNarrowScalarProbe(
      b = -1,
      s = 300,
      c = 'Z',
      u = 4_000_000_000u,
      ub = 200u,
      us = 60_000u,
      ul = 18_000_000_000_000_000_000uL,
    )
    assertEquals(
      "BridgedNarrowScalarProbe(b=-1, s=300, c=Z, u=4000000000, ub=200, us=60000, ul=18000000000000000000)",
      roundTrip(payload).toString(),
    )
  }

  /**
   * Task 5.15, the `Any` half. A scalar that is not a field of a bridged class crosses through the
   * host's dynamic encoder instead of a generated one, and that encoder is a separate list of
   * cases: it refused every one of these with `HOST2JS: no bridge for 'kotlin.UInt'` on the
   * Kotlin/Native host.
   *
   * The expectation is what the guest actually receives, which is the wire form rather than the
   * Kotlin value: `Char` crosses as its code (as `CharArray` already did), and a `UInt` above
   * `Int.MAX_VALUE` crosses as the `Int` with the same bits, because that is the number
   * `HermesBridge_createInt` is handed. A receiver that decodes it back into a `UInt` reinterprets
   * those bits and gets the original value, which is what the two tests above pin.
   */
  @Test fun narrowAndUnsignedScalarsCrossTheDynamicPath() {
    val seen = listOf(
      host.callGuestFunction("describeScalarArg", listOf(-1.toByte())),
      host.callGuestFunction("describeScalarArg", listOf(300.toShort())),
      host.callGuestFunction("describeScalarArg", listOf('Z')),
      host.callGuestFunction("describeScalarArg", listOf(4_000_000_000u)),
      host.callGuestFunction("describeScalarArg", listOf(200.toUByte())),
      host.callGuestFunction("describeScalarArg", listOf(60_000.toUShort())),
      host.callGuestFunction("describeScalarArg", listOf(18_000_000_000_000_000_000uL)),
    )
    assertEquals("-1|300|90|-294967296|-56|-5536|-446744073709551616", seen.joinToString("|"))
  }

  /**
   * Task 5.16, the half both hosts can be held to. A supplementary character is one of the two
   * places where Java's modified UTF-8 and standard UTF-8 disagree (a surrogate pair in six bytes,
   * where UTF-8 wants four), and it is what the JNI host's raw `GetStringUTFChars` destroyed: the
   * guest received an *empty string*, and reported it as `JsException: Char sequence is empty`.
   * Nothing in the call was wrong, and nothing about the value was recoverable afterwards.
   */
  @Test fun supplementaryCharacterSurvivesTheBridge() {
    val payload = BridgedEncodingProbe(s = "\uD83D\uDE00", tail = "tail")
    assertEquals("2|56832|tail", host.callGuestFunction("describeEncodingProbe", listOf(payload)))
  }

  /** Code points below the surrogate boundary, which both encodings happen to agree on. */
  @Test fun multiByteCharactersSurviveTheBridge() {
    val payload = BridgedEncodingProbe(s = "éß中", tail = "tail")
    assertEquals("3|20013|tail", host.callGuestFunction("describeEncodingProbe", listOf(payload)))
  }

  /**
   * Task 5.17, the second disagreement between the two encodings: a NUL, which ends a C string.
   * These rows were pinned per host while only the JVM host could carry them; both hosts do now, so
   * they live here with the rest of the shared contract.
   */
  @Test fun nulStringReachesTheGuest() {
    val payload = BridgedEncodingProbe(s = "a\u0000b", tail = "tail")
    assertEquals("3|98|tail", host.callGuestFunction("describeEncodingProbe", listOf(payload)))
  }

  /** The guest's own string, built with a NUL in it, arriving whole. */
  @Test fun nulStringFromTheGuestReachesTheHost() {
    assertEquals(
      "BridgedEncodingProbe(len=3, last=121, tail=guest-tail)",
      host.callGuestFunction("provideEncodingProbe", emptyList()).toString(),
    )
  }

  /** Both halves in one value, since a truncation on either leg is invisible from the other. */
  @Test fun nulStringSurvivesARoundTrip() {
    val payload = BridgedEncodingProbe(s = "a\u0000b", tail = "tail")
    assertEquals(
      "BridgedEncodingProbe(len=3, last=98, tail=tail)",
      roundTrip(payload).toString(),
    )
  }

  /**
   * A high surrogate on its own, which no encoding can represent: Hermes hands over WTF-8 and JNI
   * cannot represent it either. Both hosts replace it with U+FFFD rather than disagreeing by host or
   * throwing, which is what this row holds them to -- the policy, not the damage.
   */
  @Test fun unpairedSurrogateBecomesAReplacementCharacter() {
    val payload = BridgedEncodingProbe(s = "\uD800", tail = "tail")
    assertEquals("1|65533|tail", host.callGuestFunction("describeEncodingProbe", listOf(payload)))
  }

  /** And back out of the guest intact, which is the other side of the same boundary. */
  @Test fun supplementaryCharacterSurvivesARoundTrip() {
    val payload = BridgedEncodingProbe(s = "\uD83D\uDE00", tail = "tail")
    assertEquals(
      "BridgedEncodingProbe(len=2, last=56832, tail=tail)",
      roundTrip(payload).toString(),
    )
  }

  /**
   * The same properties coming back, which is the other half of the machinery: the host's decoder
   * reads them, and a read made tolerant of absence answers with null for the reference-typed ones
   * and with zero for `count`. Assigning that blindly would corrupt the host's own state, so the
   * decode is presence-gated exactly like the guest's factory is.
   */
  @Test
  fun narrowScalarFieldsSurviveARoundTrip() {
    val payload = BridgedNarrowHolder(b = 7, s = -9, c = 'z', opt = 'X')
    assertEquals(
      "BridgedNarrowHolder(b=7, s=-9, c=z, opt=X)",
      roundTrip(payload).toString(),
    )
  }

  /**
   * The host warms up every bridged module, and each one publishes its own factories object through
   * `__bridgeRegisterRuntime`. The host merges them into one global; had it assigned the global
   * instead, the last module loaded would own it and every class whose module loaded earlier would
   * lose its create-factory, with the host reporting "the guest provides no create_..." for it.
   *
   * No single-module test bundle can see this: only the guest module here has bridged classes, so
   * there was exactly one registration and the two behaviours were indistinguishable. compose-live
   * has dozens of modules, and it found the difference on the first payload it sent.
   */
  @Test
  fun factoriesPublishedByAnEarlierModuleSurviveALaterRegistration() {
    // Stand in for a module that loads after the one carrying the fixtures.
    host.evaluateForBridge(
      """
      __bridgeRegisterRuntime({
        create_app_cash_zipline_bridge_test_LaterModulePayload: function (fields) {
          return { builtBy: 'later' };
        }
      });
      0
      """,
    )

    val factories = "globalThis.__zipline_bridgeFactories"
    // The guest's own runtime factories are still reachable...
    assertEquals("function", host.evaluateForBridge("typeof $factories.newLong"))
    // ...the later registration is visible beside them...
    assertEquals(
      "function",
      host.evaluateForBridge(
        "typeof $factories.create_app_cash_zipline_bridge_test_LaterModulePayload",
      ),
    )
    // ...and the guest's create-factories still build payloads, which is the behaviour an
    // overwrite would have destroyed.
    assertEquals(BridgedTestValues.data, roundTrip(BridgedTestValues.data))
  }

  @Test
  fun mutableBodyFieldsSurviveARoundTrip() {
    val payload = BridgedMutableBody(a = 3).apply {
      extra = "from-host"
      tags = listOf("a", "b")
      w = BridgedInline(9)
      count = 7
    }
    assertEquals(
      "BridgedMutableBody(a=3, extra=from-host, tags=[a, b], w=BridgedInline(raw=9), count=7)",
      roundTrip(payload).toString(),
    )
  }
}
