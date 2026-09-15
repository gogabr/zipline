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

import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.CValues
import kotlinx.cinterop.CVariable
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.NativePlacement
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKStringFromUtf8
import kotlinx.cinterop.value
import app.cash.zipline.quickjs.JSContext
import app.cash.zipline.quickjs.JSValue
import app.cash.zipline.quickjs.JS_Call
import app.cash.zipline.quickjs.JS_FreeValue
import app.cash.zipline.quickjs.JS_GetGlobalObject
import app.cash.zipline.quickjs.JS_GetPropertyStr
import app.cash.zipline.quickjs.JS_GetPropertyUint32
import app.cash.zipline.quickjs.JS_IsArray
import app.cash.zipline.quickjs.JS_IsBool
import app.cash.zipline.quickjs.JS_IsException
import app.cash.zipline.quickjs.JS_IsFunction
import app.cash.zipline.quickjs.JS_IsNull
import app.cash.zipline.quickjs.JS_IsNumber
import app.cash.zipline.quickjs.JS_IsString
import app.cash.zipline.quickjs.JS_TAG_BOOL
import app.cash.zipline.quickjs.JS_IsUndefined
import app.cash.zipline.quickjs.JS_TAG_FLOAT64
import app.cash.zipline.quickjs.JsUndefined
import app.cash.zipline.quickjs.JS_TAG_INT
import app.cash.zipline.quickjs.JS_TAG_OBJECT
import app.cash.zipline.quickjs.JS_ToCString
import app.cash.zipline.quickjs.JS_FreeCString
import app.cash.zipline.quickjs.JsValueGetBool
import app.cash.zipline.quickjs.JsValueGetFloat64
import app.cash.zipline.quickjs.JsValueGetInt
import app.cash.zipline.quickjs.JsValueGetNormTag
import app.cash.zipline.quickjs.JsGetOwnPropertyNames
import app.cash.zipline.quickjs.JsGetPropertyAt
import app.cash.zipline.quickjs.JsGetPropertyName
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.rawValue
import app.cash.zipline.quickjs.JsFreePropertyEnum


/** Copy the data of [item] to the [index] of [this] as if it were an array of [T] structs. */
internal inline operator fun <reified T : CVariable> CPointer<T>.set(index: Int, item: CValues<T>) {
  val offset = index * sizeOf<T>()
  item.place(interpretCPointer(rawValue + offset)!!)
}

/** Copy the values of [items] into a new array. */
internal inline fun <reified T : CVariable> NativePlacement.allocArrayOf(
  vararg items: CValues<T>,
): CArrayPointer<T> {
  val array = allocArray<T>(items.size)
  items.forEachIndexed { index, item ->
    array[index] = item
  }
  return array
}

/**
 * Read a JS number as Double, handling both JS_TAG_INT and JS_TAG_FLOAT64.
 * JS numbers may be encoded as either tag — JsValueGetFloat64 on an int-tagged
 * value reads garbage.
 */
@OptIn(ExperimentalForeignApi::class)
fun JsNumberToDouble(jsVal: CValue<JSValue>): Double = when (JsValueGetNormTag(jsVal)) {
  JS_TAG_INT -> JsValueGetInt(jsVal).toDouble()
  else -> JsValueGetFloat64(jsVal)
}

/**
 * Read a JS number as Int, handling both JS_TAG_INT and JS_TAG_FLOAT64.
 * JS numbers may be encoded as either tag — JsValueGetInt on a float64-tagged
 * value reads the double's low 32 bits (0 for any small integral double),
 * mirroring the JVM-side lesson documented in Context.cpp (raw readers misread
 * values whose numeric tag differs from the reader).
 *
 * Non-numeric tags (undefined/null/object) read as 0, preserving the previous
 * silent behavior of the raw getter on those tags.
 */
@OptIn(ExperimentalForeignApi::class)
fun JsNumberToInt(jsVal: CValue<JSValue>): Int = when (JsValueGetNormTag(jsVal)) {
  JS_TAG_INT -> JsValueGetInt(jsVal)
  JS_TAG_FLOAT64 -> JsValueGetFloat64(jsVal).toInt()
  else -> 0
}


/**
 * Reads the numeric payload of a BOXED Kotlin value class instance as Double.
 * See [JsBoxedNumberToLong]; this variant covers Int/Float/Double wrappers
 * whose backing payload is a plain JS number.
 */
