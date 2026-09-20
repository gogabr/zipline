/*
 * Copyright (C) 2021 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:OptIn(ExperimentalForeignApi::class)

package app.cash.zipline

import app.cash.zipline.hermes.*
import kotlinx.cinterop.*

// Tag constants matching HermesBridge_getValueTag in hermes-ios.h.
private const val TAG_NULL = 0
private const val TAG_INT = 1
private const val TAG_DOUBLE = 2
private const val TAG_STRING = 3
private const val TAG_BOOL = 4
private const val TAG_OBJECT = 5
private const val TAG_ARRAY = 6
private const val TAG_UNDEFINED = 7

/**
 * Read a JS number as Long, handling Hermes int/double tags and boxed Kotlin/JS Long values (whose
 * halves the guest reports through its value ops: Kotlin/JS mangles `low`/`high`).
 */
fun JsNumberToLong(context: COpaquePointer?, handle: Int): Long {
  if (context == null) return 0L
  return when (HermesBridge_getValueTag(context, handle)) {
    TAG_INT -> HermesBridge_getValueDouble(context, handle).toInt().toLong()
    TAG_DOUBLE -> HermesBridge_getValueDouble(context, handle).toLong()
    else -> jsLongValue(context, handle)
  }
}

/** True when [handle] is a boxed kotlin.Long, as the guest's value ops report it. */
private fun jsIsLong(ctx: COpaquePointer?, handle: Int): Boolean {
  if (ctx == null) return false
  if (HermesBridge_getValueTag(ctx, handle) != TAG_OBJECT) return false
  val low = classifyValueOp(ctx, "longLow", handle)
  if (low < 0) return false
  val isInt = HermesBridge_getValueTag(ctx, low) == TAG_INT
  HermesBridge_freeHandle(ctx, low)
  return isInt
}

/**
 * The value of the boxed kotlin.Long in [handle]; fails loudly when it is not one, so a Long field
 * decoded without the guest's value ops surfaces instead of silently reading zero.
 */
private fun jsLongValue(ctx: COpaquePointer?, handle: Int): Long {
  val low = callValueOp(ctx, "longLow", handle)
  try {
    if (HermesBridge_getValueTag(ctx, low) == TAG_INT) {
      val high = callValueOp(ctx, "longHigh", handle)
      val highInt = try {
        if (HermesBridge_getValueTag(ctx, high) == TAG_INT) {
          HermesBridge_getValueDouble(ctx, high).toInt()
        } else {
          0
        }
      } finally {
        HermesBridge_freeHandle(ctx, high)
      }
      val lowInt = HermesBridge_getValueDouble(ctx, low).toInt()
      return (highInt.toLong() shl 32) or (lowInt.toLong() and 0xFFFFFFFFL)
    }
  } finally {
    HermesBridge_freeHandle(ctx, low)
  }
  error("host bridge: not a kotlin.Long: the guest value ops reported no low/high halves for this value")
}

/**
 * Reads the numeric payload of a BOXED Kotlin value class instance as Double.
 * Kotlin/JS boxes value-class fields with custom members into plain JS objects whose single
 * backing field is hash-mangled and exposes no accessor. Scan the instance's OWN enumerable
 * properties and return the first numeric value; a Boxed Kotlin/JS Long (e.g. a value class over
 * Long) is read through the guest's value ops first. Returns null when the value is not an object
 * or has no numeric payload.
 *
 * Still reachable: the Kotlin/Native generator emits this for inline value-class fields and for
 * value-class elements of collections (a List<Color> arrives boxed element by element).
 */
