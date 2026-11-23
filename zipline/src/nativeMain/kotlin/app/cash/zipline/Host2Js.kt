@file:OptIn(ExperimentalForeignApi::class)

package app.cash.zipline

import app.cash.zipline.hermes.HermesBridge_callFunctionWithArg
import app.cash.zipline.hermes.HermesBridge_callFunctionWithArgs2
import app.cash.zipline.hermes.HermesBridge_createBool
import app.cash.zipline.hermes.HermesBridge_createDouble
import app.cash.zipline.hermes.HermesBridge_createInt
import app.cash.zipline.hermes.HermesBridge_createNull
import app.cash.zipline.hermes.HermesBridge_createString
import app.cash.zipline.hermes.HermesBridge_createStringUtf8
import app.cash.zipline.hermes.HermesBridge_defineProperty
import app.cash.zipline.hermes.HermesBridge_freeHandle
import app.cash.zipline.hermes.HermesBridge_getProperty
import app.cash.zipline.hermes.HermesBridge_newArray
import app.cash.zipline.hermes.HermesBridge_newObjectWithPrototype
import app.cash.zipline.hermes.HermesBridge_setArrayElement
import app.cash.zipline.hermes.HermesContext_getLastError
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned

/**
 * Implemented by classes annotated with `@WithHost2JSBridge` on Kotlin/Native. The bridge compiler
 * plugin injects this supertype and an `override` of [convertToJs] whose body builds a counterpart
 * JS object (prototype-based, recursively converting fields) in [ctx].
 *
 * [anyToJs] dispatches to this interface for object values; a value that implements neither this
 * interface nor any built-in conversion is an assertion error, never a silent null.
 */
public interface Host2JsConvertible {
  /** Build a JS counterpart of this object in [ctx]; the caller owns the returned handle. */
  public fun convertToJs(ctx: COpaquePointer?): Int
}

/**
 * Create a new JS instance whose prototype is the guest prototype registered for [fq] (the guest's
 * module-load `__bridgeRegister` call retains it). The prototype is never created or cloned; the
 * caller owns the returned handle.
 *
 * Fails loudly when no prototype is registered: `undefined` is a legitimate payload value, so no JS
 * sentinel may stand in for a missing prototype.
 */
public fun newJsObject(ctx: COpaquePointer?, fq: String): Int {
  val handle = HermesBridge_newObjectWithPrototype(ctx, fq)
  if (handle < 0) {
    error(
      "HOST2JS: no registered prototype for $fq; " +
        "the guest module did not call __bridgeRegister for this class",
    )
  }
  return handle
}

/**
 * Define [name] on the object at [objHandle] as an own data property (writable, enumerable,
 * configurable) holding the value at [valueHandle]. Defining - not assigning - shadows any
 * getter-only accessor the guest class prototype may carry. The caller keeps ownership of
 * [valueHandle].
 */
public fun setJsProperty(ctx: COpaquePointer?, objHandle: Int, name: String, valueHandle: Int) {
  HermesBridge_defineProperty(ctx, objHandle, name, valueHandle)
}

/**
 * Hand the host's fields object to the guest's generated `create_[factoryName]` and return the instance
 * the guest builds from it. The guest runs the class's own constructor on the values, so its initializer
 * blocks, computed properties, validation and default expressions all happen — which a prototype-stamped
 * lookalike skips. The fields object is the stamped instance the host was already going to send: it
 * carries the class's prototype, and the factory reads only the host's own properties off it.
 *
 * A guest that publishes no such factory is reported, not worked around: the alternative was the
 * field-stamped lookalike this path replaced, which skips the constructor.
 */
public fun host2JsCallCreateFactory(
  ctx: COpaquePointer?,
  fqName: String,
  factoryName: String,
  fieldsHandle: Int,
): Int {
  val factory = factoryFunction(ctx, factoryName)
  if (factory < 0) {
    error(
      "HOST2JS: the guest provides no $factoryName for $fqName; this class shape gets no " +
        "create-factory (a guest built without create-factories, or a value-class constructor " +
        "parameter). What the host could see: ${host2JsRegistrationState(ctx, fqName)}",
    )
  }
  val instance = callOnFactory(ctx, factory, intArrayOf(fieldsHandle))
  if (instance < 0) {
    // Say what the guest said. The guest's factory failed inside JS, and its message names the class
    // and the field — which is worth more here than a second report that a call did not succeed. The
    // host's own wording survives only for the case where JS had nothing to say.
    val guestMessage = HermesContext_getLastError(ctx)?.toKString()
    error(
      guestMessage
        ?: "HOST2JS: the guest's $factoryName for $fqName produced no value and reported nothing",
    )
  }
  return instance
}

