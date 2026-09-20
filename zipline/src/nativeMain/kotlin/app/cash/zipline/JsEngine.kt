@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)

package app.cash.zipline

import app.cash.zipline.hermes.*
import app.cash.zipline.internal.bridge.CallChannel
import app.cash.zipline.internal.bridge.INBOUND_CHANNEL_NAME
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.cinterop.*
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

// Channels and sinks are keyed by engine context pointer so that multiple
// concurrent runtimes (e.g. an old and a new Zipline during a screen
// transition) each route their JS calls to their own endpoint. The maps are
// copy-on-write because registration (zipline thread) and callback
// invocation (any thread running JS) may race.
private val outboundChannels = AtomicReference<Map<Long, CallChannel>>(emptyMap())
private val rdmaChangeSinks = AtomicReference<Map<Long, RdmaChangeSink>>(emptyMap())
private val cdpListeners = AtomicReference<Map<Long, CdpListener>>(emptyMap())

val bridgeRetainRefs = mutableListOf<() -> Unit>()

fun registerBridgeInitHook(hook: () -> Unit) {
  bridgeRetainRefs.add(hook)
}

fun registerBridge(fqn: String, fn: CPointer<CFunction<(COpaquePointer?, Int) -> COpaquePointer?>>) {
  @Suppress("UNCHECKED_CAST")
  HermesBridge_addBridgeEntry(fqn, fn as COpaquePointer)
}

private fun <V> AtomicReference<Map<Long, V>>.putValue(key: Long, value: V) {
  while (true) {
    val current = load()
    if (compareAndSet(current, current + (key to value))) return
  }
}

private fun <V> AtomicReference<Map<Long, V>>.removeValue(key: Long) {
  while (true) {
    val current = load()
    if (key !in current) return
    if (compareAndSet(current, current - key)) return
  }
}

@Suppress("UNCHECKED_CAST")
private fun outboundChannelCallCallback(context: COpaquePointer?, callJson: CPointer<ByteVar>?): CPointer<ByteVar>? {
    val channel = outboundChannels.load()[context?.rawValue?.toLong() ?: return null] ?: return null
    val callJsonStr = callJson?.toKString() ?: return null
    val result: String = channel.call(callJsonStr)
    val bytes: ByteArray = result.encodeToByteArray()
    val byteCount: Int = bytes.size
    // The C++ caller releases the returned buffer with free(), so it must be
    // allocated with the system allocator, not Kotlin/Native's nativeHeap.
    val persistentPtr: CPointer<ByteVar> = platform.posix
        .malloc((byteCount + 1).convert())
        ?.reinterpret()
        ?: return null
    return bytes.usePinned { pinned ->
        val srcPtr: CPointer<ByteVar> = pinned.addressOf(0).reinterpret()
        platform.posix.memcpy(persistentPtr, srcPtr, byteCount.toULong())
        // Set the last byte to 0
        platform.posix.memset(persistentPtr + byteCount, 0, 1uL)
        persistentPtr
    }
}

private fun outboundChannelDisconnectCallback(context: COpaquePointer?, instanceName: CPointer<ByteVar>?): Int {
    val channel = outboundChannels.load()[context?.rawValue?.toLong() ?: return 0] ?: return 0
    val instanceNameStr = instanceName?.toKString() ?: return 0
    return if (channel.disconnect(instanceNameStr)) 1 else 0
}

// Called from C++ when JS invokes finishChanges() on the RDMA channel.
// Delegates to rdmaChangeSink.sendChanges() which flushes all accumulated
// changes to the UI. The C++ side manages the pendingChanges list and
// removeCounter internally; only the final sendChanges() call reaches Kotlin.
private fun rdmaChangeSinkSendChanges(context: COpaquePointer?) {
    val sink = rdmaChangeSinks.load()[context?.rawValue?.toLong() ?: return] ?: return
    sink.sendChanges()
}

