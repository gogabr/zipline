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

import app.cash.zipline.internal.CoroutineEventLoop
import app.cash.zipline.internal.EventListenerAdapter
import app.cash.zipline.internal.GuestService
import app.cash.zipline.internal.HostService
import app.cash.zipline.internal.RealHostService
import app.cash.zipline.internal.ZIPLINE_GUEST_NAME
import app.cash.zipline.internal.ZIPLINE_HOST_NAME
import app.cash.zipline.internal.bridge.CallChannel
import app.cash.zipline.internal.bridge.Endpoint
import app.cash.zipline.internal.bridge.ZiplineServiceAdapter
import app.cash.zipline.internal.bridge.stopTrackingLeaks
import app.cash.zipline.internal.bridge.theOnlyCancellationException
import app.cash.zipline.internal.cdpAttachIfEnabled
import app.cash.zipline.internal.initModuleLoader
import app.cash.zipline.internal.loadJsModule
import kotlin.coroutines.resumeWithException
import kotlin.reflect.KClass
import kotlin.reflect.cast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.EmptySerializersModule
import kotlinx.serialization.modules.SerializersModule

actual class Zipline private constructor(
  @property:EngineApi
  val jsEngine: JsEngine,
  userSerializersModule: SerializersModule,
  dispatcher: CoroutineDispatcher,
  private val scope: CoroutineScope,
  val eventListener: EventListener,
) : AutoCloseable {
  private val endpoint = Endpoint(
    scope = scope,
    userSerializersModule = userSerializersModule,
    eventListener = EventListenerAdapter(eventListener, this),
    outboundChannel = object : CallChannel {
      /** Lazily fetch the channel to call into JS. */
      private val jsInboundBridge: CallChannel by lazy(mode = LazyThreadSafetyMode.NONE) {
        jsEngine.getInboundChannel()
      }

      override fun call(callJson: String): String {
        check(scope.isActive) { "Zipline closed" }
        return jsInboundBridge.call(callJson)
      }

      override fun disconnect(instanceName: String): Boolean {
        return jsInboundBridge.disconnect(instanceName)
      }
    },
    oppositeProvider = {
      guest
    },
  )

  private val guest: GuestService = endpoint.take(ZIPLINE_GUEST_NAME)

  actual val json: Json
    get() = endpoint.json

  internal actual val serviceNames: Set<String>
    get() = endpoint.serviceNames

  internal actual val clientNames: Set<String>
    get() = guest.serviceNames

  private var closed = false

  private val attachments = mutableMapOf<KClass<*>, Any>()

  init {
    // Eagerly publish the channel so the guest can call us.
    jsEngine.initOutboundChannel(endpoint.inboundChannel)
    jsEngine.initDirectChangesChannel()

    val eventLoop = CoroutineEventLoop(dispatcher, scope, guest)

    endpoint.bind<HostService>(
      name = ZIPLINE_HOST_NAME,
      instance = RealHostService(endpoint, this, eventListener, eventLoop),
    )
  }

  fun initDirectBridgeChannel(sink: DirectChangeSink) {
    jsEngine.directChangeSink = sink
    jsEngine.initDirectChangesChannel()
  }

  actual fun <T : ZiplineService> bind(name: String, instance: T) {
    error("unexpected call to Zipline.bind: is the Zipline plugin configured?")
  }

  @PublishedApi
  internal fun <T : ZiplineService> bind(
    name: String,
    service: T,
    adapter: ZiplineServiceAdapter<T>,
  ) {
    check(scope.isActive) { "closed" }
    endpoint.bind(name, service, adapter)
  }

  actual fun <T : ZiplineService> take(
    name: String,
    scope: ZiplineScope,
  ): T {
    error("unexpected call to Zipline.take: is the Zipline plugin configured?")
  }

  @PublishedApi
  internal fun <T : ZiplineService> take(
    name: String,
    scope: ZiplineScope = ZiplineScope(),
    adapter: ZiplineServiceAdapter<T>,
  ): T {
    check(this.scope.isActive) { "closed" }
    return endpoint.take(name, scope, adapter)
  }

  /**
   * Release resources held by this instance. It is an error to do any of the following after
   * calling close:
   *
   *  * Call [take] or [bind].
   *  * Accessing [jsEngine].
   *  * Accessing the objects returned from [take].
   */
  override fun close() {
    close(closeServices = true)
  }

  /**
   * Release resources held by this instance. It is an error to do any of the following after
   * calling close:
   *
   *  * Call [take] or [bind].
   *  * Accessing [jsEngine].
   *  * Accessing the objects returned from [take].
   *
   * @param closeServices whether to close the host services that were bound with [bind]. Pass false
   *   when these services are owned by the host and may be shared with other [Zipline] instances or
   *   outlive this one (as Treehouse does when it reuses host services across code sessions).
   *   Closing a shared service here would also cancel in-flight calls that belong to other live
   *   [Zipline] instances, surfacing spurious cancellations in unrelated code.
   */
  fun close(closeServices: Boolean) {
    if (closed) return
    closed = true

    var thrown: Throwable? = null

    scope.cancel(theOnlyCancellationException)

    // Drop references to all bound services. We clear the map to prevent possible retain cycles on
    // Kotlin/Native where some objects may be reference-counted. Only close the services when we own
    // their lifecycle; host-owned services may still be bound to other live Zipline instances.
    val inboundServicesToClose = endpoint.inboundServices.values.toTypedArray()
    endpoint.inboundServices.clear()
    if (closeServices) {
      for (inboundService in inboundServicesToClose) {
        try {
          inboundService.service.close()
        } catch (e: Throwable) {
          if (thrown != null) thrown = e
        }
      }
    }

    jsEngine.close()

    // Don't wait for a JS continuation to resume, it never will. Canceling `scope` doesn't do this
    // because each continuation is in its caller's scope.
    for (continuation in endpoint.incompleteContinuations) {
      continuation.resumeWithException(CancellationException("Zipline closed"))
    }
    endpoint.incompleteContinuations.clear()
    stopTrackingLeaks(endpoint)
    eventListener.ziplineClosed(this)

    if (thrown != null) {
      throw thrown
    }
  }

  fun loadJsModule(script: String, id: String) {
    loadJsModule(jsEngine, script, id)
  }

  fun loadJsModule(bytecode: ByteArray, id: String) {
    loadJsModule(jsEngine, id, bytecode)
  }

  actual fun <T : Any> getOrPutAttachment(key: KClass<T>, compute: () -> T): T {
    val value = attachments.getOrPut(key, compute)
    return key.cast(value)
  }

  companion object {
    /**
     * TCP port of the CDP (Chrome DevTools Protocol) debug server shared by all engines,
     * e.g. 9222. Set it before [create] to debug the guest JS with Chrome DevTools; null
     * (the default) disables debugging. On Kotlin/Native the ZIPLINE_CDP_PORT environment
     * variable is honored as a fallback.
     */
    var cdpDebugPort: Int? = null

    fun create(
      dispatcher: CoroutineDispatcher,
      serializersModule: SerializersModule = EmptySerializersModule(),
      eventListener: EventListener = EventListener.NONE,
    ): Zipline {
      val jsEngine = JsEngine.create()
      initModuleLoader(jsEngine)

      val scope = CoroutineScope(dispatcher)
      val result = Zipline(jsEngine, serializersModule, dispatcher, scope, eventListener)
      cdpAttachIfEnabled(jsEngine, scope)
      eventListener.ziplineCreated(result)
      return result
    }
  }
}
