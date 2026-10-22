package app.cash.zipline

/**
 * CDP tests in one process share a port: [app.cash.zipline.internal.cdp.CdpDebugSupport] keeps a
 * single server. Parallel test processes must not all bind 9229.
 */
internal fun cdpDebugTestPort(): Int = 20_000 + (processId() % 10_000).toInt()

internal expect fun processId(): Long
