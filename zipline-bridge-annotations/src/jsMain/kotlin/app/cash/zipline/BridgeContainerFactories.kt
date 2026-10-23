package app.cash.zipline

/**
 * Guest constructors for the containers the direct bridge transports as a special case. The
 * bridge plugin calls these from `__BridgeRuntimeFactories` when converting a host value; each
 * takes one array of parts so the host needs no multi-argument call API.
 */
public fun newPair(parts: Array<Any?>): Pair<Any?, Any?> = Pair(parts[0], parts[1])

public fun newTriple(parts: Array<Any?>): Triple<Any?, Any?, Any?> = Triple(parts[0], parts[1], parts[2])

data class SimpleMapEntry(override val key: Any?, override val value: Any?) : Map.Entry<Any?, Any?>

public fun newMapEntry(parts: Array<Any?>): Map.Entry<Any?, Any?> = SimpleMapEntry(parts[0], parts[1])
