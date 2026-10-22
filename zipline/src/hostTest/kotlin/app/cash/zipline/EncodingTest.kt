/*
 * Copyright (C) 2023 Square, Inc.
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
package app.cash.zipline

import app.cash.zipline.testing.EncodingService
import app.cash.zipline.testing.LongPayload
import app.cash.zipline.testing.loadTestingJs
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher

class EncodingTest {
  @OptIn(ExperimentalCoroutinesApi::class)
  private val dispatcher = UnconfinedTestDispatcher()
  private val zipline = Zipline.create(dispatcher)

  @BeforeTest fun setUp() = runBlocking(dispatcher) {
    zipline.loadTestingJs()
  }

  @AfterTest fun tearDown() = runBlocking(dispatcher) {
    zipline.close()
  }

  /**
   * The guest transport is string-based, so plain `Long` (no `@Contextual`, no wrapping serializer)
   * round-trips exactly, including values outside the +/-2^53-1 double-precision range.
   */
  @Test fun encodeLongs() = runBlocking(dispatcher) {
    zipline.jsEngine.evaluate("testing.app.cash.zipline.testing.prepareEncodingJsBridges()")

    val service = zipline.take<EncodingService>("encodingService")
    assertThat(service.echoLong(Long.MIN_VALUE)).isEqualTo(Long.MIN_VALUE)
    assertThat(service.echoLong(-9007199254740993L)).isEqualTo(-9007199254740993L)
    assertThat(service.echoLong(-9007199254740992L)).isEqualTo(-9007199254740992L)
    assertThat(service.echoLong(-9007199254740991L)).isEqualTo(-9007199254740991L)
    assertThat(service.echoLong(-9007199254740990L)).isEqualTo(-9007199254740990L)
    assertThat(service.echoLong(-1L)).isEqualTo(-1L)
    assertThat(service.echoLong(0L)).isEqualTo(0L)
    assertThat(service.echoLong(1L)).isEqualTo(1L)
    assertThat(service.echoLong(9007199254740990L)).isEqualTo(9007199254740990L)
    assertThat(service.echoLong(9007199254740991L)).isEqualTo(9007199254740991L)
    assertThat(service.echoLong(9007199254740992L)).isEqualTo(9007199254740992L)
    assertThat(service.echoLong(9007199254740993L)).isEqualTo(9007199254740993L)
    assertThat(service.echoLong(Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE)
  }

  @Test fun encodeLongList() = runBlocking(dispatcher) {
    zipline.jsEngine.evaluate("testing.app.cash.zipline.testing.prepareEncodingJsBridges()")

    val service = zipline.take<EncodingService>("encodingService")
    val values = listOf(
      Long.MIN_VALUE,
      -9007199254740993L,
      0L,
      9007199254740993L,
      Long.MAX_VALUE,
    )
    assertThat(service.echoLongList(values)).isEqualTo(values)
  }

  @Test fun encodeLongMap() = runBlocking(dispatcher) {
    zipline.jsEngine.evaluate("testing.app.cash.zipline.testing.prepareEncodingJsBridges()")

    val service = zipline.take<EncodingService>("encodingService")
    val values = mapOf(
      Long.MIN_VALUE to -9007199254740993L,
      9007199254740993L to 0L,
      Long.MAX_VALUE to 1L,
    )
    assertThat(service.echoLongMap(values)).isEqualTo(values)
  }

  @Test fun encodeLongPayload() = runBlocking(dispatcher) {
    zipline.jsEngine.evaluate("testing.app.cash.zipline.testing.prepareEncodingJsBridges()")

    val service = zipline.take<EncodingService>("encodingService")
    val payload = LongPayload(
      value = 9007199254740993L,
      values = listOf(Long.MIN_VALUE, -9007199254740993L, 0L, Long.MAX_VALUE),
    )
    assertThat(service.echoPayload(payload)).isEqualTo(payload)
  }
}