// Recursive JSON serializer for Hermes JS values — equivalent to gogabr's jsValueToJsonElement
// in QuickJs.kt. Recursively converts JS objects/arrays/primitives into kotlinx JsonElement.
private fun hermesValueToJsonElement(context: COpaquePointer?, handle: Int): JsonElement {
    if (context == null) return JsonNull
    val tag = HermesBridge_getValueTag(context, handle)
    return when (tag) {
        1 -> JsonPrimitive(HermesBridge_getValueDouble(context, handle).toInt())
        2 -> {
            val d = HermesBridge_getValueDouble(context, handle)
            val l = d.toLong()
            if (l.toDouble() == d) JsonPrimitive(l) else JsonPrimitive(d)
        }
        3 -> {
            val str = HermesBridge_getValueString(context, handle)
            val kstr = str?.toKStringFromUtf8()?.also { platform.posix.free(str) } ?: ""
            JsonPrimitive(kstr)
        }
        4 -> JsonPrimitive(HermesBridge_getValueBool(context, handle) != 0)
        6 -> {
            val len = HermesBridge_getArrayLength(context, handle)
            val items = mutableListOf<JsonElement>()
            var i = 0
            while (i < len) {
                val elemRef = HermesBridge_createArrayElementHandle(context, handle, i)
                items.add(hermesValueToJsonElement(context, elemRef))
                HermesBridge_freeHandle(context, elemRef)
                i++
            }
            JsonArray(items)
        }
        5 -> {
            val keysHandle = HermesBridge_getObjectPropertyNames(context, handle)
            if (keysHandle == 0) return JsonNull
            val keysLen = HermesBridge_getArrayLength(context, keysHandle)
            val props = mutableMapOf<String, JsonElement>()
            var i = 0
            while (i < keysLen) {
                val keyHandle = HermesBridge_createArrayElementHandle(context, keysHandle, i)
                val keyPtr = HermesBridge_getValueString(context, keyHandle)
                val key = keyPtr?.toKStringFromUtf8()?.also { platform.posix.free(keyPtr) } ?: ""
                HermesBridge_freeHandle(context, keyHandle)
                val valHandle = HermesBridge_createHandle(context, handle, key)
                props[key] = hermesValueToJsonElement(context, valHandle)
                HermesBridge_freeHandle(context, valHandle)
                i++
            }
            HermesBridge_freeHandle(context, keysHandle)
            JsonObject(props)
        }
        else -> JsonNull
    }
}

// Per-change-type callbacks called from C++ JS host-function lambdas.
// Each writes directly into the RdmaChangeSink accumulator.

private fun onRdmaCreate(context: COpaquePointer?, id: Int, tag: Int) {
    try {
        rdmaChangeSinks.load()[context?.rawValue?.toLong() ?: return]?.createCreate(id, tag)
    } catch (_: Throwable) {
    }
}

private fun onRdmaPropertyChange(context: COpaquePointer?, id: Int, widgetTag: Int, propertyTag: Int, valueHandle: Int) {
    try {
        val sink = rdmaChangeSinks.load()[context?.rawValue?.toLong() ?: return] ?: return
        val value = hermesValueToJsonElement(context, valueHandle)
        sink.createPropertyChange(id, widgetTag, propertyTag, value)
    } catch (_: Throwable) {
    }
}

private fun onRdmaModifierChange(context: COpaquePointer?, id: Int, elementsHandle: Int) {
    try {
        val sink = rdmaChangeSinks.load()[context?.rawValue?.toLong() ?: return] ?: return
        val elements = jsArrayToModifierElements(context, elementsHandle)
        sink.createModifierChange(id, elements)
    } catch (_: Throwable) {
    }
}

// Equivalent to gogabr's jsArrayToModifierElements in QuickJs.kt.
private fun jsArrayToModifierElements(context: COpaquePointer?, elementsHandle: Int): List<Pair<Int, JsonElement>> {
    val len = HermesBridge_getArrayLength(context, elementsHandle)
    val elements = mutableListOf<Pair<Int, JsonElement>>()
    var i = 0
    while (i < len) {
        val elemRef = HermesBridge_createArrayElementHandle(context, elementsHandle, i)
        val tagHandle = HermesBridge_createHandle(context, elemRef, "0")
        val valHandle = HermesBridge_createHandle(context, elemRef, "1")
        val mtag = HermesBridge_getValueDouble(context, tagHandle).toInt()
        val value = hermesValueToJsonElement(context, valHandle)
        elements.add(mtag to value)
        HermesBridge_freeHandle(context, valHandle)
        HermesBridge_freeHandle(context, tagHandle)
        HermesBridge_freeHandle(context, elemRef)
        i++
    }
    return elements
}