@OptIn(ExperimentalForeignApi::class)
fun JsBoxedNumberToDouble(ctx: CPointer<JSContext>, jsVal: CValue<JSValue>): Double? {
  if (JsValueGetNormTag(jsVal) != JS_TAG_OBJECT) return null
  if (jsIsLong(ctx, jsVal)) return jsLongValue(ctx, jsVal).toDouble()
  return memScoped {
    val count = alloc<IntVar>()
    val ptab = JsGetOwnPropertyNames(ctx, jsVal, count.ptr) ?: return@memScoped null
    try {
      for (i in 0 until count.value) {
        val prop = JsGetPropertyAt(ctx, jsVal, ptab, i)
        try {
          if (JS_IsNumber(prop) != 0) {
            val tag = JsValueGetNormTag(prop)
            return@memScoped if (tag == JS_TAG_INT) JsValueGetInt(prop).toDouble()
            else JsValueGetFloat64(prop)
          }
        } finally {
          JS_FreeValue(ctx, prop)
        }
      }
      null
    } finally {
      JsFreePropertyEnum(ctx, ptab)
    }
  }
}

/**
 * Reads the numeric payload of a BOXED Kotlin value class instance (e.g. Color
 * over Long). Kotlin/JS boxes value-class fields with custom members into plain
 * JS objects whose single backing field is hash-mangled ('uoul_1'-style) and
 * exposes no accessor, so neither a named read nor a bridge converter can reach
 * it. A boxed Kotlin/JS Long itself is handled first (see [jsLongValue]);
 * otherwise scan the instance's OWN enumerable properties and take the first
 * numeric value (number or nested Kotlin/JS Long {low_1, high_1} object).
 * Returns null when the object has no numeric payload.
 */
@OptIn(ExperimentalForeignApi::class)
fun JsBoxedNumberToLong(ctx: CPointer<JSContext>, jsVal: CValue<JSValue>): Long? {
  if (JsValueGetNormTag(jsVal) != JS_TAG_OBJECT) return null
  // The guest reports the halves (Kotlin/JS mangles the box's fields and the stdlib's); only the
  // shape scan below remains for boxes nothing else recognizes.
  if (jsIsLong(ctx, jsVal)) return jsLongValue(ctx, jsVal)
  return memScoped {
    val count = alloc<IntVar>()
    val ptab = JsGetOwnPropertyNames(ctx, jsVal, count.ptr) ?: return@memScoped null
    try {
      for (i in 0 until count.value) {
        val prop = JsGetPropertyAt(ctx, jsVal, ptab, i)
        try {
          if (JS_IsNumber(prop) != 0) {
            val tag = JsValueGetNormTag(prop)
            return@memScoped if (tag == JS_TAG_INT) JsValueGetInt(prop).toLong()
            else JsValueGetFloat64(prop).toLong()
          }
          if (JsValueGetNormTag(prop) == JS_TAG_OBJECT) {
            val lo = JS_GetPropertyStr(ctx, prop, "low_1")
            val hi = JS_GetPropertyStr(ctx, prop, "high_1")
            val isLong = JS_IsUndefined(lo) == 0 && JS_IsUndefined(hi) == 0
            if (isLong) {
              val result = (JsNumberToInt(hi).toLong() shl 32) or (JsNumberToInt(lo).toLong() and 0xFFFFFFFF)
              JS_FreeValue(ctx, hi)
              JS_FreeValue(ctx, lo)
              return@memScoped result
            }
            JS_FreeValue(ctx, hi)
            JS_FreeValue(ctx, lo)
          }
        } finally {
          JS_FreeValue(ctx, prop)
        }
      }
      null
    } finally {
      JsFreePropertyEnum(ctx, ptab)
    }
  }
}

/**
 * Read a JS number as Long, handling JS_TAG_INT, JS_TAG_FLOAT64 and boxed kotlin.Long values
 * (whose halves the guest reports: Kotlin/JS mangles `low`/`high`).
 */
