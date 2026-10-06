/*
 * Cross-version test for the old tree.
 *
 * The host below is this tree's: its generated reader was compiled from WirePayload(val oldId: Int,
 * var label: String), so it reads exactly the pre-rename names and has no fallback. The guest
 * bundle it loads is the *new* tree's, whose class carries the renamed fields, so only the alias
 * the new guest installs on its class prototype can supply the names this host reads.
 *
 * The other direction lives in :zipline-bridge-skew-new.
 */
package app.cash.zipline.bridge.test

import kotlin.test.Test
import kotlin.test.assertEquals

/** Host backend: loads guest bytecode and evaluates JS through the bridge dispatcher. */
expect class SkewHost(modules: List<Pair<String, String>>) {
  fun loadGuest()
  fun evaluateOne(script: String): Any?
  fun close()
}

class CrossVersionTest {
  private fun withHost(
    modules: List<Pair<String, String>>,
    mainModuleId: String,
    body: (SkewHost, String) -> Unit,
  ) {
    val host = SkewHost(modules)
    try {
      host.loadGuest()
      body(host, mainModuleId)
    } finally {
      host.close()
    }
  }

  @Test
  fun newGuestBundleOnOldHost() = withHost(
    GeneratedSkewNewGuest.modules,
    GeneratedSkewNewGuest.mainModuleId,
  ) { host, moduleId ->
    // 42 and "note", never 0 and null: a value that never arrived cannot pass as a default, and a
    // null in this field would be indistinguishable from the alias not working at all.
    assertEquals(
      WirePayload(oldId = 42, label = "skew", oldNote = "note"),
      host.evaluateOne(
        "require('$moduleId').app.cash.zipline.bridge.test.provideWirePayloadWithNote()",
      ),
    )
  }
}
