package app.cash.zipline

internal actual fun processId(): Long = ProcessHandle.current().pid()