@OptIn(ExperimentalForeignApi::class)
fun JsNumberToLong(ctx: CPointer<JSContext>, jsVal: CValue<JSValue>): Long = when (JsValueGetNormTag(jsVal)) {
  JS_TAG_INT -> JsValueGetInt(jsVal).toLong()
  JS_TAG_FLOAT64 -> JsValueGetFloat64(jsVal).toLong()
  // A boxed kotlin.Long: the guest reports its halves, its fields are mangled in Kotlin/JS.
  else -> jsLongValue(ctx, jsVal)
}

/** True when [jsVal] is a boxed kotlin.Long, as the guest's value ops report it. */
private fun jsIsLong(ctx: CPointer<JSContext>, jsVal: CValue<JSValue>): Boolean {
  val low = callValueOp(ctx, "longLow", jsVal) ?: return false
  val isInt = JsValueGetNormTag(low) == JS_TAG_INT
  JS_FreeValue(ctx, low)
  return isInt
}

/** The value of the boxed kotlin.Long in [jsVal]; fails loudly when it is not one. */
private fun jsLongValue(ctx: CPointer<JSContext>, jsVal: CValue<JSValue>): Long {
  val low = callValueOp(ctx, "longLow", jsVal)
  if (low != null && JsValueGetNormTag(low) == JS_TAG_INT) {
    val high = callValueOp(ctx, "longHigh", jsVal)
    val highInt = if (high != null && JsValueGetNormTag(high) == JS_TAG_INT) JsValueGetInt(high) else 0
    if (high != null) JS_FreeValue(ctx, high)
    val result = (highInt.toLong() shl 32) or (JsValueGetInt(low).toLong() and 0xFFFFFFFFL)
    JS_FreeValue(ctx, low)
    return result
  }
  if (low != null) JS_FreeValue(ctx, low)
  error("not a kotlin.Long: the guest value ops reported no low/high halves for this value")
}

/** Ordinal of the enum instance in [jsVal], or -1 when it isn't an enum. */
public fun jsEnumOrdinal(ctx: CPointer<JSContext>, jsVal: CValue<JSValue>): Int {
  val ordinal = callValueOp(ctx, "enumOrdinal", jsVal)
  if (ordinal != null) {
    val result = if (JsValueGetNormTag(ordinal) == JS_TAG_INT) JsValueGetInt(ordinal) else -1
    JS_FreeValue(ctx, ordinal)
    if (result >= 0) return result
  }
  return -1
}

/**
 * The guest's value accessors, installed by `app.cash.zipline.publishValueOps()` from the
 * bridge plugin's module-load hook and fetched once per runtime. Kotlin/JS mangles the member names
 * of the stdlib collections (production builds drop the original names entirely) and there is no
 * single collection prototype to mark, so the guest answers the type question with the compiler's
 * own `is` check and drives the iteration. Every call here is O(1); nothing is copied guest-side.
 */
private fun valueOps(ctx: CPointer<JSContext>): CValue<JSValue>? {
  val quickJs = quickJsFor(ctx)
  quickJs.bridgeValueOps?.let { return it }
  val global = JS_GetGlobalObject(ctx)
  val ops = JS_GetPropertyStr(ctx, global, "__zipline_bridgeValueOps")
  JS_FreeValue(ctx, global)
  if (JS_IsUndefined(ops) != 0) {
    JS_FreeValue(ctx, ops)
    return null
  }
  quickJs.bridgeValueOps = ops
  return ops
}

/** Calls `ops.<name>(argument)`; returns the result (caller frees), or null when unavailable. */
private fun callValueOp(
  ctx: CPointer<JSContext>,
  name: String,
  argument: CValue<JSValue>,
): CValue<JSValue>? {
  val ops = valueOps(ctx) ?: return null
  val fn = JS_GetPropertyStr(ctx, ops, name)
  if (JS_IsFunction(ctx, fn) == 0) {
    JS_FreeValue(ctx, fn)
    return null
  }
  val result = memScoped {
    val args = allocArrayOf(argument)
    JS_Call(ctx, fn, JsUndefined(), 1, args)
  }
  JS_FreeValue(ctx, fn)
  if (JS_IsException(result) != 0) {
    JS_FreeValue(ctx, result)
    return null
  }
  return result
}

/**
 * How the guest classified an opaque JS value, from the value-ops `kind` call. The codes are the
 * guest's own (`BridgeValueOps` on the JS side). A JS array is never one of these: it is decoded
 * before [collectionKind] is consulted.
 */
