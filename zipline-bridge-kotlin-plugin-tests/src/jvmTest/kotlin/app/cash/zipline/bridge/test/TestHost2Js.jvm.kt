/*
 * JVM/JNI host backend for Host2JsBridgeEndToEndTest.
 *
 * Loading order matters: QuickJs.create() loads libquickjs, then System.load loads
 * libbridgetests.dylib (undefined symbols resolve against the already-loaded libquickjs at
 * dlopen time). The external roundTripNative/toJsJsonNative methods are implemented by
 * host2js_test_jni.c, which drives bridgeAnyToJs (the virtual convertToJs dispatch) and
 * bridgeForAny against the QuickJs instance's JSContext.
 */
package app.cash.zipline.bridge.test

import app.cash.zipline.QuickJs
import app.cash.zipline.internal.initModuleLoader
import app.cash.zipline.internal.loadJsModule

/** The guest app module id, as assigned by ZiplineCompiler (./<entry file>.js). */
private const val GUEST_MODULE = "./zipline-root-zipline-bridge-kotlin-plugin-tests.js"

actual class TestHost2Js actual constructor() {
  private val quickJs = QuickJs.create()

  init {
    val soPath = System.getProperty("bridgeTestSoPath")
    check(soPath != null) { "bridgeTestSoPath system property is missing (linkBridgeSo must run first)" }
    System.load(soPath)
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

  actual fun roundTrip(value: Any): Any? = roundTripNative(quickJs, value)

  actual fun toJson(value: Any): String {
    // The execution context is created with JS_NewContextNoEval, so the JSON.stringify step
    // runs via evaluateForBridge (compiled on the eval-capable context, executed here).
    setJsGlobalNative(quickJs, value)
    val json = quickJs.evaluateForBridge(
      "JSON.stringify(globalThis.__host2js_test)",
      "host2js_test.js",
    ) as String
    quickJs.evaluateForBridge("delete globalThis.__host2js_test", "host2js_test_cleanup.js")
    return json
  }

  private external fun roundTripNative(quickJs: QuickJs, value: Any): Any?

  private external fun setJsGlobalNative(quickJs: QuickJs, value: Any)

  actual fun close() {
    quickJs.close()
  }
}
