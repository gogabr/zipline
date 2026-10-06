package app.cash.zipline.bridge.test

import app.cash.zipline.bridge.support.HostName
import app.cash.zipline.bridge.support.WithHost2JSBridge
import app.cash.zipline.bridge.support.WithJS2HostBridge

/**
 * The post-rename shape of the shared class: the same FQN as the copy in :zipline-bridge-skew-old,
 * with the fields renamed and each carrying the name it had before. This module's test loads the
 * other tree's guest bundle, and the other tree's test loads this one, so both directions of a
 * mixed deployment are covered by literally compiled trees.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class WirePayload(
  @HostName("oldId") val newId: Int,
  @HostName("label") var newLabel: String,
  /** Nullable on purpose: a Kotlin null must cross as a JS null, and stay distinct from the
   *  absent property that makes the reader fall back to [HostName]'s old name. */
  @HostName("oldNote") val newNote: String?,
)