fun JsBoxedNumberToDouble(context: COpaquePointer?, handle: Int): Double? {
  if (context == null) return null
  if (HermesBridge_getValueTag(context, handle) != TAG_OBJECT) return null
  if (jsIsLong(context, handle)) return jsLongValue(context, handle).toDouble()
  val namesHandle = HermesBridge_getObjectPropertyNames(context, handle)
  if (namesHandle == 0) return null
  try {
    val count = HermesBridge_getArrayLength(context, namesHandle)
    var i = 0
    while (i < count) {
      val nameRef = HermesBridge_createArrayElementHandle(context, namesHandle, i)
      val namePtr = HermesBridge_getValueString(context, nameRef)
      val name = namePtr?.toKStringFromUtf8()?.also { platform.posix.free(namePtr) }
      HermesBridge_freeHandle(context, nameRef)
      if (name != null) {
        val propRef = HermesBridge_createHandle(context, handle, name)
        try {
          when (HermesBridge_getValueTag(context, propRef)) {
            TAG_INT, TAG_DOUBLE -> return HermesBridge_getValueDouble(context, propRef)
          }
        } finally {
          HermesBridge_freeHandle(context, propRef)
        }
      }
      i++
    }
    return null
  } finally {
    HermesBridge_freeHandle(context, namesHandle)
  }
}

/**
 * Reads the numeric payload of a BOXED Kotlin value class instance as Long (e.g. Color over Long).
 * Same scanning strategy as [JsBoxedNumberToDouble], additionally handling a Kotlin/JS Long
 * payload, which is read through the guest's value ops. Returns null when there is no numeric
 * payload.
 *
 * Still reachable: the Kotlin/Native generator emits this for inline value-class fields and for
 * value-class elements of collections (a List<Color> arrives boxed element by element).
 */
fun JsBoxedNumberToLong(context: COpaquePointer?, handle: Int): Long? {
  if (context == null) return null
  if (HermesBridge_getValueTag(context, handle) != TAG_OBJECT) return null
  // An UNBOXED Kotlin/JS Long: return null for it so the caller falls back to JsNumberToLong
  // (which packs low/high correctly) instead of scanning and reading just the low 32 bits.
  if (jsIsLong(context, handle)) return null
  val namesHandle = HermesBridge_getObjectPropertyNames(context, handle)
  if (namesHandle == 0) return null
  try {
    val count = HermesBridge_getArrayLength(context, namesHandle)
    var i = 0
    while (i < count) {
      val nameRef = HermesBridge_createArrayElementHandle(context, namesHandle, i)
      val namePtr = HermesBridge_getValueString(context, nameRef)
      val name = namePtr?.toKStringFromUtf8()?.also { platform.posix.free(namePtr) }
      HermesBridge_freeHandle(context, nameRef)
      if (name != null) {
        val propRef = HermesBridge_createHandle(context, handle, name)
        try {
          when (HermesBridge_getValueTag(context, propRef)) {
            TAG_INT, TAG_DOUBLE -> return HermesBridge_getValueDouble(context, propRef).toLong()
            TAG_OBJECT -> if (jsIsLong(context, propRef)) return jsLongValue(context, propRef)
          }
        } finally {
          HermesBridge_freeHandle(context, propRef)
        }
      }
      i++
    }
    return null
  } finally {
    HermesBridge_freeHandle(context, namesHandle)
  }
}

/**
 * The guest's value accessors (`globalThis.__zipline_bridgeValueOps`), published by
 * `app.cash.zipline.publishValueOps()` from the bridge plugin's module-load hook. Kotlin/JS
 * mangles the member names of the stdlib collections (production builds drop the original names
 * entirely) and there is no single collection prototype to mark, so the guest answers the type
 * question with the compiler's own `is` check and drives the iteration. Returns a handle, or -1
 * when the guest never published them.
 */
private fun valueOps(ctx: COpaquePointer?): Int =
  HermesBridge_getProperty(ctx, 0, "__zipline_bridgeValueOps")

/**
 * Call the guest value op [name] with [argument] and return a handle to the result, or -1 when
 * the ops object, the op or the value is unavailable.
 *
 * Lenient by design: this is used to *classify* a value whose type the host does not know, where
 * the structural probes it replaces also gave up quietly. Conversions that need one specific op
 * use [callValueOp] and fail loudly instead of degrading to an empty collection or a zero.
 */
private fun classifyValueOp(ctx: COpaquePointer?, name: String, argument: Int): Int {
  if (ctx == null) return -1
  val ops = valueOps(ctx)
  if (ops < 0) return -1
  try {
    val fn = HermesBridge_getProperty(ctx, ops, name)
    if (fn < 0) return -1
    try {
      return HermesBridge_callFunctionWithArg(ctx, fn, argument)
    } finally {
      HermesBridge_freeHandle(ctx, fn)
    }
  } finally {
    HermesBridge_freeHandle(ctx, ops)
  }
}

