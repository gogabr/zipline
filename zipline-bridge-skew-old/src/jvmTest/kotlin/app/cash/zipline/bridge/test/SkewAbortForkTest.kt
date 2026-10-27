package app.cash.zipline.bridge.test

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Rows where the old host decoding the new guest's object kills the JVM.
 *
 * The Hermes decoder throws a C++ `jsi::JSError` for a missing or mistyped String/number property.
 * That exception has no JNI landing pad, so it reaches `std::terminate` and the process aborts: no
 * Kotlin `catch` can see it, and asserting in-process would take the whole test class down with it.
 * Each scenario therefore runs in a forked JVM, and the pinned behavior is the abort itself - exit
 * code 134 (SIGABRT) with the Hermes message on stderr.
 *
 * This is the headline finding for T1: the failure is a crash, not a caught exception.
 */
class SkewAbortForkTest {

  @Test fun fieldRenamed_abortsProcess() {
    assertAborts("fieldRenamed", "Value is undefined, expected a String")
  }

  @Test fun fieldRetyped_abortsProcess() {
    assertAborts("fieldRetyped", "Value is a string, expected a number")
  }

  @Test fun nullIntoNonNullable_abortsProcess() {
    assertAborts("nullIntoNonNullable", "Value is null, expected a String")
  }

  @Test fun superclassRemovedOnGuest_abortsProcess() {
    assertAborts("superclassRemovedOnGuest", "Value is undefined, expected a number")
  }

  private fun assertAborts(scenario: String, expectedMessage: String) {
    val result = forkRunner(scenario)
    assertEquals(ABORT_EXIT_CODE, result.exitCode, "$scenario: expected SIGABRT. Output: ${result.output}")
    assertTrue(
      result.output.contains(expectedMessage),
      "$scenario: output missing '$expectedMessage': ${result.output}",
    )
  }

  private fun forkRunner(scenario: String): ForkResult {
    val java = File(System.getProperty("java.home"), "bin/java").absolutePath
    val classpath = System.getProperty("java.class.path")
    val soPath = checkNotNull(System.getProperty("bridgeTestSoPath")) {
      "bridgeTestSoPath must be set by the jvmTest task"
    }
    val process = ProcessBuilder(java, "-cp", classpath, "-DbridgeTestSoPath=$soPath", RUNNER_CLASS, scenario)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    if (!process.waitFor(120, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      error("forked runner for '$scenario' timed out")
    }
    return ForkResult(process.exitValue(), output)
  }

  private class ForkResult(val exitCode: Int, val output: String)

  private companion object {
    const val RUNNER_CLASS = "app.cash.zipline.bridge.test.SkewAbortRunner"

    /** 128 + 6: the shell's encoding of a SIGABRT death. */
    const val ABORT_EXIT_CODE = 134
  }
}
