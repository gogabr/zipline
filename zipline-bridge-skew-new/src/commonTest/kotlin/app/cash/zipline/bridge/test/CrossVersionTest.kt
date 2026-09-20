/*
 * Tests for the new tree.
 *
 * The host below is this tree's: its generated reader was compiled from the renamed WirePayload and
 * knows the old names only as a fallback, and its generated writer defines the old names as
 * accessors beside the new ones. Each assertion pins one mechanism:
 *
 *  - oldGuestOnNewHost: the old bundle's object carries only the pre-rename names, so only the
 *    reader fallback can produce the new names.
 *  - oldGuestReadsNewHostPayload: the payload is written by this tree's host converter and read by
 *    old-shape guest code, so only the writer alias can make it resolve.
 *  - aliasSetterStaysInSync: the accessor's setter, which no cross-direction test touches.
 *  - newGuestOnNewHost / newGuestReadsNewHostPayload: the same-version pairing, which must keep
 *    working untouched (the alias machinery is inert when both sides already agree).
 *  - nullFieldStaysJsNull: Kotlin/JS maps a Kotlin null to a JS null, never to `undefined`, so a
 *    present-but-null field stays distinguishable from the absent one the fallback keys on.
 *
 * The nullable renamed field (newNote/oldNote) rides along in every pairing: absent-or-null must
 * decode to null, and a value must survive, on both the fallback and the alias path.
 *
 * The old tree's test (:zipline-bridge-skew-old) covers the opposite pairing, where only the
 * guest-installed prototype alias can help.
 */
package app.cash.zipline.bridge.test

import kotlin.test.Test
import kotlin.test.assertEquals

/** Host backend: loads guest bytecode and evaluates JS through the bridge dispatcher. */
expect class SkewHost(modules: List<Pair<String, String>>) {
  fun loadGuest()
  fun evaluateOne(script: String): Any?
  /** Convert [args] host->JS and call the guest global [name] with them; converts the result back. */
  fun callGuestFunction(name: String, args: List<Any?>): Any?
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
  fun oldGuestOnNewHost() = withHost(
    GeneratedSkewOldGuest.modules,
    GeneratedSkewOldGuest.mainModuleId,
  ) { host, moduleId ->
    // 42 and "note", never 0 and null: a value that never arrived cannot pass as a default.
    assertEquals(
      WirePayload(newId = 42, newLabel = "skew", newNote = "note"),
      host.evaluateOne("require('$moduleId').app.cash.zipline.bridge.test.provideWirePayload()"),
    )
  }

  @Test
  fun oldGuestReadsNewHostPayload() = withHost(
    GeneratedSkewOldGuest.modules,
    GeneratedSkewOldGuest.mainModuleId,
  ) { host, _ ->
    // A null field crosses as a JS null through the alias: the old-shape guest sees a Kotlin null.
    assertEquals(
      "42|null",
      host.callGuestFunction(
        "readOldIdFromPayload",
        listOf(WirePayload(newId = 42, newLabel = "skew", newNote = null)),
      ),
    )
  }

  @Test
  fun aliasSetterStaysInSync() = withHost(
    GeneratedSkewNewGuest.modules,
    GeneratedSkewNewGuest.mainModuleId,
  ) { host, moduleId ->
    // A joined string, so both host decoders are exercised the same way.
    assertEquals(
      "written/written",
      host.evaluateOne(
        "var o = require('$moduleId').app.cash.zipline.bridge.test.provideWirePayload(); " +
          "o.label = 'written'; o.newLabel + '/' + o.label",
      ),
    )
  }

  @Test
  fun newGuestOnNewHost() = withHost(
    GeneratedSkewNewGuest.modules,
    GeneratedSkewNewGuest.mainModuleId,
  ) { host, moduleId ->
    // Both sides agree on the current names, so no alias or fallback is involved.
    assertEquals(
      WirePayload(newId = 42, newLabel = "skew", newNote = null),
      host.evaluateOne("require('$moduleId').app.cash.zipline.bridge.test.provideWirePayload()"),
    )
  }

  @Test
  fun newGuestReadsNewHostPayload() = withHost(
    GeneratedSkewNewGuest.modules,
    GeneratedSkewNewGuest.mainModuleId,
  ) { host, _ ->
    // The host writer defines the current name as a data property; the guest reads it there, not
    // through the old-name alias.
    assertEquals(
      "42|null",
      host.callGuestFunction(
        "readNewIdFromPayload",
        listOf(WirePayload(newId = 42, newLabel = "skew", newNote = null)),
      ),
    )
  }

  @Test
  fun nullFieldStaysJsNull() = withHost(
    GeneratedSkewNewGuest.modules,
    GeneratedSkewNewGuest.mainModuleId,
  ) { host, moduleId ->
    // The guest's own object, as JS sees it: a Kotlin null is a JS null, and `undefined` (the
    // reader fallback's trigger) is a different value.
    assertEquals(
      true,
      host.evaluateOne(
        "var o = require('$moduleId').app.cash.zipline.bridge.test.provideWirePayload(); " +
          "o.newNote === null && o.newNote !== undefined",
      ),
    )
  }
}
