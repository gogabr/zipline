/*
 * JVM/JNI host backend for CrossVersionTest.
 *
 * Loading order matters: JsEngine.create() loads libhermesvm, then System.load loads this module's
 * libskewnew.dylib, whose constructors register its generated converters via addBridgeEntry and
 * whose generated Java_*_convertToJs members implement the host->JS direction. Undefined symbols
 * resolve against the already-loaded libhermesvm at dlopen time.
 *
 * The library is per module: each tree has its own reader for the shared FQN, and two readers in
 * one process would make the winner depend on load order.
 */
package app.cash.zipline.bridge.test

import app.cash.zipline.JsEngine
import app.cash.zipline.internal.initModuleLoader
import app.cash.zipline.internal.loadJsModule
import java.util.Base64

actual class SkewHost actual constructor(
  private val modules: List<Pair<String, String>>,
) {
  private val jsEngine = JsEngine.create()

  init {
    val soPath = System.getProperty("bridgeTestSoPath")
    check(soPath != null) { "bridgeTestSoPath system property is missing (linkBridgeSo must run first)" }
    System.load(soPath)
    initModuleLoader(jsEngine)
    // Each bundle owns its own payload reader: the old bundle readOldIdFromPayload (this host's
    // payload reaching old-shape guest code) and this bundle readNewIdFromPayload (the
    // same-version pairing). require() stays inside each thunk so a host that never loaded that
    // bundle fails inside the conversion (loudly) instead of on a missing global.
    jsEngine.evaluateForBridge(
      "globalThis.readOldIdFromPayload = function (v) { " +
        "return require('${GeneratedSkewOldGuest.mainModuleId}')" +
        ".app.cash.zipline.bridge.test.readOldIdFromPayload(v); }; " +
        "globalThis.readNewIdFromPayload = function (v) { " +
        "return require('${GeneratedSkewNewGuest.mainModuleId}')" +
        ".app.cash.zipline.bridge.test.readNewIdFromPayload(v); }; 0",
      "skew_test_globals.js",
    )
  }

  actual fun loadGuest() {
    for ((id, base64) in modules) {
      loadJsModule(jsEngine, id, decodeGuestModule(base64))
    }
  }

  actual fun evaluateOne(script: String): Any? = jsEngine.evaluateForBridge(script, "test.js")

  actual fun callGuestFunction(name: String, args: List<Any?>): Any? = jsEngine.callGuestFunction(name, args)

  actual fun close() {
    jsEngine.close()
  }
}

/**
 * Decodes an embedded guest module (a ZiplineFile container) into raw engine bytecode.
 * Host-only: the loader has no JS target, so this lives in the platform actual rather than in
 * commonTest.
 */
internal fun decodeGuestModule(encoded: String): ByteArray {
  val container = app.cash.zipline.loader.ZiplineFile.read(
    okio.Buffer().write(Base64.getDecoder().decode(encoded)),
  )
  return container.jsBytecode.toByteArray()
}
