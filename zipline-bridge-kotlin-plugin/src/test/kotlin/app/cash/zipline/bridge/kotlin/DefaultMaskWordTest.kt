/*
 * Copyright (C) 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline.bridge.kotlin

import kotlin.test.assertEquals
import org.junit.Test

/**
 * The bridge computes a defaulted-parameter flag word in three places (the generated C, the
 * guest's create-factory, the native host's decoder) and hands it to the JVM's synthetic masked
 * constructor, whose descriptor the Kotlin compiler generated. All must agree on the packing, so
 * the packing is asserted here against the rule the compiler follows: one 32-bit word per 32
 * parameters, the parameter's position within its word selecting the bit.
 */
class DefaultMaskWordTest {

  @Test
  fun `a signature has one mask word per 32 parameters`() {
    assertEquals(0, maskWordCount(0))
    assertEquals(1, maskWordCount(1))
    assertEquals(1, maskWordCount(32))
    assertEquals(2, maskWordCount(33))
    assertEquals(2, maskWordCount(64))
    assertEquals(3, maskWordCount(65))
  }

  @Test
  fun `a parameter's flag lands in the word that covers its position`() {
    // The 32nd parameter (index 31) is the last bit of the first word; the 33rd starts the second.
    assertEquals(0, maskWordIndex(31))
    assertEquals(1, maskWordIndex(32))
    assertEquals(1, maskWordIndex(33))
    assertEquals(2, maskWordIndex(64))
  }

  @Test
  fun `a flag bit is the parameter's position inside its word`() {
    assertEquals(1, maskBit(0))
    assertEquals(2, maskBit(1))
    assertEquals(1 shl 31, maskBit(31))
    // Word 1 restarts at bit 0, so a shift by the raw index (which would overflow) is never used.
    assertEquals(1, maskBit(32))
    assertEquals(4, maskBit(34))
  }
}
