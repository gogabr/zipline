package app.cash.zipline.service.skew

import app.cash.zipline.Zipline
import app.cash.zipline.testing.systemFileSystem
import app.cash.zipline.testing.ziplineRoot
import kotlinx.coroutines.Dispatchers
import okio.buffer
import okio.use

// Host-side plumbing for the real-engine service version-skew contract.
//
// Rows assert the *observable outcome* as a string, never wire bytes: a value is stringified, a
// failure becomes `throws <ExceptionClass>: <message>`. A transport swap (JSON wire -> JNI /
// Kotlin-Native data transfer) that changes a verdict changes that string, so the pinned contract
// fails loudly instead of silently agreeing.

/** Module id the guest bundle is registered under. Matches the Kotlin/JS output file name. */
internal const val GUEST_MODULE_ID = "./zipline-root-zipline-service-skew.js"

/**
 * The guest's full JS graph, in topologically-sorted load order.
 *
 * Every module comes from *this* module's own `compileSync` output rather than the shared testing
 * graph. Kotlin/JS links cross-module references through an index-keyed export slot table
 * (`kotlin_kotlin.$_$.k8`), and that table is a function of the whole compilation. Mixing a guest
 * built here with a stdlib built elsewhere resolves those slots to the wrong member — the failure
 * looks like `Cannot create property '...' on boolean 'true'`. One graph, one build, consistent slots.
 */
private val GUEST_GRAPH = listOf(
  "./kotlin-kotlin-stdlib.js",
  "./kotlinx-atomicfu.js",
  "./kotlinx-serialization-kotlinx-serialization-core.js",
  "./kotlinx-serialization-kotlinx-serialization-json.js",
  "./kotlinx-coroutines-core.js",
  "./kotlin_org_jetbrains_kotlin_kotlin_dom_api_compat.js",
  "./zipline-root-zipline.js",
  GUEST_MODULE_ID,
)

/** True when `-Dskew.capture=true` was passed: print observed values instead of asserting. */
expect fun skewCaptureMode(): Boolean

/** True on Kotlin/Native hosts, so a row can pin a platform-specific outcome. */
expect fun isNativeHost(): Boolean

private const val GUEST_JS_PATH =
  "zipline-service-skew/build/compileSync/js/main/developmentExecutable/kotlin"

/** Read one module of the guest graph from this checkout's JS build output. */
private fun readGuestJs(fileName: String): String {
  val file = ziplineRoot / GUEST_JS_PATH / fileName.removePrefix("./")
  return systemFileSystem.source(file).buffer().use { it.readUtf8() }
}

/**
 * Bring up a real engine with the testing graph plus the skew guest bundle, expose the guest as
 * `globalThis.skew`, run [body], and always close the engine.
 */
suspend fun withSkewGuest(body: suspend (Zipline) -> Unit) {
  val zipline = Zipline.create(Dispatchers.Default)
  try {
    for (id in GUEST_GRAPH) {
      zipline.loadJsModule(readGuestJs(id), id)
    }
    zipline.jsEngine.evaluate("globalThis['skew'] = require('$GUEST_MODULE_ID');")
    zipline.jsEngine.evaluate("skew.app.cash.zipline.service.skew.prepareSkewBridges()")
    body(zipline)
  } finally {
    zipline.close()
  }
}

/**
 * Assert one contract row by observed outcome.
 *
 * [expected] is the JVM outcome; [nativeExpected] overrides it where the Kotlin/Native host
 * genuinely differs. Under capture mode the row prints what it observed instead of asserting, which
 * is how rows whose outcome is not obvious from the contract get pinned.
 */
suspend fun assertObserved(
  label: String,
  expected: String,
  nativeExpected: String? = null,
  actual: suspend () -> Any?,
) {
  val observed = try {
    actual().toString()
  } catch (e: Throwable) {
    "throws ${e::class.simpleName}: ${e.message ?: ""}"
  }
  if (skewCaptureMode()) {
    println("SKEW $label -> $observed")
  } else {
    val want = if (nativeExpected != null && isNativeHost()) nativeExpected else expected
    kotlin.test.assertEquals(want, observed, label)
  }
}

/**
 * Assert one row that must fail: the throwable's simple class name matches [expectedThrowable] and
 * its message contains [expectedMessageSubstring]. Under capture mode the row prints the observed
 * throwable and message instead of asserting.
 */
suspend fun assertThrowsContaining(
  label: String,
  expectedThrowable: String,
  expectedMessageSubstring: String,
  block: suspend () -> Any?,
) {
  val observed = runCatching { block() }
  val error = observed.exceptionOrNull()
  if (skewCaptureMode()) {
    val shown = error?.let { "throws ${it::class.simpleName}: ${it.message}" } ?: "returned ${observed.getOrNull()}"
    println("SKEW $label -> $shown")
    return
  }
  val actual = error ?: throw AssertionError("$label: expected $expectedThrowable, returned ${observed.getOrNull()}")
  kotlin.test.assertEquals(expectedThrowable, actual::class.simpleName, "$label (${actual::class.simpleName}: ${actual.message})")
  kotlin.test.assertTrue(
    expectedMessageSubstring in (actual.message ?: ""),
    "$label: '$expectedMessageSubstring' missing from '${actual.message}'",
  )
}

/** Call a guest-exported function by name through the `globalThis.skew` handle. */
internal fun Zipline.callGuest(fn: String, vararg args: Any?): Any? {
  val encoded = args.joinToString(",") { jsLiteral(it) }
  return jsEngine.evaluate("skew.app.cash.zipline.service.skew.$fn($encoded)")
}

private fun jsLiteral(value: Any?): String = when (value) {
  null -> "null"
  is String -> "'${value.replace("'", "\\'")}'"
  is Number -> value.toString()
  else -> throw IllegalArgumentException("unsupported guest arg: $value")
}
