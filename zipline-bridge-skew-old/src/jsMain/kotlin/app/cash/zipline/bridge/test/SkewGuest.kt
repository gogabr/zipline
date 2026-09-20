package app.cash.zipline.bridge.test

/**
 * The guest half of the old tree. Its bundle is loaded by the new tree's host, and its own host
 * loads the new tree's bundle.
 *
 * [oldNote] carries a real value here, so the alias and fallback paths are seen carrying data; the
 * host-built payloads in the new tree use `null`, which [readOldIdFromPayload] reports on.
 */
@JsExport
fun provideWirePayload(): WirePayload = WirePayload(oldId = 42, label = "skew", oldNote = "note")

/**
 * Old guest code reading a payload the host built: reads the pre-rename field names.
 *
 * The second element distinguishes "present and null" from "absent" - Kotlin/JS does not fold a
 * JS `undefined` into a Kotlin null, so `== null` fails and the value renders as `undefined`.
 */
@JsExport
fun readOldIdFromPayload(p: WirePayload): String =
  "${p.oldId}|${if (p.oldNote == null) "null" else p.oldNote}"
