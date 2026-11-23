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

/**
 * Whether the host's payload object carries [key] as an OWN property, for the `host→guest`
 * create-factory the bridge plugin generates. Two distinctions, both load-bearing:
 *
 *  * Presence, not truthiness — a host that sent `null` or `0` sent a value, and the parameter must
 *    take that value rather than the guest-side default.
 *  * Own, not reachable via `in` — the host writes its fields onto an object that carries the guest
 *    class's own prototype (reusing what the pre-factory path already built, rather than asking each
 *    host for a primitive to make a bare `{}`), so `key in target` would answer yes for anything the
 *    prototype carries and the factory would hand the constructor a method where a value belongs.
 *    Only the host's own data properties are a sent value.
 *
 * This is the guest's copy of the question the JVM host asks with a JNI field lookup and the
 * Kotlin/Native host asks with `HermesBridge_getValueTag(...) == TAG_UNDEFINED`; all three feed the
 * same flag word.
 *
 * Top-level and public so the plugin resolves it by [org.jetbrains.kotlin.name.CallableId] from this
 * module's compile classpath, exactly like [publishValueOps]: an unresolvable reference here must be a
 * compile error in a module that names this library, not a missing property at run time.
 */
public fun bridgeHas(target: Any?, key: String): Boolean = js("Object.prototype.hasOwnProperty.call(target, key)")

/**
 * Read [key] off the host's payload object, for the same factory.
 *
 * No type check: the host converted the value into the guest's JS shape when it built the object, so
 * the factory casts straight to the constructor-parameter type. A host that sent the wrong shape is a
 * skew failure the guest will hit the moment it uses the value — the same exposure `bridgeGet` has
 * nowhere else to sit, since the guest cannot re-check a value the host already serialised.
 */
public fun bridgeGet(target: Any?, key: String): Any? = js("target[key]")

/**
 * A wire number into a `Char`, for the same factory.
 *
 * A cast will not do it. Kotlin/JS has no `Byte` or `Short` at run time — those are numbers, and
 * `bridgeGet(...) as Byte` costs nothing — but `Char` is an inline class there, so casting a number to
 * it answers with something the constructor cannot use: measured, a host's `'z'` arrived as
 * `'\u0000'` and a `'X'` under `Char?` as `null`, silently, with no failure anywhere on the way.
 * So the code point is taken out of the number and the wrapper built, which is what `Int.toChar()`
 * does with the compiler's own knowledge of the representation — the same reason the factory builds
 * value-class wrappers rather than casting to them.
 *
 * `Byte` and `Short` deliberately have no counterpart here: the cast is correct for them, and a helper
 * would only obscure which of the three types needs building.
 */
public fun bridgeChar(value: Any?): Char = value!!.unsafeCast<Double>().toInt().toChar()

/**
 * The ordinal a host sent to a `create_<fqn>` enum factory, as an `Int`.
 *
 * The host crosses the ordinal as a JS number, which Kotlin/JS hands the factory as a `Double` (the
 * engine's number type) or as an `Int` (when a caller passed a Kotlin `Int` straight through). Both
 * are accepted; anything else is a broken host and fails here, loudly, rather than resolving a
 * nonsense entry. The out-of-range check is the factory's job — it owns the entry list — but the
 * tag check and its message live here in plain Kotlin so the generated factory does not hand-roll IR.
 */
public fun bridgeEnumOrdinalArg(raw: Any?, fqName: String): Int = when (raw) {
  is Int -> raw

  is Double -> raw.toInt()

  else -> throw IllegalStateException(
    "host2js: $fqName expects the enum ordinal as a number, got " +
      (raw?.let { it::class.simpleName } ?: "null"),
  )
}
