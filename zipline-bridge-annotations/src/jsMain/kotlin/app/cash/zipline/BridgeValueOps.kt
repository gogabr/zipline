package app.cash.zipline

import kotlin.js.JsName

/**
 * Value accessors for the host's JS->host decoders.
 *
 * A host-side decoder often has to convert a value whose static type it doesn't know (property
 * values cross as `Any?`). For collections, Longs and enums it cannot look for a well-known shape:
 *
 *  * Kotlin/JS mangles the member names of the stdlib (`get_entries_<hash>_k$`, `low_1`, `ordinal_1`,
 *    ...), and production builds drop the original names altogether — so probing names from the
 *    host works in development and fails silently in production.
 *  * There is no single collection prototype to mark: `mapOf(pair)` builds a HashMap, `emptyMap()`
 *    returns an EmptyMap singleton, `toMap()`/`optimizeReadOnlyMap` pick implementations by size,
 *    and the JS-interop views (`asJsMapView`, ...) are further classes.
 *
 * So the guest answers the type question with the compiler's own `is` check and reads the values
 * with the compiler's own calls; the host only drives the access. Every call is O(1) and nothing is
 * copied guest-side — the host materializes the JVM/Kotlin value it needs.
 *
 * This lives in `zipline-bridge-annotations` on purpose: the bridge plugin resolves
 * [publishValueOps] while compiling each bridged module, so the symbol has to be on that module's
 * own compile classpath. The annotations are the one artifact every bridged module declares; the
 * runtime module is not (it reaches them transitively, as an implementation dependency, and Kotlin/
 * JS does not put transitive implementation dependencies on a consumer's compile classpath). Put it
 * back in the runtime module and the plugin silently stops publishing the ops, which fails later,
 * on the first collection the host has to decode.
 */
internal object BridgeValueOps {
  const val NONE = 0
  const val MAP = 1
  const val SET = 2
  const val LIST = 3

  @JsName("kind")
  fun kind(value: Any?): Int = when (value) {
    is Map<*, *> -> MAP
    is Set<*> -> SET
    is List<*> -> LIST
    else -> NONE
  }

  /** An iterator over entries ([MAP]) or elements ([SET], [LIST]). */
  @JsName("iterator")
  fun iterator(value: Any?): Any? = when (value) {
    is Map<*, *> -> value.entries.iterator()
    is Set<*> -> value.iterator()
    is List<*> -> value.iterator()
    else -> null
  }

  @JsName("hasNext")
  fun hasNext(iterator: Any?): Boolean = (iterator as? Iterator<*>)?.hasNext() == true

  /** The next entry ([MAP]) or element ([SET], [LIST]). */
  @JsName("next")
  fun next(iterator: Any?): Any? = (iterator as? Iterator<*>)?.next()

  /** Key of an entry produced by [next]. */
  @JsName("key")
  fun key(entry: Any?): Any? = (entry as? Map.Entry<*, *>)?.key

  /** Value of an entry produced by [next], or the element itself for sets and lists. */
  @JsName("value")
  fun value(entry: Any?): Any? = (entry as? Map.Entry<*, *>)?.value

  /** Low 32 bits of a boxed `kotlin.Long`, or null for any other value. */
  @JsName("longLow")
  fun longLow(value: Any?): Int? = (value as? Long)?.toInt()

  /** High 32 bits of a boxed `kotlin.Long`, or null for any other value. */
  @JsName("longHigh")
  fun longHigh(value: Any?): Int? = ((value as? Long)?.shr(32))?.toInt()

  /**
   * Ordinal of a guest-built enum instance, or -1 for any other value. Objects the host built for a
   * host->JS conversion are handled host-side (they carry `ordinal_1`, a host-defined name).
   */
  @JsName("enumOrdinal")
  fun enumOrdinal(value: Any?): Int = (value as? Enum<*>)?.ordinal ?: -1
}

/**
 * Installs [BridgeValueOps] on `globalThis` for the host, under names that no compiler or minifier
 * touches. Called from the bridge plugin's module-load hook, because Kotlin/JS initializes
 * file-level declarations lazily and the host needs this before the application runs.
 */
public fun publishValueOps() {
  val ops = BridgeValueOps
  @Suppress("UNUSED_VARIABLE")
  val installed = js(
    """
    globalThis.__zipline_bridgeValueOps = {
      kind: function (value) { return ops.kind(value); },
      iterator: function (value) { return ops.iterator(value); },
      hasNext: function (iterator) { return ops.hasNext(iterator); },
      next: function (iterator) { return ops.next(iterator); },
      key: function (entry) { return ops.key(entry); },
      value: function (entry) { return ops.value(entry); },
      longLow: function (value) { return ops.longLow(value); },
      longHigh: function (value) { return ops.longHigh(value); },
      enumOrdinal: function (value) { return ops.enumOrdinal(value); },
    };
    """,
  )
}
