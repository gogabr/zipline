package app.cash.zipline.bridge.test

/**
 * The guest half of the new tree. Its bundle is loaded by the old tree's host, and its own host
 * loads the old tree's bundle.
 *
 * The payload's [WirePayload.newNote] is null: this bundle is where a Kotlin null has to stay a JS
 * null, and the same-version test probes it on the guest side. [oldNote][WirePayload] in the other
 * tree carries a value instead, so both hold a distinguishable shape.
 *
 * [readNewIdFromPayload] carries the new shape, so only the same-version pairing (this bundle on
 * this tree's host) may call it. The old tree's test never does: an old host writing host->JS
 * payloads for new guest code is the one direction left uncovered (the old host writes only the
 * old name, and only a rewrite of the guest property getter could fix that).
 */
@JsExport
fun provideWirePayload(): WirePayload = WirePayload(newId = 42, newLabel = "skew", newNote = null)

/**
 * The same payload with the nullable field set: the other tree's host reads it through the alias,
 * and a non-null value there is the only shape that proves the alias carried it (a null and an
 * absent property both decode to null on the JS-to-host side).
 */
@JsExport
fun provideWirePayloadWithNote(): WirePayload =
  WirePayload(newId = 42, newLabel = "skew", newNote = "note")

/** New-shape guest code reading a payload the new host built: reads the current field names. */
@JsExport
fun readNewIdFromPayload(p: WirePayload): String =
  "${p.newId}|${if (p.newNote == null) "null" else p.newNote}"
