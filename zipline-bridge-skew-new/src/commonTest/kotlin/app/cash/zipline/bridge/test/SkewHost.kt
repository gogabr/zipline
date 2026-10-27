package app.cash.zipline.bridge.test

/**
 * Host backend for the bridge version-skew matrix: loads a guest bundle and crosses values through
 * the direct bridge. The host itself is this compilation's — its generated converters were compiled
 * from this tree's fixture shapes — while [modules] is any tree's guest bytecode, which is what makes
 * the skew pairing possible.
 */
expect class SkewHost(modules: List<Pair<String, String>>) {
  fun loadGuest()

  /** Evaluate [script] in the engine and convert the result host-side. */
  fun evaluateOne(script: String): Any?

  /**
   * Install a global thunk named [name] that requires [moduleId] and calls its exported
   * `app.cash.zipline.bridge.test.[name]`, so [callGuestFunction] can reach a guest function from
   * a bundle the host loaded. The require stays inside the thunk so a host that never loaded the
   * bundle fails inside the conversion instead of on a missing global.
   */
  fun installReader(moduleId: String, name: String)
  fun callGuestFunction(name: String, args: List<Any?>): Any?
  fun close()
}

/**
 * When true, the matrix tests print observed values instead of asserting them. Used to pin the
 * first-run behavior of the silent-skew rows (absent fields, type mismatches) before encoding the
 * expectations. Set with `-Dskew.capture=true` on the JVM.
 */
expect fun skewCaptureMode(): Boolean

/**
 * True on the Kotlin/Native host backend. Some skew rows behave differently per host implementation
 * rather than merely wording the failure differently; those rows carry a native-specific expectation.
 */
expect fun isNativeHost(): Boolean
