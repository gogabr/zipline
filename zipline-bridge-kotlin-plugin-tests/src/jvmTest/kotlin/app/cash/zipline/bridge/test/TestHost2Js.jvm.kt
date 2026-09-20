/*
 * JVM/JNI host backend for Host2JsBridgeEndToEndTest.
 *
 * Loading order matters: JsEngine.create() loads libhermesvm, then System.load loads
 * libbridgetests.dylib whose constructors register the generated JS-to-host converters via
 * addBridgeEntry, and whose generated Java_*_convertToJs members implement the host-to-JS
 * direction (undefined symbols resolve against the already-loaded libhermesvm at dlopen time).
 *
 * The round trip itself goes through the production engine API: callGuestFunction converts each
 * argument host->JS (bridgeAnyToJs), calls the guest global, and decodes the result JS->host
 * (bridgeForAny). No test-only native library is involved.
 */
package app.cash.zipline.bridge.test

import app.cash.zipline.JsEngine
import app.cash.zipline.internal.initModuleLoader
import app.cash.zipline.internal.loadJsModule
import java.util.Base64

/** The guest app module id, as assigned by ZiplineCompiler (./<entry file>.js). */
private const val GUEST_MODULE = "./zipline-root-zipline-bridge-kotlin-plugin-tests.js"

actual class TestHost2Js actual constructor() {
  private val jsEngine = JsEngine.create()

  init {
    val soPath = System.getProperty("bridgeTestSoPath")
    check(soPath != null) { "bridgeTestSoPath system property is missing (linkBridgeSo must run first)" }
    System.load(soPath)
    initModuleLoader(jsEngine)
    // The guest owns the identity and JSON.stringify entry points. require() stays inside the
    // thunk so a host that never loaded the guest module still fails inside the conversion (the
    // loud missing-prototype path) rather than on a missing global.
    jsEngine.evaluateForBridge(
      "globalThis.echoHostValue = function (v) { " +
        "return require('$GUEST_MODULE').app.cash.zipline.bridge.test.echoHostValue(v); }; " +
        "globalThis.stringifyHostValue = function (v) { " +
        "return require('$GUEST_MODULE').app.cash.zipline.bridge.test.stringifyHostValue(v); }; 0",
      "host2js_test_globals.js",
    )
  }

  actual fun loadGuest() {
    for ((id, base64) in GeneratedGuest.modules) {
      loadJsModule(jsEngine, id, decodeGuestModule(base64))
    }
  }

  actual fun roundTrip(value: Any): Any? = jsEngine.callGuestFunction("echoHostValue", listOf(value))

  actual fun toJson(value: Any): String = jsEngine.callGuestFunction("stringifyHostValue", listOf(value)) as String

  actual fun hasGlobalFunction(name: String): Boolean = jsEngine.hasGlobalFunction(name)

  actual fun callGuestFunction(name: String, args: List<Any?>): Any? = jsEngine.callGuestFunction(name, args)

  actual fun evaluateForBridge(script: String): Any? = jsEngine.evaluateForBridge(script, "sink.js")

  actual fun close() {
    jsEngine.close()
  }
}
