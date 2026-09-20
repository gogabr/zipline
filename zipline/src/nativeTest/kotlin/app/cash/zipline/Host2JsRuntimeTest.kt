package app.cash.zipline

import app.cash.zipline.hermes.HERMES_TAG_ERROR
import app.cash.zipline.hermes.HermesBridge_freeHandle
import app.cash.zipline.hermes.HermesBridge_getProperty
import app.cash.zipline.hermes.HermesBridge_getValueDouble
import app.cash.zipline.hermes.HermesBridge_getValueString
import app.cash.zipline.hermes.HermesBridge_getValueTag
import app.cash.zipline.hermes.HermesBridge_installBridgeRegister
import app.cash.zipline.hermes.HermesBridge_isFunction
import app.cash.zipline.hermes.HermesContext_evaluate
import app.cash.zipline.hermes.HermesContext_getLastError
import app.cash.zipline.hermes.HermesRuntime_create
import app.cash.zipline.hermes.HermesRuntime_destroy
import app.cash.zipline.hermes.HermesRuntime_getJsiRuntime
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toKStringFromUtf8
import kotlinx.cinterop.useContents
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runtime tests for the host2js helpers (anyToJs, kotlinLongToJs, newJsObject, setJsProperty)
 * against a real Hermes runtime, through the handle API.
 *
 * The guest state these helpers need - the runtime factories published with
 * `__bridgeRegisterRuntime`, the value ops, and a class prototype - is created here through the
 * same entry points a generated guest module uses, so the test pins the wire contract rather than
 * a private shortcut.
 */
@OptIn(ExperimentalForeignApi::class)
class Host2JsRuntimeTest {
  private val context: COpaquePointer? = HermesRuntime_create()

  init {
    check(context != null) { "Failed to create a Hermes runtime" }
    HermesBridge_installBridgeRegister(HermesRuntime_getJsiRuntime(context))
  }

  @AfterTest
  fun tearDown() {
    HermesRuntime_destroy(context)
  }

  /** Evaluate [script] in the test runtime; a JS error fails the test with the engine's message. */
  private fun eval(script: String) {
    val tagged = HermesContext_evaluate(context, script, "host2js_runtime_test.js")
    tagged.useContents {
      if (tag == HERMES_TAG_ERROR) {
        fail("JS failed: " + (HermesContext_getLastError(context)?.toKString() ?: "unknown error"))
      }
    }
  }

  /**
   * Publish what a guest module compiled with the bridge plugin publishes at load: the runtime
   * factories, the value ops, and one class prototype whose prototype carries a data property, a
   * method and a getter-only accessor.
   */
  private fun publishGuestRuntime() {
    eval(
      """
      __bridgeRegisterRuntime({
        newLong: function (low, high) { return { low_1: low, high_1: high }; },
        newArrayList: function (array) { return array; },
        newLinkedHashMap: function (keys, values) { return {}; }
      });
      globalThis.__zipline_bridgeValueOps = {
        kind: function (value) { return 0; },
        longLow: function (value) { return value.low_1; },
        longHigh: function (value) { return value.high_1; },
        enumOrdinal: function (value) { return -1; },
        iterator: function (value) { return null; },
        hasNext: function (iterator) { return false; },
        next: function (iterator) { return null; },
        key: function (entry) { return null; },
        value: function (entry) { return null; }
      };
      class Host2JsEcho {
        greet() { return 'hello'; }
      }
      Host2JsEcho.prototype.origin = 'prototype';
      Object.defineProperty(Host2JsEcho.prototype, 'value', { get: function () { return 99; } });
      __bridgeRegister("com.example.Host2JsEcho", Host2JsEcho);
      0
      """.trimIndent(),
    )
  }

  /** Host value -> JS -> host, exercising both directions of the conversion. */
  private fun roundTrip(value: Any?): Any? {
    val handle = anyToJs(context, value)
    try {
      return bridgeForAny(context, handle)
    } finally {
      HermesBridge_freeHandle(context, handle)
    }
  }

  /** The property [name] of the object at [handle], as a handle. */
  private fun propertyOf(handle: Int, name: String): Int {
    val property = HermesBridge_getProperty(context, handle, name)
    check(property >= 0) { "no property '$name' on handle $handle" }
    return property
  }

  /** The string value of the property [name] of the object at [handle]. */
  private fun stringPropertyOf(handle: Int, name: String): String? {
    val property = propertyOf(handle, name)
    try {
      val pointer = HermesBridge_getValueString(context, property) ?: return null
      return pointer.toKStringFromUtf8().also { platform.posix.free(pointer) }
    } finally {
      HermesBridge_freeHandle(context, property)
    }
  }

  /** The property [name] of the prototype the guest registered for [fq]. */
  private fun prototypePropertyOf(fq: String, name: String): Int {
    val prototypes = HermesBridge_getProperty(context, 0, "__zipline_bridgePrototypes")
    check(prototypes >= 0) { "the guest published no prototypes" }
    val prototype = try {
      propertyOf(prototypes, fq)
    } finally {
      HermesBridge_freeHandle(context, prototypes)
    }
    try {
      return propertyOf(prototype, name)
    } finally {
      HermesBridge_freeHandle(context, prototype)
    }
  }

