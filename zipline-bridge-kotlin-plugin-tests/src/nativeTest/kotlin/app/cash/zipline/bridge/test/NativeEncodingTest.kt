/*
 * A string that carries a NUL, on the host that cannot yet carry it.
 *
 * Pinned as broken on purpose. Both directions cut the value at the NUL and neither says so: the
 * Kotlin/Native host hands the guest a NUL-terminated C string (cinterop converts a `String`
 * argument with `toCString`) and reads one back through `HermesBridge_getValueString`, which
 * computes the UTF-8 length and then `strdup`s the buffer, keeping only the bytes up to the first
 * NUL. The JVM host carries the same payloads whole, so this file records a divergence with a name
 * rather than a shared contract: task 5.17 is to give that path a length, and every row here
 * changes when it lands.
 */
package app.cash.zipline.bridge.test

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeEncodingTest {
  private lateinit var host: TestHost2Js

  @BeforeTest
  fun setUp() {
    host = TestHost2Js()
    host.loadGuest()
  }

  /** "a" plus a NUL plus "b" arrives as "a": the rest of the value is gone, and nothing failed. */
  @Test fun nulStringIsTruncatedOnTheWayIn() {
    val payload = BridgedEncodingProbe(s = "a\u0000b", tail = "tail")
    assertEquals("1|97|tail", host.callGuestFunction("describeEncodingProbe", listOf(payload)).toString())
  }

  /** The guest's own "x" NUL "y" arrives as "x", for the same reason on the decode side. */
  @Test fun nulStringFromTheGuestIsTruncatedOnTheWayOut() {
    assertEquals("BridgedEncodingProbe(len=1, last=120, tail=guest-tail)", host.callGuestFunction("provideEncodingProbe", emptyList()).toString())
  }
}
