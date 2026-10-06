package app.cash.zipline.bridge.test

import app.cash.zipline.bridge.support.WithHost2JSBridge
import app.cash.zipline.bridge.support.WithJS2HostBridge

/**
 * The pre-rename shape of the shared class: the same FQN as the copy in :zipline-bridge-skew-new,
 * declared with the field names the class carried before the rename. This module's test loads the
 * other tree's guest bundle, and the other tree's test loads this one, so both directions of a
 * mixed deployment are covered by literally compiled trees.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class WirePayload(
  val oldId: Int,
  var label: String,
  /** Nullable on purpose: its name is renamed in the other tree, so a JS `null` and an absent
   *  property must stay distinguishable on both sides of the alias. */
  val oldNote: String?,
)