  @Test
  fun `kotlinLongToJs round-trips positive and negative longs`() {
    publishGuestRuntime()
    for (value in listOf(
      0L, 1L, -1L, 42L, -42L,
      Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong(),
      Long.MAX_VALUE, Long.MIN_VALUE,
      0x0123456789ABCDEFL,
    )) {
      val handle = kotlinLongToJs(context, value)
      try {
        // The JS -> host readers must see a real kotlin.Long, not a truncated number.
        assertEquals(value, JsNumberToLong(context, handle), "JsNumberToLong of $value")
        assertEquals(value, bridgeForAny(context, handle), "bridgeForAny of $value")
      } finally {
        HermesBridge_freeHandle(context, handle)
      }
    }
  }

  @Test
  fun `kotlinLongToJs creates an object with low and high halves`() {
    publishGuestRuntime()
    val handle = kotlinLongToJs(context, 0x0123456789ABCDEFL)
    try {
      // The wire format JsNumberToLong packs back together: low 32 bits then high 32 bits.
      val low = propertyOf(handle, "low_1")
      val high = propertyOf(handle, "high_1")
      try {
        assertEquals(-1985229329, HermesBridge_getValueDouble(context, low).toInt())
        assertEquals(19088743, HermesBridge_getValueDouble(context, high).toInt())
      } finally {
        HermesBridge_freeHandle(context, high)
        HermesBridge_freeHandle(context, low)
      }
    } finally {
      HermesBridge_freeHandle(context, handle)
    }
  }

  @Test
  fun `anyToJs round-trips primitives through bridgeForAny`() {
    assertEquals(null, roundTrip(null))
    assertEquals(true, roundTrip(true))
    assertEquals(false, roundTrip(false))
    // JS numbers have one representation, so every Kotlin numeric arrives as a Double.
    assertEquals(42.0, roundTrip(42))
    assertEquals(3.14, roundTrip(3.14))
    assertEquals(1.5, roundTrip(1.5f))
    assertEquals("hello", roundTrip("hello"))
  }

  @Test
  fun `anyToJs long uses the newLong factory`() {
    publishGuestRuntime()
    assertEquals(-5L, roundTrip(-5L))
    assertEquals(1234567890123L, roundTrip(1234567890123L))

    // -5L is low = -5, high = -1 in the halves the guest's Long box exposes.
    val handle = anyToJs(context, -5L)
    try {
      val low = propertyOf(handle, "low_1")
      val high = propertyOf(handle, "high_1")
      try {
        assertEquals(-5, HermesBridge_getValueDouble(context, low).toInt())
        assertEquals(-1, HermesBridge_getValueDouble(context, high).toInt())
      } finally {
        HermesBridge_freeHandle(context, high)
        HermesBridge_freeHandle(context, low)
      }
    } finally {
      HermesBridge_freeHandle(context, handle)
    }
  }

  @Test
  fun `newJsObject creates an instance carrying the guest prototype`() {
    publishGuestRuntime()
    val instance = newJsObject(context, "com.example.Host2JsEcho")
    try {
      // The instance is an object whose prototype is the guest's own: it inherits the
      // prototype's data property and method, and has no own properties of its own (the guest
      // constructor does not run - the host fills the fields in).
      assertEquals("prototype", stringPropertyOf(instance, "origin"))
      val greet = propertyOf(instance, "greet")
      try {
        assertTrue(HermesBridge_isFunction(context, greet) != 0, "proto method is not callable")
      } finally {
        HermesBridge_freeHandle(context, greet)
      }

      // Nothing of its own: the guest constructor does not run, only what the host defines lands
      // on the instance (see setJsProperty below).
      assertEquals(-1, HermesBridge_getProperty(context, instance, "noSuchProperty"))
    } finally {
      HermesBridge_freeHandle(context, instance)
    }
  }

  @Test
  fun `newJsObject fails loudly for a class the guest never registered`() {
    // A host that skipped the guest's registration must not get a JS sentinel back: undefined is
    // a legitimate payload value, so the failure has to name the class.
    val e = assertFailsWith<IllegalStateException> {
      newJsObject(context, "com.example.NoSuchBridge")
    }
    assertTrue(e.message!!.contains("no registered prototype"), "message was: " + e.message)
    assertTrue(e.message!!.contains("com.example.NoSuchBridge"), "message was: " + e.message)
  }

  @Test
  fun `setJsProperty defines an own data property`() {
    publishGuestRuntime()
    val instance = newJsObject(context, "com.example.Host2JsEcho")
    val value = newJsInt(context, 7)
    try {
      setJsProperty(context, instance, "value", value)

      // The instance reads its own property back...
      val own = propertyOf(instance, "value")
      try {
        assertEquals(7.0, HermesBridge_getValueDouble(context, own))
      } finally {
        HermesBridge_freeHandle(context, own)
      }

      // ...while the prototype's getter-only accessor is untouched: setJsProperty defines a
      // property instead of assigning, which would have gone through (and been swallowed by, or
      // thrown from) the accessor.
      val prototypeValue = prototypePropertyOf("com.example.Host2JsEcho", "value")
      try {
        assertEquals(99.0, HermesBridge_getValueDouble(context, prototypeValue))
      } finally {
        HermesBridge_freeHandle(context, prototypeValue)
      }
    } finally {
      HermesBridge_freeHandle(context, value)
      HermesBridge_freeHandle(context, instance)
    }
  }
}