private fun onRdmaAdd(context: COpaquePointer?, id: Int, childrenTag: Int, childId: Int, index: Int) {
    try {
        rdmaChangeSinks.load()[context?.rawValue?.toLong() ?: return]?.createAdd(id, childrenTag, childId, index)
    } catch (_: Throwable) {
    }
}

private fun onRdmaRemove(context: COpaquePointer?, id: Int, childrenTag: Int, index: Int, detach: Int) {
    try {
        rdmaChangeSinks.load()[context?.rawValue?.toLong() ?: return]?.createRemove(id, childrenTag, index, detach != 0)
    } catch (_: Throwable) {
    }
}

private fun onRdmaMove(context: COpaquePointer?, id: Int, childrenTag: Int, fromIndex: Int, toIndex: Int, count: Int) {
    try {
        rdmaChangeSinks.load()[context?.rawValue?.toLong() ?: return]?.createMove(id, childrenTag, fromIndex, toIndex, count)
    } catch (_: Throwable) {
    }
}

private fun onRdmaBridgeChange(context: COpaquePointer?, id: Int, jsValueHandle: Int) {
    try {
        val sink = rdmaChangeSinks.load()[context?.rawValue?.toLong() ?: return] ?: return
        val dispPtr = HermesBridge_getBridgeDispatch(context, jsValueHandle)
        if (dispPtr == 0L) return
        val dispatchFn = dispPtr.toCPointer<CFunction<(COpaquePointer?, Int) -> COpaquePointer?>>()!!
        val ktObj = dispatchFn(context, jsValueHandle)!!.asStableRef<Any>().get()
        sink.createBridgeChange(id, ktObj)
    } catch (_: Throwable) {
    }
}

private fun onRdmaSetRemoveDetach(context: COpaquePointer?, index: Int) {
    rdmaChangeSinks.load()[context?.rawValue?.toLong() ?: return]?.setRemoveDetach(index)
}

private fun onRdmaSendChanges(context: COpaquePointer?) {
    try {
        rdmaChangeSinks.load()[context?.rawValue?.toLong() ?: return]?.sendChanges()
    } catch (_: Throwable) {
    }
}

// CDP session callbacks (see CdpSession.cpp). May be invoked from arbitrary
// threads; the listener lookup is a lock-free read of the copy-on-write map.
private fun cdpMessageCallback(context: COpaquePointer?, json: CPointer<ByteVar>?) {
    val listener = cdpListeners.load()[context?.rawValue?.toLong() ?: return] ?: return
    listener.onMessage(json?.toKString() ?: return)
}

private fun cdpTasksEnqueuedCallback(context: COpaquePointer?) {
    val listener = cdpListeners.load()[context?.rawValue?.toLong() ?: return] ?: return
    listener.onTasksEnqueued()
}

// Called when the native CDP session is torn down (HermesRuntime_destroy):
// drop the registry entry so it can't retain a dead listener.
private fun cdpDisposedCallback(context: COpaquePointer?) {
    cdpListeners.removeValue(context?.rawValue?.toLong() ?: return)
}

