/**
 * Standalone entry point that performs one skew read known to kill the JVM.
 *
 * Reading a missing or mistyped String/number property on the guest object aborts the process inside
 * Hermes (`jsi::JSError` escaping through JNI), so the row cannot be asserted in-process: the abort
 * takes the test JVM with it and Gradle reports the whole class as crashed. [SkewAbortForkTest] runs
 * one scenario per child process and asserts the exit status and abort message instead.
 */
package app.cash.zipline.bridge.test

object SkewAbortRunner {

  /** The scenarios this runner can execute, in the order [SkewAbortForkTest] asserts them. */
  val SCENARIOS = listOf("fieldRenamed", "fieldRetyped", "superclassInserted")

  @JvmStatic
  fun main(args: Array<String>) {
    val scenario = args.singleOrNull() ?: error("expected one scenario name, got ${args.toList()}")
    withOldGuest { host, id ->
      when (scenario) {
        "fieldRenamed" -> host.evaluateOne(provide("provideRenamedField", id))
        "fieldRetyped" -> host.evaluateOne(provide("provideRetypedField", id))
        "superclassInserted" -> host.evaluateOne(provide("provideHierarchy", id))
        else -> error("unknown scenario '$scenario'")
      }
    }
    // Reached only if the read turns out to be survivable, which is itself a finding.
    println("COMPLETED $scenario")
  }
}