enum class CollectionKind(val code: Int) {
  NONE(0),
  MAP(1),
  SET(2),
  LIST(3),
  ;

  companion object {
    /** The kind [code] stands for, or [NONE] when the guest sent a code this build doesn't know. */
    fun of(code: Int): CollectionKind = when (code) {
      MAP.code -> MAP
      SET.code -> SET
      LIST.code -> LIST
      else -> NONE
    }
  }
}

/** The guest's classification of [jsVal], or [CollectionKind.NONE] when it is not a collection. */
private fun collectionKind(ctx: CPointer<JSContext>, jsVal: CValue<JSValue>): CollectionKind {
  if (JsValueGetNormTag(jsVal) != JS_TAG_OBJECT) return CollectionKind.NONE
  val kind = callValueOp(ctx, "kind", jsVal) ?: return CollectionKind.NONE
  val code = if (JsValueGetNormTag(kind) == JS_TAG_INT) JsValueGetInt(kind) else CollectionKind.NONE.code
  JS_FreeValue(ctx, kind)
  return CollectionKind.of(code)
}

/** Builds a LinkedHashSet ([CollectionKind.SET]) or ArrayList ([CollectionKind.LIST]) from the guest's iterator. */
fun jsCollectionToKotlin(
  ctx: CPointer<JSContext>,
  jsVal: CValue<JSValue>,
  kind: CollectionKind,
  converter: (CValue<JSValue>) -> Any?,
): Any {
  val result: MutableCollection<Any?> = when (kind) {
    CollectionKind.SET -> LinkedHashSet()
    else -> ArrayList()
  }
  val iterator = callValueOp(ctx, "iterator", jsVal) ?: return result
  while (true) {
    val hasNext = callValueOp(ctx, "hasNext", iterator) ?: break
    val more = JsValueGetNormTag(hasNext) == JS_TAG_BOOL && JsValueGetBool(hasNext) != 0
    JS_FreeValue(ctx, hasNext)
    if (!more) break
    val element = callValueOp(ctx, "next", iterator) ?: break
    result.add(converter(element))
    JS_FreeValue(ctx, element)
  }
  JS_FreeValue(ctx, iterator)
  return result
}

/**
 * Throws when [jsVal] looks like a Kotlin class instance that no bridge converter was registered
 * for, naming the class; returns null for plain data shapes (JS objects, Kotlin collections),
 * which is how they decoded before. See the call site in [bridgeForAny].
 */
private fun throwUnbridgedJsObject(ctx: CPointer<JSContext>, jsVal: CValue<JSValue>): Any? {
  // A function value is not a data payload (it decoded to null before, and zipline has no
  // function bridge), so leave it alone.
  if (JS_IsFunction(ctx, jsVal) != 0) return null
  val ctor = JS_GetPropertyStr(ctx, jsVal, "constructor")
  if (JS_IsUndefined(ctor) != 0 || JS_IsNull(ctor) != 0 || JS_IsFunction(ctx, ctor) == 0) {
    JS_FreeValue(ctx, ctor)
    return null
  }
  val ctorName = JS_GetPropertyStr(ctx, ctor, "name")
  val ctorCString = JS_ToCString(ctx, ctorName)
  val className = ctorCString?.toKStringFromUtf8().orEmpty()
  if (ctorCString != null) JS_FreeCString(ctx, ctorCString)
  JS_FreeValue(ctx, ctorName)
  JS_FreeValue(ctx, ctor)
  if (className.isEmpty() || className in UNBRIDGED_DECODE_EXEMPT_CLASSES) return null
  throw IllegalStateException(
    "host bridge: no converter registered for JS class '$className'; " +
      "annotate the class with @WithJS2HostBridge to send it to the host",
  )
}

/**
 * JS built-ins (plain objects, arrays, dates, …) and Kotlin's collection/long wrappers decode
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
 * Dispatch a single JS value to its Kotlin equivalent using bridge_dispatch for objects.
 * Used by generated bridge code for elements of unknown type (Any? fields, List elements).
 */
