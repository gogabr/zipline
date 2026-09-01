/*
 * Copyright (C) 2026 Square, Inc.
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

import app.cash.zipline.quickjs.JSContext
import app.cash.zipline.quickjs.JSValue
import app.cash.zipline.quickjs.JS_Call
import app.cash.zipline.quickjs.JS_FreeValue
import app.cash.zipline.quickjs.JS_GetRuntime
import app.cash.zipline.quickjs.JS_GetRuntimeOpaque
import app.cash.zipline.quickjs.JS_NewArray
import app.cash.zipline.quickjs.JS_NewBool
import app.cash.zipline.quickjs.JS_NewFloat64
import app.cash.zipline.quickjs.JS_NewInt32
import app.cash.zipline.quickjs.JS_NewString
import app.cash.zipline.quickjs.JS_SetPropertyStr
import app.cash.zipline.quickjs.JS_SetPropertyUint32
import app.cash.zipline.quickjs.JsNull
import app.cash.zipline.quickjs.JsUndefined
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.utf8

/**
 * Implemented by classes annotated with [app.cash.zipline.bridge.support.WithHost2JSBridge] on
 * Kotlin/Native. The bridge compiler plugin injects this supertype and an `override` of
 * [convertToJs] whose body builds a counterpart JS object (prototype-based, recursively
 * converting fields) in [ctx].
 *
 * [anyToJs] dispatches to this interface for object values; a value that implements neither this
 * interface nor any built-in conversion is an assertion error, never a silent null.
 */
public interface Host2JsConvertible {
  /** Build a JS counterpart of this object in [ctx]; the caller owns the returned value. */
  public fun convertToJs(ctx: CPointer<JSContext>): CValue<JSValue>
}

internal fun quickJsFor(ctx: CPointer<JSContext>): QuickJs {
  return JS_GetRuntimeOpaque(JS_GetRuntime(ctx))!!.asStableRef<QuickJs>().get()
}

/**
 * Convert a Kotlin [Long] to a real Kotlin/JS `kotlin.Long` instance (correct prototype and
 * methods) by calling the guest's registered `bridgeNewLong` factory with the low/high halves.
 * The caller owns the returned value.
 */
public fun kotlinLongToJs(ctx: CPointer<JSContext>, value: Long): CValue<JSValue> {
  val factory = quickJsFor(ctx).bridgeNewLong
    ?: error("HOST2JS: no registered newLong runtime factory; the guest module did not call __bridgeRegisterRuntime")
  val low = JS_NewInt32(ctx, value.toInt())
  val high = JS_NewInt32(ctx, (value shr 32).toInt())
  val result = memScoped {
    val args = allocArrayOf(low, high)
    val r = JS_Call(ctx, factory, JsUndefined(), 2, args)
    JS_FreeValue(ctx, low)
    JS_FreeValue(ctx, high)
    r
  }
  return result
}

/**
 * Set [name] on [obj] to [value]. Consumes [value] (QuickJS takes ownership), so the caller
 * must not free it afterwards.
 */
public fun setJsProperty(
  ctx: CPointer<JSContext>,
  obj: CValue<JSValue>,
  name: String,
  value: CValue<JSValue>,
) {
  JS_SetPropertyStr(ctx, obj, name, value)
}

/**
 * Convert an arbitrary host value to its JS counterpart in [ctx]. The caller owns the returned
 * value.
 *
 * - Primitives, [String], and arrays convert to the corresponding JS primitives/arrays.
 * - [Long] values become real `kotlin.Long` instances via the guest's `bridgeNewLong` factory.
 * - [List] values become real Kotlin/JS `ArrayList` instances via the guest's
 *   `bridgeNewArrayList` factory; [Map] values become real `LinkedHashMap` instances via the
 *   guest's `bridgeNewLinkedHashMap` factory (keys and values converted recursively). These
 *   match the shapes the JS→host readers expect, so converted collections round-trip.
 * - Anything implementing [Host2JsConvertible] (i.e. `@WithHost2JSBridge` classes) dispatches
 *   to its [Host2JsConvertible.convertToJs].
 *
 * Anything else is an assertion error, never a silent null; the only null produced here is for
 * an actual [null] data value.
 */
