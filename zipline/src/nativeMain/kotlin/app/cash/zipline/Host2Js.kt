@file:OptIn(ExperimentalForeignApi::class)

package app.cash.zipline

import app.cash.zipline.hermes.HermesBridge_callFunctionWithArg
import app.cash.zipline.hermes.HermesBridge_callFunctionWithArgs2
import app.cash.zipline.hermes.HermesBridge_createBool
import app.cash.zipline.hermes.HermesBridge_createDouble
import app.cash.zipline.hermes.HermesBridge_createInt
import app.cash.zipline.hermes.HermesBridge_createNull
import app.cash.zipline.hermes.HermesBridge_createString
import app.cash.zipline.hermes.HermesBridge_defineProperty
import app.cash.zipline.hermes.HermesBridge_freeHandle
import app.cash.zipline.hermes.HermesBridge_getProperty
import app.cash.zipline.hermes.HermesBridge_newArray
import app.cash.zipline.hermes.HermesBridge_newObjectWithPrototype
import app.cash.zipline.hermes.HermesBridge_setArrayElement
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi

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
  val result = when (args.size) {
    1 -> HermesBridge_callFunctionWithArg(ctx, factory, args[0])
    2 -> HermesBridge_callFunctionWithArgs2(ctx, factory, args[0], args[1])
    else -> -1
  }
  HermesBridge_freeHandle(ctx, factory)
  if (result < 0) error("HOST2JS: the guest $name runtime factory failed")
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

    is String -> {
      val handle = HermesBridge_createString(ctx, value)
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

/**
 * Refuse to convert an enum host -> JS.
 *
 * The guest's enum instances are the singletons from its own `values()`; an object built here from
 * the retained prototype would only *look* like one. Its ordinal would be a property the guest
 * never reads, and `when`/identity comparisons against the real constants would fail, so the guest
 * could not use the value as an enum at all. Sending such a lookalike hides that, so nothing is
 * sent: the conversion fails where the mistake is.
 */
public fun host2JsRefuseEnum(fqName: String): Nothing =
  error("HOST2JS: '$fqName' is an enum; the guest would only see a lookalike, not an enum instance")
