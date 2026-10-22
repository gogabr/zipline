@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package app.cash.zipline

import platform.posix.getpid

internal actual fun processId(): Long = getpid().toLong()