/**
 * Call the guest value op [name] with [argument] and return a handle to the result.
 *
 * Fails loudly when the guest published no value ops (a module the bridge plugin did not compile)
 * or does not define [name] (a plugin/guest version mismatch): both used to turn into a silently
 * empty collection or a zeroed Long far from the cause.
 */
private fun callValueOp(ctx: COpaquePointer?, name: String, argument: Int): Int {
  val ops = if (ctx != null) valueOps(ctx) else -1
  if (ops < 0) {
    error(
      "host bridge: the value op `$name` requires globalThis.__zipline_bridgeValueOps, which the " +
        "guest did not publish; the guest module was not compiled with the Zipline bridge plugin",
    )
  }
  try {
    val fn = HermesBridge_getProperty(ctx, ops, name)
    if (fn < 0) {
      error(
        "host bridge: the guest value ops object does not define `$name`; the guest module was " +
          "compiled with an incompatible Zipline bridge plugin",
      )
    }
    try {
      val result = HermesBridge_callFunctionWithArg(ctx, fn, argument)
      if (result < 0) error("host bridge: the guest value op `$name` failed")
      return result
    } finally {
      HermesBridge_freeHandle(ctx, fn)
    }
  } finally {
    HermesBridge_freeHandle(ctx, ops)
  }
}

/**
 * Ordinal of the enum instance at [handle], or -1 when it is not an enum. The guest reports it
 * through its value ops (Kotlin/JS mangles `ordinal`); there is deliberately no fallback for an
 * object the host built for a host->JS conversion: such an object is not an enum to the guest, so
 * reading its ordinal back would only make a round trip look correct.
 */
public fun jsEnumOrdinal(ctx: COpaquePointer?, handle: Int): Int {
  val ordinal = classifyValueOp(ctx, "enumOrdinal", handle)
  if (ordinal < 0) return -1
  try {
    return when (HermesBridge_getValueTag(ctx, ordinal)) {
      TAG_INT, TAG_DOUBLE -> HermesBridge_getValueDouble(ctx, ordinal).toInt()
      else -> -1
    }
  } finally {
    HermesBridge_freeHandle(ctx, ordinal)
  }
}

/**
 * How the guest classified an opaque JS value, from the value-ops `kind` call. The codes are the
 * guest's own (`BridgeValueOps` on the JS side). A plain JS array is never one of these: it is
 * decoded structurally, before the kind is consulted.
 */
public enum class CollectionKind(val code: Int) {
  NONE(0),
  MAP(1),
  SET(2),
  LIST(3),
  ;

  public companion object {
    /** The kind [code] stands for, or [NONE] when the guest sent a code this build doesn't know. */
    public fun of(code: Int): CollectionKind = when (code) {
      MAP.code -> MAP
      SET.code -> SET
      LIST.code -> LIST
      else -> NONE
    }
  }
}

/** The guest's classification of [handle], or [CollectionKind.NONE] when it is not a collection. */
public fun collectionKind(ctx: COpaquePointer?, handle: Int): CollectionKind {
  if (ctx == null) return CollectionKind.NONE
  if (HermesBridge_getValueTag(ctx, handle) != TAG_OBJECT) return CollectionKind.NONE
  val kindResult = classifyValueOp(ctx, "kind", handle)
  if (kindResult < 0) return CollectionKind.NONE
  try {
    val code = when (HermesBridge_getValueTag(ctx, kindResult)) {
      TAG_INT, TAG_DOUBLE -> HermesBridge_getValueDouble(ctx, kindResult).toInt()
      else -> CollectionKind.NONE.code
    }
    return CollectionKind.of(code)
  } finally {
    HermesBridge_freeHandle(ctx, kindResult)
  }
}

/**
 * Builds a LinkedHashSet ([CollectionKind.SET]) or ArrayList ([CollectionKind.LIST]) from the
 * guest's own iterator. A list the guest pinned as a plain JS array is read directly.
 *
 * [converter] receives a handle to each element and frees nothing: this function owns the handle
 * lifecycle for what it passes in.
 */