@OptIn(ExperimentalForeignApi::class)
fun bridgeForAny(ctx: CPointer<JSContext>, jsVal: CValue<JSValue>): Any? = when {
  JS_IsArray(ctx, jsVal) != 0 -> {
    // Any-typed property values that are Kotlin Lists arrive as JS arrays (guest adapter
    // pins the raw JS value); decode elements recursively through their converters.
    val lengthProp = JS_GetPropertyStr(ctx, jsVal, "length")
    val length = JsValueGetInt(lengthProp)
    JS_FreeValue(ctx, lengthProp)
    (0 until length).map { index ->
      val element = JS_GetPropertyUint32(ctx, jsVal, index.toUInt())
      try {
        bridgeForAny(ctx, element)
      } finally {
        JS_FreeValue(ctx, element)
      }
    }
  }
  JS_IsNumber(jsVal) != 0 -> JsNumberToDouble(jsVal)
  JS_IsBool(jsVal) != 0 -> (JsValueGetBool(jsVal) != 0)
  JS_IsString(jsVal) != 0 -> {
    val cstr = JS_ToCString(ctx, jsVal)
    if (cstr != null) {
      val str = cstr.toKStringFromUtf8()
      JS_FreeCString(ctx, cstr)
      str
    } else ""
  }
  JS_IsUndefined(jsVal) != 0 || JS_IsNull(jsVal) != 0 -> null
  else -> {
    // Kotlin/JS collection (map/set/list): the guest identifies it and drives the iteration.
    when (val kind = collectionKind(ctx, jsVal)) {
      CollectionKind.MAP -> return jsMapToKotlin(
        ctx, jsVal, { element -> bridgeForAny(ctx, element) },
        { element -> bridgeForAny(ctx, element) },
      )
      CollectionKind.SET, CollectionKind.LIST ->
        return jsCollectionToKotlin(ctx, jsVal, kind) { element -> bridgeForAny(ctx, element) }
      CollectionKind.NONE -> Unit
    }
    val dispatch = JS_GetPropertyStr(ctx, jsVal, "bridge_dispatch")
    if (JS_IsUndefined(dispatch) == 0) {
      val fn = JsValueGetFloat64(dispatch).toRawBits().toCPointer<UByteVar>()!!.asStableRef<(CPointer<JSContext>, CValue<JSValue>) -> Any>()
      val r = fn.get()(ctx, jsVal)
      JS_FreeValue(ctx, dispatch)
      r
    } else {
      JS_FreeValue(ctx, dispatch)
      // A boxed kotlin.Long: the guest identifies it (Kotlin/JS mangles the fields of the box).
      if (jsIsLong(ctx, jsVal)) return jsLongValue(ctx, jsVal)
      // 3) No converter: a class instance that cannot be decoded. Returning null here used to
      // surface as a confusing cast/NPE far from the cause (e.g. a List<TopBarIcon> arriving as
      // [null] because TopBarIcon was not annotated), so name the class instead.
      throwUnbridgedJsObject(ctx, jsVal)
    }
  }
}

/**
 * Converts a Kotlin/JS Map instance into a Kotlin Map by walking its Kotlin iterator protocol
 * (entries() → iterator() → hasNext()/next()) and converting each key/value pair with the
 * supplied converters. Used by generated bridge code for Map fields.
 */
@OptIn(ExperimentalForeignApi::class)
public fun <K, V> jsMapToKotlin(
  ctx: CPointer<JSContext>,
  jsVal: CValue<JSValue>,
  keyConverter: (CValue<JSValue>) -> K,
  valueConverter: (CValue<JSValue>) -> V,
): Map<K, V> {
  val result = mutableMapOf<K, V>()
  val iterator = callValueOp(ctx, "iterator", jsVal) ?: return result
  while (true) {
    val hasNext = callValueOp(ctx, "hasNext", iterator) ?: break
    val more = JsValueGetNormTag(hasNext) == JS_TAG_BOOL && JsValueGetBool(hasNext) != 0
    JS_FreeValue(ctx, hasNext)
    if (!more) break
    val entry = callValueOp(ctx, "next", iterator) ?: break
    val rawKey = callValueOp(ctx, "key", entry)
    val rawValue = callValueOp(ctx, "value", entry)
    if (rawKey != null && rawValue != null) {
      result[keyConverter(rawKey)] = valueConverter(rawValue)
      JS_FreeValue(ctx, rawValue)
      JS_FreeValue(ctx, rawKey)
    }
    JS_FreeValue(ctx, entry)
  }
  JS_FreeValue(ctx, iterator)
  return result
}

