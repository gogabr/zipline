package app.cash.zipline.bridge.test

/**
 * Shared plumbing for the version-skew matrices in both trees.
 *
 * "New guest" / "old guest" name the *bundle* loaded into this tree's host, not the tree the test
 * lives in: the new tree's host loading the old bundle is the new-host/old-guest direction, and the
 * old tree's host loading the new bundle is the reverse.
 */

/** Every `readBack*` the new bundle exports. */
internal val NEW_GUEST_READERS = listOf(
  "readBackControl",
  "readBackAddedField",
  "readBackRemovedField",
  "readBackRenamedField",
  "readBackRetypedField",
  "readBackNullability",
  "readBackHierarchy",
  "readBackDroppedBase",
  "readBackInterface",
  "readBackGrowthEnum",
  "readBackShiftedEnum",
  "readBackWrapped",
  "readBackSealed",
  "readBackOnlyInNew",
)

/** The old bundle swaps `readBackOnlyInNew` for `readBackOnlyInOld`. */
internal val OLD_GUEST_READERS = listOf(
  "readBackControl",
  "readBackAddedField",
  "readBackRemovedField",
  "readBackRenamedField",
  "readBackRetypedField",
  "readBackNullability",
  "readBackHierarchy",
  "readBackDroppedBase",
  "readBackInterface",
  "readBackGrowthEnum",
  "readBackShiftedEnum",
  "readBackWrapped",
  "readBackSealed",
  "readBackOnlyInOld",
)

/** `require('<id>').app.cash.zipline.bridge.test.<fn>()` */
internal fun provide(fn: String, moduleId: String): String =
  "require('$moduleId').app.cash.zipline.bridge.test.$fn()"

/** Convert [payload] host->JS, call the guest's `readBack*` global thunk, decode the result. */
internal fun SkewHost.read(fn: String, payload: Any?): Any? = callGuestFunction(fn, listOf(payload))

/**
 * Assert the observed cross-version behavior of one matrix row.
 *
 * The result is compared as a string so a silent value and a thrown exception are recorded the same
 * way: under `-Dskew.capture=true` the row prints what it observed instead of asserting, which is
 * how the silent-skew expectations (absent field, mistyped read, out-of-range ordinal) were pinned
 * from the bridge rather than guessed.
 *
 * [nativeExpected] overrides [expected] on the Kotlin/Native host, for rows where the JVM and
 * Kotlin/Native hosts *behave* differently (different value, or silent vs loud). Rows that differ
 * only in message dialect are folded by [normalizeBridgeMessage] instead.
 */
internal fun assertObserved(label: String, expected: String, nativeExpected: String? = null, actual: () -> Any?) {
  val observed = normalizeBridgeMessage(
    runCatching { actual().toString() }
      .getOrElse { "throws ${it::class.simpleName}: ${it.message ?: ""}" },
  )
  if (skewCaptureMode()) {
    println("SKEW $label -> $observed")
  } else {
    val want = if (nativeExpected != null && isNativeHost()) nativeExpected else expected
    kotlin.test.assertEquals(want, observed, label)
  }
}

/**
 * Fold the two host implementations' message dialects into one form so a row that fails the same way
 * on both asserts once:
 * - the JVM host's C-generated messages are lowercase `host2js:` and leave the class name bare;
 *   Kotlin/Native's are uppercase `HOST2JS:` and quote it.
 * - the Kotlin/Native `callGuestFunction` wraps a host->JS conversion failure in `JsException` with a
 *   `cannot convert argument N of class X to JS:` prefix; the JVM throws the same message as
 *   `IllegalStateException`. The wrapper is a harness-path difference, not a bridge-semantics one, so
 *   the fold asserts the message and the README records the differing exception type.
 *
 * Rows where the two hosts *behave* differently (different value, or silent vs loud) are not folded;
 * they pass [assertObserved]'s `nativeExpected` instead.
 */
internal fun normalizeBridgeMessage(message: String): String =
  message
    .replace("HOST2JS:", "host2js:")
    .replace(Regex("host2js: '([^']*)' is an enum"), "host2js: $1 is an enum")
    .replace(
      Regex("^throws JsException: callGuestFunction: cannot convert argument \\d+ of class \\S+ to JS: "),
      "throws IllegalStateException: ",
    )

internal inline fun withNewGuest(body: (SkewHost, String) -> Unit) {
  withSkewHost(GeneratedSkewNewGuest.modules, GeneratedSkewNewGuest.mainModuleId, NEW_GUEST_READERS, body)
}

internal inline fun withOldGuest(body: (SkewHost, String) -> Unit) {
  withSkewHost(GeneratedSkewOldGuest.modules, GeneratedSkewOldGuest.mainModuleId, OLD_GUEST_READERS, body)
}

/**
 * Load [modules] into a fresh host of this tree, expose each of [readers] as a global thunk against
 * the guest's main module, run [body], and always close the engine.
 */
internal inline fun withSkewHost(
  modules: List<Pair<String, String>>,
  mainModuleId: String,
  readers: List<String>,
  body: (SkewHost, String) -> Unit,
) {
  val host = SkewHost(modules)
  try {
    host.loadGuest()
    for (reader in readers) host.installReader(mainModuleId, reader)
    body(host, mainModuleId)
  } finally {
    host.close()
  }
}