public fun jsCollectionToKotlin(
  ctx: COpaquePointer?,
  handle: Int,
  kind: CollectionKind,
  converter: (Int) -> Any?,
): MutableCollection<Any?> {
  // Fast path: a list backed by a plain JS array has no Kotlin iterator to drive.
  if (kind == CollectionKind.LIST && HermesBridge_getValueTag(ctx, handle) == TAG_ARRAY) {
    val length = HermesBridge_getArrayLength(ctx, handle).coerceAtLeast(0)
    val result = ArrayList<Any?>(length)
    var index = 0
    while (index < length) {
      val element = HermesBridge_createArrayElementHandle(ctx, handle, index)
      try {
        result.add(converter(element))
      } finally {
        HermesBridge_freeHandle(ctx, element)
      }
      index++
    }
    return result
  }

  val result: MutableCollection<Any?> =
    if (kind == CollectionKind.SET) LinkedHashSet() else ArrayList()
  val iterator = callValueOp(ctx, "iterator", handle)
  try {
    while (true) {
      val hasNext = callValueOp(ctx, "hasNext", iterator)
      val more = try {
        HermesBridge_getValueTag(ctx, hasNext) == TAG_BOOL &&
          HermesBridge_getValueBool(ctx, hasNext) != 0
      } finally {
        HermesBridge_freeHandle(ctx, hasNext)
      }
      if (!more) break
      val element = callValueOp(ctx, "next", iterator)
      try {
        result.add(converter(element))
      } finally {
        HermesBridge_freeHandle(ctx, element)
      }
    }
  } finally {
    HermesBridge_freeHandle(ctx, iterator)
  }
  return result
}

/**
 * Converts a Kotlin/JS Map instance into a Kotlin Map by walking the guest's own iterator protocol
 * (iterator -> hasNext/next -> key/value) and converting each key/value pair with the supplied
 * converters. Used by generated bridge code for Map fields.
 *
 * The converters receive a handle to the key/value and free nothing: this function owns the handle
 * lifecycle for what it passes in.
 */
public fun jsMapToKotlin(
  ctx: COpaquePointer?,
  handle: Int,
  key: (Int) -> Any?,
  value: (Int) -> Any?,
): MutableMap<Any?, Any?> {
  val result = LinkedHashMap<Any?, Any?>()
  val iterator = callValueOp(ctx, "iterator", handle)
  try {
    while (true) {
      val hasNext = callValueOp(ctx, "hasNext", iterator)
      val more = try {
        HermesBridge_getValueTag(ctx, hasNext) == TAG_BOOL &&
          HermesBridge_getValueBool(ctx, hasNext) != 0
      } finally {
        HermesBridge_freeHandle(ctx, hasNext)
      }
      if (!more) break
      val entry = callValueOp(ctx, "next", iterator)
      try {
        val rawKey = callValueOp(ctx, "key", entry)
        try {
          val rawValue = callValueOp(ctx, "value", entry)
          try {
            result[key(rawKey)] = value(rawValue)
          } finally {
            HermesBridge_freeHandle(ctx, rawValue)
          }
        } finally {
          HermesBridge_freeHandle(ctx, rawKey)
        }
      } finally {
        HermesBridge_freeHandle(ctx, entry)
      }
    }
  } finally {
    HermesBridge_freeHandle(ctx, iterator)
  }
  return result
}

/**
 * Throws when [handle] looks like a Kotlin class instance that no bridge converter was registered
 * for, naming the class; returns null for plain data shapes (JS objects, Kotlin collections),
 * which is how they decoded before. See the call site in [bridgeForAny].
 */
private fun throwUnbridgedJsObject(ctx: COpaquePointer?, handle: Int): Any? {
  // A function value is not a data payload (zipline has no function bridge), so leave it alone.
  if (HermesBridge_isFunction(ctx, handle) != 0) return null
  val ctor = HermesBridge_getProperty(ctx, handle, "constructor")
  if (ctor < 0) return null
  try {
    if (HermesBridge_isFunction(ctx, ctor) == 0) return null
    val nameHandle = HermesBridge_getProperty(ctx, ctor, "name")
    if (nameHandle < 0) return null
    val namePtr = HermesBridge_getValueString(ctx, nameHandle)
    HermesBridge_freeHandle(ctx, nameHandle)
    val className = namePtr?.toKStringFromUtf8()?.also { platform.posix.free(namePtr) }.orEmpty()
    if (className.isEmpty() || className in UNBRIDGED_DECODE_EXEMPT_CLASSES) return null
    throw IllegalStateException(
      "host bridge: no converter registered for JS class '$className'; " +
        "annotate the class with @WithJS2HostBridge to send it to the host",
    )
  } finally {
    HermesBridge_freeHandle(ctx, ctor)
  }
}