public fun anyToJs(ctx: CPointer<JSContext>, value: Any?): CValue<JSValue> {
  return when (value) {
    null -> JsNull()

    is Boolean -> JS_NewBool(ctx, if (value) 1 else 0)

    is Int -> JS_NewInt32(ctx, value)
    is Byte -> JS_NewInt32(ctx, value.toInt())
    is Short -> JS_NewInt32(ctx, value.toInt())
    is Char -> JS_NewInt32(ctx, value.code)

    is Float -> JS_NewFloat64(ctx, value.toDouble())
    is Double -> JS_NewFloat64(ctx, value)

    is Long -> kotlinLongToJs(ctx, value)

    is String -> JS_NewString(ctx, value.utf8)

    is List<*> -> {
      val jsArray = JS_NewArray(ctx)
      value.forEachIndexed { index, element ->
        JS_SetPropertyUint32(ctx, jsArray, index.convert(), anyToJs(ctx, element))
      }
      val factory = quickJsFor(ctx).bridgeNewArrayList
        ?: error("HOST2JS: no registered newArrayList runtime factory; the guest module did not call __bridgeRegisterRuntime")
      memScoped {
        val args = allocArrayOf(jsArray)
        val r = JS_Call(ctx, factory, JsUndefined(), 1, args)
        JS_FreeValue(ctx, jsArray)
        r
      }
    }

    is Map<*, *> -> {
      // Two parallel JS arrays: the guest's newLinkedHashMap factory zips them into pairs and
      // builds a real LinkedHashMap (no internal-layout reliance, matches JS-created maps).
      val keys = JS_NewArray(ctx)
      val values = JS_NewArray(ctx)
      value.entries.forEachIndexed { index, entry ->
        JS_SetPropertyUint32(ctx, keys, index.convert(), anyToJs(ctx, entry.key))
        JS_SetPropertyUint32(ctx, values, index.convert(), anyToJs(ctx, entry.value))
      }
      val factory = quickJsFor(ctx).bridgeNewLinkedHashMap
        ?: error("HOST2JS: no registered newLinkedHashMap runtime factory; the guest module did not call __bridgeRegisterRuntime")
      memScoped {
        val args = allocArrayOf(keys, values)
        val r = JS_Call(ctx, factory, JsUndefined(), 2, args)
        JS_FreeValue(ctx, keys)
        JS_FreeValue(ctx, values)
        r
      }
    }

    is IntArray -> {
      val arr = JS_NewArray(ctx)
      value.forEachIndexed { index, element ->
        JS_SetPropertyUint32(ctx, arr, index.convert(), JS_NewInt32(ctx, element))
      }
      arr
    }
    is BooleanArray -> {
      val arr = JS_NewArray(ctx)
      value.forEachIndexed { index, element ->
        JS_SetPropertyUint32(ctx, arr, index.convert(), JS_NewBool(ctx, if (element) 1 else 0))
      }
      arr
    }
    is CharArray -> {
      val arr = JS_NewArray(ctx)
      value.forEachIndexed { index, element ->
        JS_SetPropertyUint32(ctx, arr, index.convert(), JS_NewInt32(ctx, element.code))
      }
      arr
    }
    is ShortArray -> {
      val arr = JS_NewArray(ctx)
      value.forEachIndexed { index, element ->
        JS_SetPropertyUint32(ctx, arr, index.convert(), JS_NewInt32(ctx, element.toInt()))
      }
      arr
    }
    is ByteArray -> {
      val arr = JS_NewArray(ctx)
      value.forEachIndexed { index, element ->
        JS_SetPropertyUint32(ctx, arr, index.convert(), JS_NewInt32(ctx, element.toInt()))
      }
      arr
    }
    is LongArray -> {
      val arr = JS_NewArray(ctx)
      value.forEachIndexed { index, element ->
        JS_SetPropertyUint32(ctx, arr, index.convert(), kotlinLongToJs(ctx, element))
      }
      arr
    }
    is FloatArray -> {
      val arr = JS_NewArray(ctx)
      value.forEachIndexed { index, element ->
        JS_SetPropertyUint32(ctx, arr, index.convert(), JS_NewFloat64(ctx, element.toDouble()))
      }
      arr
    }
    is DoubleArray -> {
      val arr = JS_NewArray(ctx)
      value.forEachIndexed { index, element ->
        JS_SetPropertyUint32(ctx, arr, index.convert(), JS_NewFloat64(ctx, element))
      }
      arr
    }

    is Array<*> -> {
      val arr = JS_NewArray(ctx)
      value.forEachIndexed { index, element ->
        JS_SetPropertyUint32(ctx, arr, index.convert(), anyToJs(ctx, element))
      }
      arr
    }

    else -> (value as? Host2JsConvertible)?.convertToJs(ctx)
      ?: error("HOST2JS: no bridge for '${value::class.qualifiedName}'")
  }
}