/*
* This class is NOT thread safe. If multiple threads access an instance concurrently it must be
* synchronized externally.
*/
@OptIn(ExperimentalForeignApi::class)
@EngineApi
actual class JsEngine private constructor(
  private val contextPointer: COpaquePointer?,
) : AutoCloseable {
  private var closed = false

  actual companion object {
    actual fun create(): JsEngine {
      // CDP debugging compiles eagerly so breakpoints bind in
      // runtime-compiled source (lazy functions have no code blocks yet).
      val debugging = app.cash.zipline.internal.cdp.cdpDebugPort() != null
      val runtime = if (debugging) {
        HermesRuntime_createForDebugging()
      } else {
        HermesRuntime_create()
      } ?: throw UnsupportedOperationException("Failed to create Hermes runtime")
      val jsiRuntime = HermesRuntime_getJsiRuntime(runtime)
        ?: throw UnsupportedOperationException("Failed to get jsi runtime from Hermes context")

      // Register JS intrinsics (IntSet/ScatterSet/ScatterMap/etc.) that back the
      // kotlinx.collections fast paths in Kotlin/JS. These are called from
      // generated Kotlin/JS code via _intsetFind, _scatterSetFind, etc.
      js_register_intrinsics(jsiRuntime)

      // Install __bridgeRegister JS function for bridge dispatch registration
      HermesBridge_installBridgeRegister(jsiRuntime)

      return JsEngine(runtime)
    }

    actual val version: String
      get() = Hermes_getVersion()!!.toKString()
  }

  actual var interruptHandler: InterruptHandler?
    get() = throw UnsupportedOperationException()
    set(value) {
      // Hermes has no per-instruction interrupt hook; its wall-clock
      // watchTimeLimit/asyncTriggerTimeout mechanism is not wired up yet.
      // Fail loudly instead of silently dropping the handler.
      throw UnsupportedOperationException("InterruptHandler is not supported by the Hermes native engine")
    }

  actual val memoryUsage: MemoryUsage
    get() {
      checkNotClosed()
      memScoped {
        val usage = alloc<HermesCoreMemoryUsage>()
        val ok = HermesContext_getMemoryUsage(contextPointer, usage.ptr)
        check(ok != 0) { "HermesContext_getMemoryUsage failed" }
        return MemoryUsage(
          heapSize = usage.heapSize,
          allocatedBytes = usage.allocatedBytes,
          totalAllocatedBytes = usage.totalAllocatedBytes,
          va = usage.va,
          externalBytes = usage.externalBytes,
          mallocSizeEstimate = usage.mallocSizeEstimate,
          peakAllocatedBytes = usage.peakAllocatedBytes,
          peakLiveAfterGC = usage.peakLiveAfterGC,
          numCollections = usage.numCollections,
          numMarkStackOverflows = usage.numMarkStackOverflows,
        )
      }
    }

  actual var memoryLimit: Long
    get() = throw UnsupportedOperationException()
    set(value) {
      throw UnsupportedOperationException("memoryLimit is not supported by the Hermes engine")
    }

  actual var gcThreshold: Long
    get() = throw UnsupportedOperationException()
    set(value) {
      throw UnsupportedOperationException("gcThreshold is not supported by the Hermes engine")
    }

  actual var maxStackSize: Long
    get() = throw UnsupportedOperationException()
    set(value) {
      throw UnsupportedOperationException("maxStackSize is not supported by the Hermes engine")
    }

  private fun HermesTaggedValue.toAny(errorFallback: String): Any? = when (tag) {
    HERMES_TAG_ERROR -> throw JsException(
      HermesContext_getLastError(contextPointer)?.toKString() ?: errorFallback,
    )
    HERMES_TAG_NULL -> null
    HERMES_TAG_INT -> number.toInt()
    // Numbers always cross as double; re-narrow integral values to Int.
    HERMES_TAG_DOUBLE -> number.toInt().let { if (it.toDouble() == number) it else number }
    HERMES_TAG_BOOL -> number != 0.0
    HERMES_TAG_STRING -> {
      val value = string!!.toKString()
      HermesContext_freeValue(contextPointer, string)
      value
    }
    else -> null
  }

  actual fun evaluate(script: String, fileName: String): Any? {
    checkNotClosed()
    return HermesContext_evaluate(contextPointer, script, fileName).useContents {
      toAny("Evaluation failed")
    }
  }

  actual fun evaluateForBridge(script: String, fileName: String): Any? {
    checkNotClosed()
    val bytecode = compile(script, fileName)
    val byteArrayPin = bytecode.pin()
    val handle = HermesContext_executeToHandle(
      contextPointer,
      byteArrayPin.addressOf(0).reinterpret<UByteVar>(),
      bytecode.size,
      fileName,
    )
    byteArrayPin.unpin()
    if (handle < 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw JsException(error?.toKString() ?: "Execution failed")
    }
    val result = bridgeForAny(contextPointer, handle)
    HermesBridge_freeHandle(contextPointer, handle)
    return result
  }

  actual fun compile(sourceCode: String, fileName: String, sourceMap: String?): ByteArray {
    checkNotClosed()
    memScoped {
      val bytecodeOut = alloc<CPointerVarOf<CPointer<ByteVar>>>()
      val bytecodeSizeOut = alloc<IntVar>()
      val result = HermesContext_compile(
        contextPointer,
        sourceCode,
        fileName,
        sourceMap,
        bytecodeOut.ptr,
        bytecodeSizeOut.ptr,
      )
      if (result == 0) {
        val error = HermesContext_getLastError(contextPointer)
        throw JsException(error?.toKString() ?: "Compilation failed")
      }
      val bytecodeSizeVal = bytecodeSizeOut.value
      val bytecode = if (bytecodeOut.value != null && bytecodeSizeVal > 0) {
        bytecodeOut.value!!.readBytes(bytecodeSizeVal)
      } else {
        ByteArray(0)
      }
      HermesContext_freeValue(contextPointer, bytecodeOut.value)
      return bytecode
    }
  }

  actual fun execute(bytecode: ByteArray, fileName: String): Any? {
    checkNotClosed()
    val byteArrayPin = bytecode.pin()
    val tagged = HermesContext_execute(
      contextPointer,
      byteArrayPin.addressOf(0).reinterpret<UByteVar>(),
      bytecode.size,
      fileName,
    )
    byteArrayPin.unpin()
    return tagged.useContents { toAny("Execution failed") }
  }

  actual fun gc() {
    checkNotClosed()
    HermesContext_gc(contextPointer)
  }

  actual fun getGlobalProperty(name: String): String? {
    checkNotClosed()
    memScoped {
      val valuePtr = alloc<CPointerVarOf<CPointer<ByteVar>>>()
      val result = HermesContext_getGlobalProperty(contextPointer, name, valuePtr.ptr)
      if (result == 0) {
        return null
      }
      val value = valuePtr.value?.toKString()
      HermesContext_freeValue(contextPointer, valuePtr.value)
      return value
    }
  }

  actual fun setGlobalProperty(name: String, value: String) {
    checkNotClosed()
    val result = HermesContext_setGlobalProperty(contextPointer, name, value)
    if (result == 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw UnsupportedOperationException(error?.toKString() ?: "Failed to set global property")
    }
  }

  actual fun deleteGlobalProperty(name: String) {
    checkNotClosed()
    val result = HermesContext_deleteGlobalProperty(contextPointer, name)
    if (result == 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw UnsupportedOperationException(error?.toKString() ?: "Failed to delete global property")
    }
  }

  actual fun callGlobalMethod(objectName: String, methodName: String) {
    checkNotClosed()
    val result = HermesContext_callGlobalMethod(contextPointer, objectName, methodName)
    if (result == 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw UnsupportedOperationException(error?.toKString() ?: "Failed to call global method")
    }
  }

  actual fun callGlobalFunctionWithStringArg(functionName: String, arg: String): String? {
    checkNotClosed()
    memScoped {
      val resultOut = alloc<CPointerVarOf<CPointer<ByteVar>>>()
      val result = HermesContext_callGlobalFunctionWithStringArg(
        contextPointer,
        functionName,
        arg,
        resultOut.ptr,
      )
      if (result == 0) {
        val error = HermesContext_getLastError(contextPointer)
        throw UnsupportedOperationException(error?.toKString() ?: "Failed to call global function")
      }
      val value = resultOut.value?.toKString()
      HermesContext_freeValue(contextPointer, resultOut.value)
      return value
    }
  }

  actual fun callRequireMethod(moduleId: String, methodName: String) {
    checkNotClosed()
    val result = HermesContext_callRequireMethod(contextPointer, moduleId, methodName)
    if (result == 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw UnsupportedOperationException(error?.toKString() ?: "Failed to call require method")
    }
  }

  actual fun installModuleLoader() {
    checkNotClosed()
    val result = HermesContext_installModuleLoader(contextPointer)
    if (result == 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw UnsupportedOperationException(error?.toKString() ?: "Failed to install module loader")
    }
  }

  actual fun hasGlobalFunction(name: String): Boolean {
    checkNotClosed()
    return HermesBridge_hasGlobalFunction(contextPointer, name) != 0
  }

  /**
   * Convert each element of [args] host -> JS and call `globalThis[name]` with them, then convert
   * the result back JS -> host through the bridge readers.
   *
   * @throws JsException when [name] is not a callable global, an argument has no JS counterpart
   *   (the message names the offending class), or the call itself throws.
   */
  actual fun callGuestFunction(name: String, args: List<Any?>): Any? {
    checkNotClosed()
    val context = requireNotNull(contextPointer) { "Engine has no native context" }

    val fnHandle = HermesBridge_getProperty(context, 0, name)
    if (fnHandle < 0 || HermesBridge_isFunction(context, fnHandle) == 0) {
      if (fnHandle >= 0) HermesBridge_freeHandle(context, fnHandle)
      throw JsException("callGuestFunction: no callable function '$name' on globalThis")
    }

    val argsHandle = HermesBridge_newArray(context)
    try {
      args.forEachIndexed { index, element ->
        val valueHandle = try {
          anyToJs(context, element)
        } catch (t: Throwable) {
          val cls = element?.let { it::class.qualifiedName } ?: "null"
          throw JsException(
            "callGuestFunction: cannot convert argument $index of class $cls to JS: ${t.message}",
          )
        }
        HermesBridge_setArrayElement(context, argsHandle, index, valueHandle)
        HermesBridge_freeHandle(context, valueHandle)
      }
      val resultHandle = HermesBridge_callFunction(context, fnHandle, argsHandle)
      if (resultHandle < 0) throw JsException("callGuestFunction: call to '$name' failed")
      try {
        return bridgeForAny(context, resultHandle)
      } finally {
        HermesBridge_freeHandle(context, resultHandle)
      }
    } finally {
      HermesBridge_freeHandle(context, argsHandle)
      HermesBridge_freeHandle(context, fnHandle)
    }
  }

  internal actual fun warmUpModule(moduleId: String, functionName: String) {
    checkNotClosed()
    // 1 = called, 0 = the module exports no such hook (not an error), -1 = the hook threw.
    val result = HermesBridge_warmUpModule(contextPointer, moduleId, functionName)
    if (result < 0) {
      throw JsException("Failed to warm up the bridge of module '$moduleId'")
    }
  }

  internal actual fun initOutboundChannel(outboundChannel: CallChannel) {
    checkNotClosed()
    val context = requireNotNull(contextPointer) { "Engine has no native context" }
    outboundChannels.putValue(context.rawValue.toLong(), outboundChannel)
    HermesContext_setOutboundChannelCallbacks(
      context,
      staticCFunction(::outboundChannelCallCallback),
      staticCFunction(::outboundChannelDisconnectCallback)
    )
    val result = HermesContext_setupOutboundCallChannel(contextPointer)
    if (result == 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw UnsupportedOperationException(error?.toKString() ?: "Failed to setup outbound call channel")
    }
  }

  internal actual fun getInboundChannel(): CallChannel {
    checkNotClosed()
    if (HermesContext_hasGlobalObject(contextPointer, INBOUND_CHANNEL_NAME) != 1) {
      throw IllegalStateException(
        "A global JavaScript object called $INBOUND_CHANNEL_NAME was not found. " +
          "Try confirming that Zipline.get() has been called."
      )
    }
    return object : CallChannel {
      override fun call(callJson: String): String {
        checkNotClosed()
        val resultPtr: CPointer<ByteVar>? = HermesContext_callInbound(
          contextPointer,
          INBOUND_CHANNEL_NAME,
          callJson
        )
        if (resultPtr == null) {
          val error = HermesContext_getLastError(contextPointer)
          throw JsException(error?.toKString() ?: "Failed to call inbound channel")
        }
        val resultStr = resultPtr.toKString()
        platform.posix.free(resultPtr)
        return resultStr
      }

      override fun disconnect(instanceName: String): Boolean {
        checkNotClosed()
        val resultPtr: CPointer<ByteVar>? = HermesContext_callInboundDisconnect(
          contextPointer,
          INBOUND_CHANNEL_NAME,
          instanceName
        )
        if (resultPtr == null) {
          val error = HermesContext_getLastError(contextPointer)
          throw JsException(error?.toKString() ?: "Failed to call inbound disconnect")
        }
        val resultStr = resultPtr.toKString()
        platform.posix.free(resultPtr)
        return resultStr == "true"
      }
    }
  }

  actual var rdmaChangeSink: RdmaChangeSink? = null

  actual fun initRdmaChangesChannel() {
    checkNotClosed()
    if (rdmaChangeSink == null) return
    val context = requireNotNull(contextPointer) { "Engine has no native context" }

    // Register per-change-type callbacks so the C++ JS host-function lambdas
    // write directly into the Kotlin RdmaChangeSink accumulator (matching the
    // gogabr/jni-bridges design).
    HermesContext_setRdmaCreateCallback(context, staticCFunction(::onRdmaCreate))
    HermesContext_setRdmaPropertyChangeCallback(context, staticCFunction(::onRdmaPropertyChange))
    HermesContext_setRdmaModifierChangeCallback(context, staticCFunction(::onRdmaModifierChange))
    HermesContext_setRdmaAddCallback(context, staticCFunction(::onRdmaAdd))
    HermesContext_setRdmaRemoveCallback(context, staticCFunction(::onRdmaRemove))
    HermesContext_setRdmaMoveCallback(context, staticCFunction(::onRdmaMove))
    HermesContext_setRdmaBridgeChangeCallback(context, staticCFunction(::onRdmaBridgeChange))
    HermesContext_setRdmaSetRemoveDetachCallback(context, staticCFunction(::onRdmaSetRemoveDetach))
    HermesContext_setRdmaSendChangesCallback(context, staticCFunction(::onRdmaSendChanges))

    rdmaChangeSinks.putValue(context.rawValue.toLong(), rdmaChangeSink!!)
    HermesContext_setRdmaChangeSink(context, staticCFunction(::rdmaChangeSinkSendChanges))
    val result = HermesContext_initRdmaChangesChannel(contextPointer)
    if (result == 0) {
      val error = HermesContext_getLastError(contextPointer)
      throw UnsupportedOperationException(error?.toKString() ?: "Failed to init RDMA changes channel")
    }
  }

  internal fun checkNotClosed() {
    check(!closed) { "JsEngine instance was closed" }
  }

  internal actual fun bridgeInitAll() {
    checkNotClosed()

    // Force initialization of all bridge modules
    bridgeRetainRefs.forEach { it() }
  }

  internal actual fun cdpAttach(listener: CdpListener): Boolean {
    checkNotClosed()
    val context = requireNotNull(contextPointer) { "Engine has no native context" }
    cdpListeners.putValue(context.rawValue.toLong(), listener)
    // The context pointer doubles as the opaque listener handle, matching the
    // outbound channel callbacks above. The registry entry is dropped by
    // cdpDisposedCallback when the native session is torn down.
    val result = HermesContext_cdpAttach(
      context,
      context,
      staticCFunction(::cdpMessageCallback),
      staticCFunction(::cdpTasksEnqueuedCallback),
      staticCFunction(::cdpDisposedCallback),
    )
    if (result == 0) {
      cdpListeners.removeValue(context.rawValue.toLong())
      return false
    }
    return true
  }

  internal actual fun cdpHandleCommand(json: String) {
    checkNotClosed()
    HermesContext_cdpHandleCommand(contextPointer, json)
  }

  internal actual fun cdpDrainTasks() {
    checkNotClosed()
    HermesContext_cdpDrainTasks(contextPointer)
  }

  internal actual fun cdpResetAgent() {
    checkNotClosed()
    HermesContext_cdpResetAgent(contextPointer)
  }

  actual override fun close() {
    if (!closed) {
      closed = true

      // Detach from the CDP debug server before the native session goes away
      // (HermesRuntime_destroy tears it down and drops the listener entry via
      // cdpDisposedCallback).
      app.cash.zipline.internal.cdp.CdpDebugSupport.detach(this)

      // Deregister this engine's channel/sink so late JS calls into the
      // (about to be destroyed) runtime can't reach Kotlin, and so the
      // per-context registries don't retain dead objects.
      if (contextPointer != null) {
        outboundChannels.removeValue(contextPointer.rawValue.toLong())
        HermesContext_setOutboundChannelCallbacks(contextPointer, null, null)

        rdmaChangeSinks.removeValue(contextPointer.rawValue.toLong())
        HermesContext_setRdmaChangeSink(contextPointer, null)
      }

      HermesRuntime_destroy(contextPointer)
    }
  }
}