/**
 * JS built-ins (plain objects, arrays, dates, …) and Kotlin's collection/Long wrappers decode
 * without a bridge converter; everything else that arrives as a class instance must have one.
 */
private val UNBRIDGED_DECODE_EXEMPT_CLASSES = setOf(
  "Object", "Array", "Function", "Date", "RegExp", "Error", "Promise", "Symbol",
  "Number", "String", "Boolean", "BigInt", "JSON", "Math", "Reflect", "Proxy",
  "ArrayBuffer", "DataView", "Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array",
  "Uint16Array", "Int32Array", "Uint32Array", "Float32Array", "Float64Array",
  "BigInt64Array", "BigUint64Array", "Map", "Set", "WeakMap", "WeakSet",
  "LinkedHashMap", "HashMap", "ArrayList", "LinkedHashSet", "HashSet", "ArrayDeque", "Long",
  // kotlin.Unit: the result of a Unit-returning guest function (e.g. the direct-event sink).
  "Unit",
)

/**
 * Dispatch a single JS value to its Kotlin equivalent using bridge dispatch for objects.
 * Used by generated bridge code for elements of unknown type (Any? fields, List elements).
 */
fun bridgeForAny(context: COpaquePointer?, handle: Int): Any? {
  if (context == null) return null
  return when (HermesBridge_getValueTag(context, handle)) {
    TAG_INT, TAG_DOUBLE -> HermesBridge_getValueDouble(context, handle)
    TAG_BOOL -> HermesBridge_getValueBool(context, handle) != 0
    TAG_STRING -> {
      val str = HermesBridge_getValueString(context, handle)
      str?.toKStringFromUtf8()?.also { platform.posix.free(str) }
    }
    TAG_NULL, TAG_UNDEFINED -> null
    TAG_ARRAY -> {
      val length = HermesBridge_getArrayLength(context, handle)
      (0 until length).map { index ->
        val elementRef = HermesBridge_createArrayElementHandle(context, handle, index)
        try {
          bridgeForAny(context, elementRef)
        } finally {
          HermesBridge_freeHandle(context, elementRef)
        }
      }
    }
    else -> {
      // Kotlin/JS collection (map/set/list): the guest identifies it and drives the iteration.
      // Consulted *before* the dispatch pointer - a Kotlin/JS collection carries no bridge
      // dispatch, so the previous order decoded it as null.
      when (val kind = collectionKind(context, handle)) {
        CollectionKind.MAP -> return jsMapToKotlin(
          context, handle,
          { key -> bridgeForAny(context, key) },
          { value -> bridgeForAny(context, value) },
        )
        CollectionKind.SET, CollectionKind.LIST -> return jsCollectionToKotlin(
          context, handle, kind,
        ) { element -> bridgeForAny(context, element) }
        CollectionKind.NONE -> Unit
      }
      val dispPtr = HermesBridge_getBridgeDispatch(context, handle)
      if (dispPtr != 0L) {
        val fn = dispPtr.toCPointer<CFunction<(COpaquePointer?, Int) -> COpaquePointer?>>()!!
        fn(context, handle)?.asStableRef<Any>()?.get()
      } else {
        // A boxed kotlin.Long: the guest identifies it (Kotlin/JS mangles the fields of the box).
        if (jsIsLong(context, handle)) return jsLongValue(context, handle)
        // No converter: a class instance that cannot be decoded. Returning null here used to
        // surface as a confusing cast/NPE far from the cause (e.g. a List<TopBarIcon> arriving as
        // [null] because TopBarIcon was not annotated), so name the class instead.
        throwUnbridgedJsObject(context, handle)
      }
    }
  }
}
