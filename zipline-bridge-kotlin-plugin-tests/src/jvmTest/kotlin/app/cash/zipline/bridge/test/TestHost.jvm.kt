/*
 * JVM/JNI host backend for BridgeEndToEndTest.
 *
 * Loading order matters: JsEngine.create() loads libhermesvm, then System.load loads
 * libbridgetests.dylib whose constructors register the generated C converters via
 * addBridgeEntry (undefined symbols resolve against the already-loaded libhermesvm).
 */
package app.cash.zipline.bridge.test

import app.cash.zipline.JsEngine
import app.cash.zipline.internal.initModuleLoader
import app.cash.zipline.internal.loadJsModule
import java.util.Base64


actual class TestHost actual constructor() {
  private val jsEngine = JsEngine.create()

  init {
    val soPath = System.getProperty("bridgeTestSoPath")
    check(soPath != null) { "bridgeTestSoPath system property is missing (linkBridgeSo must run first)" }
    System.load(soPath)
    initModuleLoader(jsEngine)
  }

  actual fun loadGuest() {
    for ((id, base64) in GeneratedGuest.modules) {
      loadJsModule(jsEngine, id, decodeGuestModule(base64))
    }
  }

  actual fun evaluateOne(script: String): Any? = jsEngine.evaluateForBridge(script, "test.js")

  actual fun close() {
    jsEngine.close()
  }
}
