package app.cash.zipline.service.skew

actual fun skewCaptureMode(): Boolean = System.getProperty("skew.capture") == "true"

actual fun isNativeHost(): Boolean = false