/**
 * Hand the ordinal of an `@WithHost2JSBridge` enum entry to the guest's `create_<fqn>` factory and
 * return the guest's own singleton entry at that ordinal. The ordinal crosses as a JS number; the
 * temporary handle is freed in a `finally` so an out-of-range ordinal — reported by the guest —
 * never leaks it.
 */
public fun host2JsCallEnumFactory(
  ctx: COpaquePointer?,
  fqName: String,
  factoryName: String,
  ordinal: Int,
): Int {
  val ordinalHandle = newJsInt(ctx, ordinal)
  try {
    return host2JsCallCreateFactory(ctx, fqName, factoryName, ordinalHandle)
  } finally {
    HermesBridge_freeHandle(ctx, ordinalHandle)
  }
}

/** Build a JS number from [value] as a handle; the caller owns it. */
public fun newJsInt(ctx: COpaquePointer?, value: Int): Int {
  val handle = HermesBridge_createInt(ctx, value)
  if (handle < 0) error("HOST2JS: failed to create a JS number")
  return handle
}

/**
 * Build a JS boolean from [value] as a handle; the caller owns it. A Boolean has to be a real JS
 * boolean, not 1/0: the guest reads Boolean fields and elements with the boolean tag.
 */
private fun newJsBool(ctx: COpaquePointer?, value: Boolean): Int {
  val handle = HermesBridge_createBool(ctx, if (value) 1 else 0)
  if (handle < 0) error("HOST2JS: failed to create a JS boolean")
  return handle
}

/**
 * The guest's host2js runtime factories (`globalThis.__zipline_bridgeFactories`, published by
 * `__bridgeRegisterRuntime` from the bridge plugin's module-load hook). Looked up per call rather
 * than cached: a handle belongs to one runtime, and a missing factory is a loud failure below.
 */
private fun factoryFunction(ctx: COpaquePointer?, name: String): Int {
  val factories = HermesBridge_getProperty(ctx, 0, "__zipline_bridgeFactories")
  if (factories < 0) return -1
  val factory = HermesBridge_getProperty(ctx, factories, name)
  HermesBridge_freeHandle(ctx, factories)
  return factory
}

/** Call the guest's [name] factory with the handles in [args]; the caller owns the result. */
private fun callFactory(ctx: COpaquePointer?, name: String, args: IntArray): Int {
  val factory = factoryFunction(ctx, name)
  if (factory < 0) {
    error(
      "HOST2JS: no registered $name runtime factory; " +
        "the guest module did not call __bridgeRegisterRuntime",
    )
  }
  val result = callOnFactory(ctx, factory, args)
  if (result < 0) error("HOST2JS: the guest $name runtime factory failed")
  return result
}

/**
 * What the host could actually see when a create-factory was missing, appended to the report: three
 * different failures answer to "the guest provides no create_X" -- no module published a factories
 * object, a registration arrived empty, or other modules registered and this class's module did not.
 * The prototype question separates the last one, because prototypes reach the host by a different route
 * than the factories do. The JNI host says the same thing in `bridge_dispatch.h`.
 */
private fun host2JsRegistrationState(ctx: COpaquePointer?, fqName: String): String {
  val factories = HermesBridge_getProperty(ctx, 0, "__zipline_bridgeFactories")
  val published =
    if (factories < 0) {
      "the factories global is absent (no module published one, or __bridgeRegisterRuntime was never " +
        "installed on this runtime)"
    } else {
      val newLong = HermesBridge_getProperty(ctx, factories, "newLong")
      HermesBridge_freeHandle(ctx, factories)
      if (newLong >= 0) {
        "a factories object is present and its newLong is reachable, so some module registered and " +
          "this class's module did not"
      } else {
        "a factories object is present but its newLong is not reachable, so a registration arrived " +
          "empty"
      }
    }
  // The warm-up bookkeeping the C++ layer keeps per module: which modules had their hook found and
  // called, and which were skipped with which verdict. Without it, "this module published no
  // factories" and "the host never warmed this module up" are the same message.
  val warmUp = jsiDiagnosticGlobal(ctx, "__zipline_bridgeWarmUpStatus") +
    "; hooks called for:" + jsiDiagnosticGlobal(ctx, "__zipline_bridgeWarmUpCalled") +
    "; missed:" + jsiDiagnosticGlobal(ctx, "__zipline_bridgeWarmUpMissed")
  val prototypes = HermesBridge_getProperty(ctx, 0, "__zipline_bridgePrototypes")
  val registered =
    if (prototypes < 0) {
      "the prototypes global is absent"
    } else {
      val proto = HermesBridge_getProperty(ctx, prototypes, fqName)
      HermesBridge_freeHandle(ctx, prototypes)
      if (proto >= 0) "this class registered its prototype" else "this class registered no prototype"
    }
  return "$published; $registered; warm-up $warmUp"
}

