/*
 * JS host backend stub for CrossVersionTest.
 *
 * The JS target only provides the guest; the host engine does not exist on JS, so the host-side
 * tests cannot run here. This actual exists only to satisfy the expect/actual contract for the
 * shared commonTest sources.
 */
package app.cash.zipline.bridge.test

actual class SkewHost actual constructor(modules: List<Pair<String, String>>) {
  actual fun loadGuest(): Unit =
    throw UnsupportedOperationException("bridge host is not supported on the JS target")

  actual fun evaluateOne(script: String): Any? =
    throw UnsupportedOperationException("bridge host is not supported on the JS target")

  actual fun close(): Unit =
    throw UnsupportedOperationException("bridge host is not supported on the JS target")
}
