/*
 * Kotlin/Native host backend for CrossVersionTest.
 *
 * evaluateForBridge evaluates the script and runs the raw JS value through bridgeForAny, which
 * dispatches to this module's generated X_toKotlin converters (registered via @EagerInitialization).
 */
package app.cash.zipline.bridge.test

import app.cash.zipline.JsEngine
import app.cash.zipline.internal.initModuleLoader
import app.cash.zipline.internal.loadJsModule
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
actual class SkewHost actual constructor(
  private val modules: List<Pair<String, String>>,
) {
  private val jsEngine = JsEngine.create()

  init {
    initModuleLoader(jsEngine)
  }

  actual fun loadGuest() {
    for ((id, base64) in modules) {
      loadJsModule(jsEngine, id, decodeGuestModule(base64))
    }
  }

  actual fun evaluateOne(script: String): Any? = jsEngine.evaluateForBridge(script, "test.js")

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
    okio.Buffer().write(Base64.decode(encoded)),
  )
  return container.jsBytecode.toByteArray()
}