// Reads one of the diagnostic globals the C++ warm-up bookkeeping maintains, capped: it exists to be
// printed inside an exception message, and a bundle with dozens of modules must not turn that report
// into a wall of ids.
private fun jsiDiagnosticGlobal(ctx: COpaquePointer?, name: String, limit: Int = 320): String {
  val handle = HermesBridge_getProperty(ctx, 0, name)
  if (handle < 0) return " <unset>"
  val value = bridgeJsString(ctx, handle) ?: return " <not a string>"
  return if (value.length <= limit) value.ifEmpty { " <none>" } else value.take(limit) + "..."
}

/**
 * Call the already-looked-up [factory]; the caller owns the result and reports the failure in its own
 * words. It must: a create-factory that threw has already said why in JS, and a caller that reports
 * first buries that message under a second-hand "a call did not succeed".
 */
private fun callOnFactory(ctx: COpaquePointer?, factory: Int, args: IntArray): Int {
  val result = when (args.size) {
    1 -> HermesBridge_callFunctionWithArg(ctx, factory, args[0])
    2 -> HermesBridge_callFunctionWithArgs2(ctx, factory, args[0], args[1])
    else -> -1
  }
  HermesBridge_freeHandle(ctx, factory)
  return result
}

/**
 * Convert a Kotlin [Long] to a real Kotlin/JS `kotlin.Long` instance (correct prototype and
 * methods) by calling the guest's registered `newLong` factory with the low/high halves. The
 * caller owns the returned handle.
 */
public fun kotlinLongToJs(ctx: COpaquePointer?, value: Long): Int {
  val low = newJsInt(ctx, value.toInt())
  val high = newJsInt(ctx, (value shr 32).toInt())
  try {
    return callFactory(ctx, "newLong", intArrayOf(low, high))
  } finally {
    HermesBridge_freeHandle(ctx, high)
    HermesBridge_freeHandle(ctx, low)
  }
}

/** Build a JS array holding [size] converted elements; the caller owns the returned handle. */
private fun jsArrayFrom(ctx: COpaquePointer?, size: Int, convert: (Int) -> Int): Int {
  val arrayHandle = HermesBridge_newArray(ctx)
  if (arrayHandle < 0) error("HOST2JS: failed to allocate a JS array")
  try {
    for (index in 0 until size) {
      val elementHandle = convert(index)
      try {
        HermesBridge_setArrayElement(ctx, arrayHandle, index, elementHandle)
      } finally {
        HermesBridge_freeHandle(ctx, elementHandle)
      }
    }
    return arrayHandle
  } catch (e: Throwable) {
    HermesBridge_freeHandle(ctx, arrayHandle)
    throw e
  }
}

/**
 * A JS string carrying [value], NUL and all.
 *
 * Not `HermesBridge_createString(ctx, value)`: cinterop converts a `const char*` argument with
 * `toCString`, so the C++ side never sees anything past a NUL and the guest received `"a"` for
 * `"a\u0000b"` with nothing complaining on either side. Encoding to bytes and pinning them keeps
 * the length, which is the one thing a `String` has that a C string cannot carry.
 */
internal fun bridgeCreateJsString(ctx: COpaquePointer?, value: String): Int {
  val bytes = value.encodeToByteArray()
  if (bytes.isEmpty()) return HermesBridge_createString(ctx, "")
  return bytes.usePinned { pinned ->
    HermesBridge_createStringUtf8(ctx, pinned.addressOf(0).reinterpret(), bytes.size)
  }
}

/**
 * Convert an arbitrary host value to its JS counterpart in [ctx]. The caller owns the returned
 * handle.
 *
 * - Primitives, [String] and arrays convert to the corresponding JS primitives/arrays.
 * - [Long] values become real `kotlin.Long` instances via the guest's `newLong` factory.
 * - [List] values become real Kotlin/JS `ArrayList` instances via the guest's `newArrayList`
 *   factory; [Map] values become `LinkedHashMap` instances via `newLinkedHashMap` (keys and values
 *   converted recursively). These match the shapes the JS-to-host readers expect, so converted
 *   collections round-trip.
 * - Anything implementing [Host2JsConvertible] (i.e. `@WithHost2JSBridge` classes) dispatches to
 *   its [Host2JsConvertible.convertToJs].
 *
 * Anything else is an assertion error, never a silent null; the only null produced here is for an
 * actual [null] data value.
 */

