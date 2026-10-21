package app.cash.zipline

import kotlinx.serialization.json.JsonElement

/**
 * Callback interface for the Direct Bridge changes channel.
 *
 * Implementations create Redwood [Change] objects from raw primitive values
 * and flush them in batches to the host UI.
 *
 * This is the Kotlin/Native (iOS) equivalent of the JNI calls from Context.cpp
 * to DirectBridge on Android.
 */
@EngineApi
public interface DirectChangeSink {
  fun createCreate(id: Int, tag: Int)
  fun createPropertyChange(id: Int, widgetTag: Int, propertyTag: Int, value: JsonElement)
  fun createModifierChange(id: Int, elements: List<Pair<Int, JsonElement>>)
  fun createAdd(id: Int, childrenTag: Int, childId: Int, index: Int)
  fun createRemove(id: Int, childrenTag: Int, index: Int, detach: Boolean)
  fun createMove(id: Int, childrenTag: Int, fromIndex: Int, toIndex: Int, count: Int)
  fun createBridgeChange(id: Int, wrapped: Any?)

  /**
   * Mark a previously-created Remove change as a detach.
   *
   * The [index] is the positional index of the Remove in the currently
   * accumulated batch, from most recent to oldest.
   */
  fun setRemoveDetach(index: Int)

  /** Flush the currently accumulated batch to the batch accumulator. */
  fun sendBatch()

  /** Flush all remaining accumulated changes to the UI sink. */
  fun sendChanges()
}
