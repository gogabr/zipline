/*
 * Kotlin/Native host backend for Host2JsBridgeEndToEndTest.
 *
 * roundTrip converts the host value with anyToJs (virtual dispatch through the generated
 * convertToJs overrides) and reads it back with bridgeForAny; toJson runs the converted value
 * through JSON.stringify as a debugging/triage aid.
 */
package app.cash.zipline.bridge.test

import app.cash.zipline.QuickJs
import app.cash.zipline.anyToJs
import app.cash.zipline.bridgeForAny
import app.cash.zipline.internal.initModuleLoader
import app.cash.zipline.internal.loadJsModule
import app.cash.zipline.quickjs.JS_FreeValue
import app.cash.zipline.quickjs.JS_GetGlobalObject
import app.cash.zipline.quickjs.JS_SetPropertyStr
import kotlinx.cinterop.ExperimentalForeignApi

/** The guest app module id, as assigned by ZiplineCompiler (./<entry file>.js). */
private const val GUEST_MODULE = "./zipline-root-zipline-bridge-kotlin-plugin-tests.js"

@OptIn(ExperimentalForeignApi::class)
actual class TestHost2Js actual constructor() {
  private val quickJs = QuickJs.create()

  init {
    initModuleLoader(quickJs)
  }

  actual fun loadGuest() {
    for ((id, base64) in GeneratedGuest.modules) {
      loadJsModule(quickJs, id, decodeGuestModule(base64))
    }
  }

  actual fun warmUp() {
    quickJs.evaluateForBridge(
      "require('$GUEST_MODULE').app.cash.zipline.bridge.test.warmUpHost2Js()",
      "warmup.js",
    )
  }

  actual fun hasGlobalFunction(name: String): Boolean = quickJs.hasGlobalFunction(name)

  actual fun callGuestFunction(name: String, args: List<Any?>): Any? = quickJs.callGuestFunction(name, args)

  actual fun evaluateForBridge(script: String): Any? = quickJs.evaluateForBridge(script, "sink.js")

  actual fun roundTrip(value: Any): Any? {
    val js = anyToJs(quickJs.jsContext, value)
    val back = bridgeForAny(quickJs.jsContext, js)
    JS_FreeValue(quickJs.jsContext, js)
    return back
  }

  actual fun toJson(value: Any): String {
    val js = anyToJs(quickJs.jsContext, value)
    val global = JS_GetGlobalObject(quickJs.jsContext)
    JS_SetPropertyStr(quickJs.jsContext, global, "__host2js_test", js)
    JS_FreeValue(quickJs.jsContext, global)
    // The execution context is created with JS_NewContextNoEval, so the JSON.stringify step
    // runs via evaluateForBridge (compiled on the eval-capable context, executed here).
    val result = quickJs.evaluateForBridge(
      "JSON.stringify(globalThis.__host2js_test)",
      "host2js_test.js",
    ) as String
    quickJs.evaluateForBridge("delete globalThis.__host2js_test", "host2js_test_cleanup.js")
    return result
  }

  actual fun close() {
    quickJs.close()
  }
}