public fun anyToJs(ctx: COpaquePointer?, value: Any?): Int {
  return when (value) {
    null -> HermesBridge_createNull(ctx).also {
      if (it < 0) error("HOST2JS: failed to create a JS null")
    }

    is Boolean -> newJsBool(ctx, value)

    is Int -> newJsInt(ctx, value)

    is Byte -> newJsInt(ctx, value.toInt())

    is Short -> newJsInt(ctx, value.toInt())

    is Char -> newJsInt(ctx, value.code)

    is Float -> {
      val handle = HermesBridge_createDouble(ctx, value.toDouble())
      if (handle < 0) error("HOST2JS: failed to create a JS number")
      handle
    }

    is Double -> {
      val handle = HermesBridge_createDouble(ctx, value)
      if (handle < 0) error("HOST2JS: failed to create a JS number")
      handle
    }

    is Long -> kotlinLongToJs(ctx, value)

    // Narrow and unsigned scalars reach the guest as numbers, as the array arms below already do for
    // `ByteArray`/`CharArray`/`ShortArray`. The rule is that the *bits* travel and the receiver
    // reinterprets them, which is what a `UInt` field of a bridged class already does on the
    // generated route and what the JNI host does for the same values: `UByte(200u)` crosses as the
    // byte `-56`, not as `200`. Zero-extending here instead would have made the two hosts put
    // different numbers on the wire for one payload.
    //
    // `ULong` keeps `Long`'s boxed low/high representation, so its 64 bits survive unchanged.
    is Byte -> newJsInt(ctx, value.toInt())

    is Short -> newJsInt(ctx, value.toInt())

    is Char -> newJsInt(ctx, value.code)

    is UByte -> newJsInt(ctx, value.toByte().toInt())

    is UShort -> newJsInt(ctx, value.toShort().toInt())

    is UInt -> newJsInt(ctx, value.toInt())

    is ULong -> kotlinLongToJs(ctx, value.toLong())

    is String -> {
      val handle = bridgeCreateJsString(ctx, value)
      if (handle < 0) error("HOST2JS: failed to create a JS string")
      handle
    }

    is List<*> -> {
      val jsArray = jsArrayFrom(ctx, value.size) { anyToJs(ctx, value[it]) }
      try {
        callFactory(ctx, "newArrayList", intArrayOf(jsArray))
      } finally {
        HermesBridge_freeHandle(ctx, jsArray)
      }
    }

    is Map<*, *> -> {
      // Two parallel JS arrays: the guest's newLinkedHashMap factory zips them into pairs and
      // builds a real LinkedHashMap (no internal-layout reliance, matches JS-created maps).
      val entries = value.entries.toList()
      val keys = jsArrayFrom(ctx, entries.size) { anyToJs(ctx, entries[it].key) }
      val values = jsArrayFrom(ctx, entries.size) { anyToJs(ctx, entries[it].value) }
      try {
        callFactory(ctx, "newLinkedHashMap", intArrayOf(keys, values))
      } finally {
        HermesBridge_freeHandle(ctx, values)
        HermesBridge_freeHandle(ctx, keys)
      }
    }

    is IntArray -> jsArrayFrom(ctx, value.size) { newJsInt(ctx, value[it]) }

    is BooleanArray -> jsArrayFrom(ctx, value.size) { newJsBool(ctx, value[it]) }

    is CharArray -> jsArrayFrom(ctx, value.size) { newJsInt(ctx, value[it].code) }

    is ShortArray -> jsArrayFrom(ctx, value.size) { newJsInt(ctx, value[it].toInt()) }

    is ByteArray -> jsArrayFrom(ctx, value.size) { newJsInt(ctx, value[it].toInt()) }

    is LongArray -> jsArrayFrom(ctx, value.size) { kotlinLongToJs(ctx, value[it]) }

    is FloatArray -> jsArrayFrom(ctx, value.size) {
      val handle = HermesBridge_createDouble(ctx, value[it].toDouble())
      if (handle < 0) error("HOST2JS: failed to create a JS number")
      handle
    }

    is DoubleArray -> jsArrayFrom(ctx, value.size) {
      val handle = HermesBridge_createDouble(ctx, value[it])
      if (handle < 0) error("HOST2JS: failed to create a JS number")
      handle
    }

    is Array<*> -> jsArrayFrom(ctx, value.size) { anyToJs(ctx, value[it]) }

    else -> (value as? Host2JsConvertible)?.convertToJs(ctx)
      ?: error("HOST2JS: no bridge for '${value::class.qualifiedName}'")
  }
}
