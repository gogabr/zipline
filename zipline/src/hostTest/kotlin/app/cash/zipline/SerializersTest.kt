/*
 * Copyright (C) 2021 Square, Inc.
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

import app.cash.zipline.testing.AdaptersRequest
import app.cash.zipline.testing.AdaptersRequestSerializersModule
import app.cash.zipline.testing.AdaptersResponse
import app.cash.zipline.testing.AdaptersResponseSerializersModule
import app.cash.zipline.testing.AdaptersSerializersModule
import app.cash.zipline.testing.AdaptersService
import app.cash.zipline.testing.loadTestingJs
import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher

class SerializersTest {
  @OptIn(ExperimentalCoroutinesApi::class)
  private val dispatcher = UnconfinedTestDispatcher()
  private val zipline = Zipline.create(dispatcher, AdaptersSerializersModule)
  private val ziplineRequestOnly = Zipline.create(dispatcher, AdaptersRequestSerializersModule)
  private val ziplineResponseOnly = Zipline.create(dispatcher, AdaptersResponseSerializersModule)

  @BeforeTest fun setUp() = runBlocking(dispatcher) {
    zipline.loadTestingJs()
    ziplineRequestOnly.loadTestingJs()
    ziplineResponseOnly.loadTestingJs()
  }

  @AfterTest fun tearDown() = runBlocking(dispatcher) {
    zipline.close()
    ziplineRequestOnly.close()
    ziplineResponseOnly.close()
  }

  @Test fun missingGetReturnValueSerializerThrowsAtCallTime() = runBlocking(dispatcher) {
    val service = ziplineRequestOnly.take<AdaptersService>("adaptersService")
    ziplineRequestOnly.jsEngine.evaluate("testing.app.cash.zipline.testing.prepareAdaptersJsBridges()")

    val e = assertFailsWith<ZiplineApiMismatchException> {
      service.echo(AdaptersRequest("Andrew"))
    }
    assertThat(e.message!!)
      .contains("json bridge: no serializer registered for app.cash.zipline.testing.AdaptersResponse")
  }

  @Test fun missingGetParameterSerializerThrowsAtCallTime() = runBlocking(dispatcher) {
    val service = ziplineResponseOnly.take<AdaptersService>("adaptersService")
    ziplineResponseOnly.jsEngine.evaluate("testing.app.cash.zipline.testing.prepareAdaptersJsBridges()")

    val e = assertFailsWith<ZiplineApiMismatchException> {
      service.echo(AdaptersRequest("Andrew"))
    }
    assertThat(e.message!!)
      .contains("json bridge: no serializer registered for app.cash.zipline.testing.AdaptersRequest")
  }

  @Test fun presentGetSerializersSucceeds() = runBlocking(dispatcher) {
    val service = zipline.take<AdaptersService>("adaptersService")
    zipline.jsEngine.evaluate("testing.app.cash.zipline.testing.prepareAdaptersJsBridges()")

    assertThat(service.echo(AdaptersRequest("Andrew")))
      .isEqualTo(AdaptersResponse("thank you for using your serializers, Andrew"))
  }

  @Test fun missingSerializerIsIsolatedPerFunction() = runBlocking(dispatcher) {
    // take() succeeds even though AdaptersResponse has no serializer registered: the serializer is
    // resolved lazily, per call. A function that needs no custom serializer still works.
    val service = ziplineRequestOnly.take<AdaptersService>("adaptersService")
    ziplineRequestOnly.jsEngine.evaluate("testing.app.cash.zipline.testing.prepareAdaptersJsBridges()")

    service.ping()

    val e = assertFailsWith<ZiplineApiMismatchException> {
      service.echo(AdaptersRequest("Andrew"))
    }
    assertThat(e.message!!)
      .contains("json bridge: no serializer registered for app.cash.zipline.testing.AdaptersResponse")
  }

  @Test fun missingSetReturnValueSerializerThrowsAtCallTime() = runBlocking(dispatcher) {
    ziplineRequestOnly.bind<AdaptersService>(
      "adaptersService",
      HostAdaptersService(),
    )
    val e = assertFailsWith<Exception> {
      ziplineRequestOnly.jsEngine.evaluate("testing.app.cash.zipline.testing.callAdaptersService()")
    }
    assertThat(e.message!!)
      .contains("json bridge: no serializer registered for app.cash.zipline.testing.AdaptersResponse")
  }

  @Test fun missingSetParameterSerializerThrowsAtCallTime() = runBlocking(dispatcher) {
    ziplineResponseOnly.bind<AdaptersService>(
      "adaptersService",
      HostAdaptersService(),
    )
    val e = assertFailsWith<Exception> {
      ziplineResponseOnly.jsEngine.evaluate("testing.app.cash.zipline.testing.callAdaptersService()")
    }
    assertThat(e.message!!)
      .contains("json bridge: no serializer registered for app.cash.zipline.testing.AdaptersRequest")
  }

  @Test fun presentSetSerializersSucceeds() = runBlocking(dispatcher) {
    zipline.bind<AdaptersService>(
      "adaptersService",
      HostAdaptersService(),
    )
    assertThat(zipline.jsEngine.evaluate("testing.app.cash.zipline.testing.callAdaptersService()"))
      .isEqualTo("JavaScript received nice adapters, Jesse")
  }

  private class HostAdaptersService : AdaptersService {
    override fun ping() {
      // No-op.
    }

    override fun echo(request: AdaptersRequest): AdaptersResponse {
      return AdaptersResponse("nice adapters, ${request.message}")
    }
  }
}
