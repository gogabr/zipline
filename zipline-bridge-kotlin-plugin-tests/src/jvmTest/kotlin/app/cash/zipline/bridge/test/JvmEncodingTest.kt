/*
 * Strings that carry a NUL, asserted on the host whose boundary can carry them.
 *
 * The shared suite in `commonTest` pins what both hosts agree on (supplementary characters,
 * multi-byte code points). A NUL is the other place where Java's modified UTF-8 and standard UTF-8
 * disagree, and here the two hosts still do not agree: the JVM side converts through UTF-16 at every
 * boundary, while the Kotlin/Native side goes through a NUL-terminated C string in both directions
 * (`HermesBridge_getValueString` computes the length and then `strdup`s the buffer, and a Kotlin
 * `String` argument is converted by cinterop with `toCString`). Fixing that needs a length-aware C
 * entry point, which is task 5.17, not a change to this file.
 */
package app.cash.zipline.bridge.test

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmEncodingTest {
  private lateinit var host: TestHost2Js

  @BeforeTest
  fun setUp() {
    host = TestHost2Js()
    host.loadGuest()
  }

  /** The host's own string, NUL and all, arriving whole. */
  @Test fun nulStringReachesTheGuest() {
    val payload = BridgedEncodingProbe(s = "a\u0000b", tail = "tail")
    assertEquals("3|98|tail", host.callGuestFunction("describeEncodingProbe", listOf(payload)).toString())
  }

  /** A string the guest built, arriving whole: this is the generated decoder's half. */
  @Test fun nulStringFromTheGuestReachesTheHost() {
    assertEquals("BridgedEncodingProbe(len=3, last=121, tail=guest-tail)", host.callGuestFunction("provideEncodingProbe", emptyList()).toString())
  }

  /** Both halves in one value, since a truncation on either leg is invisible from the other. */
  @Test fun nulStringSurvivesARoundTrip() {
    val payload = BridgedEncodingProbe(s = "a\u0000b", tail = "tail")
    assertEquals("BridgedEncodingProbe(len=3, last=98, tail=tail)", host.roundTrip(payload).toString())
  }
}
