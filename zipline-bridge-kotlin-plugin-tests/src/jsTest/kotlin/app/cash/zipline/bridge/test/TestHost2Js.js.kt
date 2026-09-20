/*
 * JS host backend stub for Host2JsBridgeEndToEndTest.
 *
 * The JS target only provides the guest; the host engine does not exist on JS, so the host-side
 * tests cannot run here. These actuals exist only to satisfy the expect/actual contract for the
 * shared commonTest sources.
 */
package app.cash.zipline.bridge.test

actual class TestHost2Js actual constructor() {
  actual fun loadGuest(): Unit =
    throw UnsupportedOperationException("bridge host is not supported on the JS target")

  actual fun roundTrip(value: Any): Any? =
    throw UnsupportedOperationException("bridge host is not supported on the JS target")

  actual fun toJson(value: Any): String =
    throw UnsupportedOperationException("bridge host is not supported on the JS target")

  actual fun hasGlobalFunction(name: String): Boolean =
    throw UnsupportedOperationException("bridge host is not supported on the JS target")

  actual fun callGuestFunction(name: String, args: List<Any?>): Any? =
    throw UnsupportedOperationException("bridge host is not supported on the JS target")

  actual fun evaluateForBridge(script: String): Any? =
    throw UnsupportedOperationException("bridge host is not supported on the JS target")

  actual fun close(): Unit =
    throw UnsupportedOperationException("bridge host is not supported on the JS target")
}
