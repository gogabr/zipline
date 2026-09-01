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
@file:OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)

package app.cash.zipline

import app.cash.zipline.internal.bridge.CallChannel
import app.cash.zipline.internal.bridge.INBOUND_CHANNEL_NAME
import app.cash.zipline.internal.bridge.OUTBOUND_CHANNEL_NAME
import app.cash.zipline.quickjs.JSCFunctionListEntry
import app.cash.zipline.quickjs.JSClassDef
import app.cash.zipline.quickjs.JSClassIDVar
import app.cash.zipline.quickjs.JSContext
import app.cash.zipline.quickjs.JSMemoryUsage
import app.cash.zipline.quickjs.JSRuntime
import app.cash.zipline.quickjs.JSValue
import app.cash.zipline.quickjs.JS_AddGlobalThisGc
import app.cash.zipline.quickjs.JS_ComputeMemoryUsage
import app.cash.zipline.quickjs.JS_EVAL_FLAG_COMPILE_ONLY
import app.cash.zipline.quickjs.JS_EVAL_FLAG_STRICT
import app.cash.zipline.quickjs.JS_Eval
import app.cash.zipline.quickjs.JS_EvalFunction
import app.cash.zipline.quickjs.JS_FreeAtom
import app.cash.zipline.quickjs.JS_FreeCString
import app.cash.zipline.quickjs.JS_FreeContext
import app.cash.zipline.quickjs.JS_FreeRuntime
import app.cash.zipline.quickjs.JS_FreeValue
import app.cash.zipline.quickjs.JS_DupValue
import app.cash.zipline.quickjs.JS_GetException
import app.cash.zipline.quickjs.JS_GetGlobalObject
import app.cash.zipline.quickjs.JS_GetPropertyStr
import app.cash.zipline.quickjs.JS_GetPropertyUint32
import app.cash.zipline.quickjs.JS_GetRuntime
import app.cash.zipline.quickjs.JS_GetRuntimeOpaque
import app.cash.zipline.quickjs.JS_HasProperty
import app.cash.zipline.quickjs.JS_IsArray
import app.cash.zipline.quickjs.JS_IsException
import app.cash.zipline.quickjs.JS_IsNull
import app.cash.zipline.quickjs.JS_IsUndefined
import app.cash.zipline.quickjs.JS_NewObject
import app.cash.zipline.quickjs.JS_NewObjectProto
import app.cash.zipline.quickjs.JS_SetPropertyStr
import app.cash.zipline.quickjs.JS_NewAtom
import app.cash.zipline.quickjs.JS_NewClass
import app.cash.zipline.quickjs.JS_NewClassID
import app.cash.zipline.quickjs.JS_NewContext
import app.cash.zipline.quickjs.JS_NewContextNoEval
import app.cash.zipline.quickjs.JS_NewObjectClass
import app.cash.zipline.quickjs.JS_NewRuntime
import app.cash.zipline.quickjs.JS_NewString
import app.cash.zipline.quickjs.JS_READ_OBJ_BYTECODE
import app.cash.zipline.quickjs.JS_READ_OBJ_REFERENCE
import app.cash.zipline.quickjs.JS_ReadObject
import app.cash.zipline.quickjs.JS_ResolveModule
import app.cash.zipline.quickjs.JS_RunGC
import app.cash.zipline.quickjs.JS_SetGCThreshold
import app.cash.zipline.quickjs.JS_SetInterruptHandler
import app.cash.zipline.quickjs.JS_SetMaxStackSize
import app.cash.zipline.quickjs.JS_SetMemoryLimit
import app.cash.zipline.quickjs.JS_SetProperty
import app.cash.zipline.quickjs.JS_SetPropertyFunctionList
import app.cash.zipline.quickjs.JS_SetRuntimeOpaque
import app.cash.zipline.quickjs.JS_TAG_BOOL
import app.cash.zipline.quickjs.JS_TAG_EXCEPTION
import app.cash.zipline.quickjs.JS_TAG_FLOAT64
import app.cash.zipline.quickjs.JS_TAG_INT
import app.cash.zipline.quickjs.JS_TAG_NULL
import app.cash.zipline.quickjs.JS_TAG_OBJECT
import app.cash.zipline.quickjs.JS_TAG_STRING
import app.cash.zipline.quickjs.JS_TAG_UNDEFINED
import app.cash.zipline.quickjs.JS_ToCString
import app.cash.zipline.quickjs.JS_WRITE_OBJ_BYTECODE
import app.cash.zipline.quickjs.JS_WRITE_OBJ_REFERENCE
import app.cash.zipline.quickjs.JS_WriteObject
import app.cash.zipline.quickjs.JsCallFunction
import app.cash.zipline.quickjs.JsDisconnectFunction
import app.cash.zipline.quickjs.JsFalse
import app.cash.zipline.quickjs.JsTrue
import app.cash.zipline.quickjs.JsUndefined
import app.cash.zipline.quickjs.JsValueArrayToInstanceRef
import app.cash.zipline.quickjs.JsValueGetBool
import app.cash.zipline.quickjs.JsValueGetFloat64
import app.cash.zipline.quickjs.JsValueGetInt
import app.cash.zipline.quickjs.JsValueGetNormTag
import app.cash.zipline.quickjs.installFinalizationRegistry
import app.cash.zipline.quickjs.js_free
import app.cash.zipline.quickjs.js_intset_register_builtins
import app.cash.zipline.quickjs.JsGetOwnPropertyNames
import app.cash.zipline.quickjs.JsGetPropertyAt
import app.cash.zipline.quickjs.JsGetPropertyName
import app.cash.zipline.quickjs.JsFreePropertyEnum
import app.cash.zipline.quickjs.JsNewCFunction
import app.cash.zipline.quickjs.JsNewFloat64
import app.cash.zipline.quickjs.JsNewTagInt
import kotlin.experimental.ExperimentalNativeApi
import kotlinx.cinterop.CArrayPointer
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.CValue
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.convert
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.cstr
import kotlinx.cinterop.free
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.refTo
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKStringFromUtf8
import kotlinx.cinterop.utf8
import kotlinx.cinterop.value
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import platform.posix.size_tVar

val bridgeRetainRefs = mutableListOf<() -> Unit>()

fun registerBridgeInitHook(hook: () -> Unit) {
  bridgeRetainRefs.add(hook)
}

private val bridgeTable = mutableMapOf<String, StableRef<(CPointer<JSContext>, CValue<JSValue>) -> Any>>()

/**
 * Create a new JS instance whose prototype is the **existing** retained guest prototype for
 * [fqn] (registered by the guest's module-load `__bridgeRegister`). The prototype is never
 * created or cloned; the caller owns the returned value. Returns null when the prototype has
 * not been registered — callers must treat that as a crash (missing module-load registration),
 * never as a fallback.
 */
public fun newJsObject(ctx: CPointer<JSContext>, fqn: String): CValue<JSValue>? {
  val quickJs = quickJsFor(ctx)
  val proto = quickJs.bridgeProtos[fqn] ?: return null
  return JS_NewObjectProto(ctx, proto) // the map keeps its own reference
}

@EngineApi
actual class QuickJs private constructor(
  private val runtime: CPointer<JSRuntime>,
  internal val context: CPointer<JSContext>,
  internal val contextForCompiling: CPointer<JSContext>,
) : AutoCloseable {
  actual companion object {
    actual fun create(): QuickJs {
      // Use the default system allocator (mimalloc was removed: its thread-exit
      // heap teardown caused invalid frees at pthread TSD cleanup).
      val runtime = JS_NewRuntime() ?: throw OutOfMemoryError()
      val context = JS_NewContextNoEval(runtime)
      if (context == null) {
        JS_FreeRuntime(runtime)
        throw OutOfMemoryError()
      }
      val contextForCompiling = JS_NewContext(runtime)
      if (contextForCompiling == null) {
        JS_FreeRuntime(runtime)
        throw OutOfMemoryError()
      }
      return QuickJs(runtime, context, contextForCompiling)
        .apply {
          // Explicitly assign default values to these properties so the backing fields values
          // are consistent with their native fields. (QuickJS doesn't offer accessors for these.)
          // TODO extract this somewhere common to share with jniMain/
          memoryLimit = -1L
          gcThreshold = 256L * 1024L
          maxStackSize = 512L * 1024L // Override the QuickJS default which is 256 KiB
          installFinalizationRegistry(context, contextForCompiling)
          js_intset_register_builtins(context)
        }
    }

    actual val version: String
      get() = quickJsVersion

    private const val RDMA_BATCH_SIZE = 2048
  }

  private val jsInterruptHandlerCFunction = staticCFunction(::jsInterruptHandlerGlobal)
  private val thisPtr = StableRef.create(this)
  init {
    JS_SetRuntimeOpaque(runtime, thisPtr.asCPointer())
    JS_SetInterruptHandler(runtime, jsInterruptHandlerCFunction, thisPtr.asCPointer())

    JS_AddGlobalThisGc(context)
  }

  private var closed = false
  private var outboundChannel: CallChannel? = null
  private var functionList: CArrayPointer<JSCFunctionListEntry>? = null

  // --- Host2JS bridge state (populated by guest module-load registration) ---

  /** JS values are context-local, so retained prototypes/factories are per-instance. */
  internal val bridgeProtos = mutableMapOf<String, CValue<JSValue>>()

  internal var bridgeNewLong: CValue<JSValue>? = null
  internal var bridgeNewArrayList: CValue<JSValue>? = null
  internal var bridgeNewLinkedHashMap: CValue<JSValue>? = null

  /**
   * The JSContext that executes guest code. Needed to call host2js conversion functions
   * ([app.cash.zipline.anyToJs], [app.cash.zipline.kotlinLongToJs]) and to read converted
   * values back via [bridgeForAny].
   */
  val jsContext: CPointer<JSContext> get() = context

  internal fun jsInterruptHandler(runtime: CPointer<JSRuntime>?): Int {
    val interruptHandler = interruptHandler ?: return 0

    JS_SetInterruptHandler(runtime, null, null) // Suppress re-enter.

    val result = try {
      interruptHandler.poll()
    } catch (t: Throwable) {
      // TODO: propagate the interrupt handler's exceptions through JS.
      true // Halt JS.
    } finally {
      // Restore handler.
      JS_SetInterruptHandler(runtime, jsInterruptHandlerCFunction, thisPtr.asCPointer())
    }

    return if (result) 1 else 0
  }

  actual var interruptHandler: InterruptHandler? = null
    set(value) {
      checkNotClosed()

      field = value
    }

  /** Memory usage statistics for the JavaScript engine. */
  actual val memoryUsage: MemoryUsage
    get() {
      checkNotClosed()

      memScoped {
        val jsMemoryUsage = alloc<JSMemoryUsage>()
        JS_ComputeMemoryUsage(runtime, jsMemoryUsage.ptr)
        return MemoryUsage(
          jsMemoryUsage.malloc_count,
          jsMemoryUsage.malloc_size,
          jsMemoryUsage.malloc_limit,
          jsMemoryUsage.memory_used_count,
          jsMemoryUsage.memory_used_size,
          jsMemoryUsage.atom_count,
          jsMemoryUsage.atom_size,
          jsMemoryUsage.str_count,
          jsMemoryUsage.str_size,
          jsMemoryUsage.obj_count,
          jsMemoryUsage.obj_size,
          jsMemoryUsage.prop_count,
          jsMemoryUsage.prop_size,
          jsMemoryUsage.shape_count,
          jsMemoryUsage.shape_size,
          jsMemoryUsage.js_func_count,
          jsMemoryUsage.js_func_size,
          jsMemoryUsage.js_func_code_size,
          jsMemoryUsage.js_func_pc2line_count,
          jsMemoryUsage.js_func_pc2line_size,
          jsMemoryUsage.c_func_count,
          jsMemoryUsage.array_count,
          jsMemoryUsage.fast_array_count,
          jsMemoryUsage.fast_array_elements,
          jsMemoryUsage.binary_object_count,
          jsMemoryUsage.binary_object_size,
        )
      }
    }

  /** Default is -1. Use -1 for no limit. */
  actual var memoryLimit: Long = -1L
    set(value) {
      checkNotClosed()

      field = value
      JS_SetMemoryLimit(runtime, value.convert())
    }

  /** Default is 256 KiB. Use -1 to disable automatic GC. */
  actual var gcThreshold: Long = -1L
    set(value) {
      checkNotClosed()

      field = value
      JS_SetGCThreshold(runtime, value.convert())
    }

  /** Default is 512 KiB. Use 0 to disable the maximum stack size check. */
  actual var maxStackSize: Long = -1L
    set(value) {
      checkNotClosed()

      field = value
      JS_SetMaxStackSize(runtime, value.convert())
    }

  actual fun evaluate(script: String, fileName: String): Any? {
    val bytecode = compile(script, fileName)
    return execute(bytecode)
  }

  actual fun evaluateForBridge(script: String, fileName: String): Any? {
    checkNotClosed()

    val bytecode = compile(script, fileName)
    val raw = executeRaw(bytecode)
    val result = bridgeForAny(context, raw)
    JS_FreeValue(context, raw)
    return result
  }

  actual fun compile(sourceCode: String, fileName: String): ByteArray {
    checkNotClosed()

    val sourceCodeUtf8 = sourceCode.utf8
    val compiled = JS_Eval(
      contextForCompiling,
      sourceCodeUtf8,
      // Drop trailing '\0':
      (sourceCodeUtf8.size - 1).convert(),
      fileName.utf8,
      JS_EVAL_FLAG_COMPILE_ONLY or JS_EVAL_FLAG_STRICT,
    )
    if (JS_IsException(compiled) != 0) {
      throwJsException()
    }
    val result = memScoped {
      val bufferLengthVar = alloc<size_tVar>()
      val buffer = JS_WriteObject(
        contextForCompiling,
        bufferLengthVar.ptr,
        compiled,
        JS_WRITE_OBJ_BYTECODE or JS_WRITE_OBJ_REFERENCE,
      )
      val bufferLength = bufferLengthVar.value.toInt()

      val result = if (buffer != null && bufferLength > 0) {
        buffer.readBytes(bufferLength)
      } else {
        null
      }

      JS_FreeValue(contextForCompiling, compiled)
      js_free(contextForCompiling, buffer)

      result
    }
    return result ?: throwJsException()
  }

  actual fun execute(bytecode: ByteArray): Any? {
    val value = executeRaw(bytecode)
    val result = value.toKotlinInstanceOrNull()
    JS_FreeValue(context, value)
    return result
  }

  /**
   * Loads [bytecode] into this context and evaluates it, returning the raw, owned JS value.
   * The caller must JS_FreeValue the result.
   */
  private fun executeRaw(bytecode: ByteArray): CValue<JSValue> {
    checkNotClosed()

    @Suppress("UNCHECKED_CAST") // ByteVar and UByteVar have the same bit layout.
    val bytecodeRef = bytecode.refTo(0) as CValuesRef<UByteVar>
    val obj = JS_ReadObject(
      context,
      bytecodeRef,
      bytecode.size.convert(),
      JS_READ_OBJ_BYTECODE or JS_READ_OBJ_REFERENCE or JS_EVAL_FLAG_STRICT,
    )
    if (JS_IsException(obj) != 0) {
      throwJsException()
    }
    if (JS_ResolveModule(context, obj) != 0) {
      throw QuickJsException("Failed to resolve JS module")
    }
    val value = JS_EvalFunction(context, obj)
    if (JS_IsException(value) != 0) {
      JS_FreeValue(context, value)
      throwJsException()
    }
    return value
  }

  internal actual fun initOutboundChannel(outboundChannel: CallChannel) {
    checkNotClosed()

    val globalThis = JS_GetGlobalObject(context)
    val propertyName = JS_NewAtom(context, OUTBOUND_CHANNEL_NAME)
    try {
      if (JS_HasProperty(context, globalThis, propertyName) != 0) {
        throw IllegalStateException("A global object called $OUTBOUND_CHANNEL_NAME already exists")
      }

      val outboundCallChannelClassId = memScoped {
        val id = alloc<JSClassIDVar>()
        JS_NewClassID(id.ptr)

        val classDef = alloc<JSClassDef>()
        classDef.class_name = "OutboundCallChannel".cstr.ptr
        JS_NewClass(runtime, id.value, classDef.ptr)

        id.value.toInt() // Why doesn't JS_NewObjectClass accept a UInt / JSClassID?
      }

      val jsOutboundCallChannel = JS_NewObjectClass(context, outboundCallChannelClassId)
      if (JS_IsException(jsOutboundCallChannel) != 0 ||
          JS_SetProperty(context, globalThis, propertyName, jsOutboundCallChannel) <= 0
      ) {
        throwJsException()
      }

      functionList = nativeHeap.allocArrayOf(
        JsCallFunction(staticCFunction(::outboundCall)),
        JsDisconnectFunction(staticCFunction(::outboundDisconnect)),
      )
      JS_SetPropertyFunctionList(context, jsOutboundCallChannel, functionList, 2)
    } finally {
      JS_FreeAtom(context, propertyName)
      JS_FreeValue(context, globalThis)
    }

    this.outboundChannel = outboundChannel
  }

  internal fun jsOutboundCall(argc: Int, argv: CArrayPointer<JSValue>): CValue<JSValue> {
    assert(argc == 1)
    val arg0 = JsValueArrayToInstanceRef(argv, 0).toKotlinInstanceOrNull() as String
    val result = outboundChannel!!.call(arg0)
    return JS_NewString(context, result.utf8)
  }

  internal fun jsOutboundDisconnect(argc: Int, argv: CArrayPointer<JSValue>): CValue<JSValue> {
    assert(argc == 1)
    val arg0 = JsValueArrayToInstanceRef(argv, 0).toKotlinInstanceOrNull() as String
    val result = outboundChannel!!.disconnect(arg0)
    return result.toJsValue()
  }

  internal actual fun getInboundChannel(): CallChannel {
    checkNotClosed()

    val globalThis = JS_GetGlobalObject(context)
    val inboundChannelAtom = JS_NewAtom(context, INBOUND_CHANNEL_NAME)
    val hasProperty = JS_HasProperty(context, globalThis, inboundChannelAtom) != 0
    JS_FreeValue(context, globalThis)
    JS_FreeAtom(context, inboundChannelAtom)
    check(hasProperty) { "A global JavaScript object called $INBOUND_CHANNEL_NAME was not found. Try confirming that Zipline.get() has been called." }

    return InboundCallChannel(this)
  }

  actual fun gc() {
    JS_RunGC(runtime)
  }

  actual var rdmaChangeSink: RdmaChangeSink? = null

  actual fun initRdmaChangesChannel() {
    if (rdmaChangeSink == null) return

    val globalThis = JS_GetGlobalObject(context)

    val rdmaObj = JS_NewObject(context)
    if (JS_IsException(rdmaObj) != 0) {
      JS_FreeValue(context, globalThis)
      return
    }

    JS_SetPropertyStr(
      context, rdmaObj, "appendCreate",
      JsNewCFunction(context, staticCFunction(::rdmaAppendCreateGlobal), "appendCreate", 2),
    )
    JS_SetPropertyStr(
      context, rdmaObj, "appendPropertyChange",
      JsNewCFunction(context, staticCFunction(::rdmaAppendPropertyChangeGlobal), "appendPropertyChange", 4),
    )
    JS_SetPropertyStr(
      context, rdmaObj, "appendModifierChange",
      JsNewCFunction(context, staticCFunction(::rdmaAppendModifierChangeGlobal), "appendModifierChange", 2),
    )
    JS_SetPropertyStr(
      context, rdmaObj, "appendAdd",
      JsNewCFunction(context, staticCFunction(::rdmaAppendAddGlobal), "appendAdd", 4),
    )
    JS_SetPropertyStr(
      context, rdmaObj, "appendRemove",
      JsNewCFunction(context, staticCFunction(::rdmaAppendRemoveGlobal), "appendRemove", 3),
    )
    JS_SetPropertyStr(
      context, rdmaObj, "setRemoveDetach",
      JsNewCFunction(context, staticCFunction(::rdmaSetRemoveDetachGlobal), "setRemoveDetach", 1),
    )
    JS_SetPropertyStr(
      context, rdmaObj, "appendMove",
      JsNewCFunction(context, staticCFunction(::rdmaAppendMoveGlobal), "appendMove", 5),
    )
    JS_SetPropertyStr(
      context, rdmaObj, "appendBridgeChange",
      JsNewCFunction(context, staticCFunction(::rdmaAppendBridgeChangeGlobal), "appendBridgeChange", 2)
    )
    JS_SetPropertyStr(
      context, rdmaObj, "finishChanges",
      JsNewCFunction(context, staticCFunction(::rdmaFinishChangesGlobal), "finishChanges", 0),
    )
    JS_SetPropertyStr(
      context, rdmaObj, "changesLength",
      JsNewCFunction(context, staticCFunction(::rdmaChangesLengthGlobal), "changesLength", 0),
    )

    JS_SetPropertyStr(context, globalThis, "app_cash_redwood_rdmaSendChanges", rdmaObj)
    JS_FreeValue(context, globalThis)
  }

  internal actual fun bridgeInitAll() {
    checkNotClosed()

    // Force initialization of all bridge modules
    bridgeRetainRefs.forEach { it() }

    val globalThis = JS_GetGlobalObject(context)
    JS_SetPropertyStr(context, globalThis, "__bridgeRegister",
      JsNewCFunction(context, staticCFunction(::bridgeRegisterGlobal), "__bridgeRegister", 2))
    JS_SetPropertyStr(context, globalThis, "__bridgeRegisterRuntime",
      JsNewCFunction(context, staticCFunction(::bridgeRegisterRuntimeGlobal), "__bridgeRegisterRuntime", 3))
    JS_FreeValue(context, globalThis)
  }

  actual override fun close() {
    if (!closed) {
      functionList?.let { ptr ->
        nativeHeap.free(ptr)
      }
      functionList = null
      bridgeProtos.values.forEach { JS_FreeValue(context, it) }
      bridgeProtos.clear()
      bridgeNewLong?.let { JS_FreeValue(context, it) }
      bridgeNewArrayList?.let { JS_FreeValue(context, it) }
      bridgeNewLinkedHashMap?.let { JS_FreeValue(context, it) }
      bridgeNewLong = null
      bridgeNewArrayList = null
      bridgeNewLinkedHashMap = null
      JS_FreeContext(contextForCompiling)
      JS_FreeContext(context)
      JS_FreeRuntime(runtime)
      thisPtr.dispose()
      closed = true
    }
  }

  internal fun checkNotClosed() {
    check(!closed) { "QuickJs instance was closed" }
  }

  private fun throwJsException(): Nothing {
    val exceptionValue = JS_GetException(context)

    val messageValue = JS_GetPropertyStr(context, exceptionValue, "message")
    val stackValue = JS_GetPropertyStr(context, exceptionValue, "stack")

    val message = JS_ToCString(
      context,
      messageValue.takeUnless { JS_IsUndefined(messageValue) != 0 } ?: exceptionValue,
    )?.toKStringFromUtf8() ?: ""
    JS_FreeValue(context, messageValue)

    val stack = JS_ToCString(context, stackValue)!!.toKStringFromUtf8()
    JS_FreeValue(context, stackValue)
    JS_FreeValue(context, exceptionValue)

    throw QuickJsException(message, stack)
  }

  internal fun CValue<JSValue>.toKotlinInstanceOrNull(): Any? {
    return when (JsValueGetNormTag(this)) {
      JS_TAG_EXCEPTION -> throwJsException()

      JS_TAG_STRING -> {
        val cString = JS_ToCString(context, this)!!
        val string = cString.toKStringFromUtf8()
        JS_FreeCString(context, cString)
        string
      }

      JS_TAG_BOOL -> JsValueGetBool(this) != 0

      JS_TAG_INT -> JsValueGetInt(this)

      JS_TAG_FLOAT64 -> JsValueGetFloat64(this)

      JS_TAG_NULL, JS_TAG_UNDEFINED -> null

      JS_TAG_OBJECT -> {
        if (JS_IsArray(context, this) != 0) {
          val lengthProperty = JS_GetPropertyStr(context, this, "length")
          val length = JsValueGetInt(lengthProperty)
          JS_FreeValue(context, lengthProperty)

          Array(length) {
            val element = JS_GetPropertyUint32(context, this, it.convert())
            val value = element.toKotlinInstanceOrNull()
            JS_FreeValue(context, element)
            value
          }
        } else {
          null
        }
      }

      else -> null
    }
  }

  private fun Boolean.toJsValue(): CValue<JSValue> {
    return if (this) JsTrue() else JsFalse()
  }

  // --- RDMA Changes Support ---

  private var removeCounter = 0

  internal fun rdmaAppendCreate(argc: Int, argv: CArrayPointer<JSValue>): CValue<JSValue> {
    val id = JsNumberToInt(JsValueArrayToInstanceRef(argv, 0))
    val tag = JsNumberToInt(JsValueArrayToInstanceRef(argv, 1))
    rdmaChangeSink?.createCreate(id, tag)
    return JsUndefined()
  }

  internal fun rdmaAppendPropertyChange(argc: Int, argv: CArrayPointer<JSValue>): CValue<JSValue> {
    val id = JsNumberToInt(JsValueArrayToInstanceRef(argv, 0))
    val widgetTag = JsNumberToInt(JsValueArrayToInstanceRef(argv, 1))
    val propertyTag = JsNumberToInt(JsValueArrayToInstanceRef(argv, 2))
    val jsValue = JsValueArrayToInstanceRef(argv, 3)
    val value = jsValueToJsonElement(jsValue)
    rdmaChangeSink?.createPropertyChange(id, widgetTag, propertyTag, value)
    return JsUndefined()
  }

  internal fun rdmaAppendModifierChange(argc: Int, argv: CArrayPointer<JSValue>): CValue<JSValue> {
    val id = JsNumberToInt(JsValueArrayToInstanceRef(argv, 0))
    val jsValue = JsValueArrayToInstanceRef(argv, 1)
    val elements = jsArrayToModifierElements(jsValue)
    rdmaChangeSink?.createModifierChange(id, elements)
    return JsUndefined()
  }

  internal fun rdmaAppendAdd(argc: Int, argv: CArrayPointer<JSValue>): CValue<JSValue> {
    val id = JsNumberToInt(JsValueArrayToInstanceRef(argv, 0))
    val childrenTag = JsNumberToInt(JsValueArrayToInstanceRef(argv, 1))
    val childId = JsNumberToInt(JsValueArrayToInstanceRef(argv, 2))
    val index = JsNumberToInt(JsValueArrayToInstanceRef(argv, 3))
    rdmaChangeSink?.createAdd(id, childrenTag, childId, index)
    return JsUndefined()
  }

  internal fun rdmaAppendRemove(argc: Int, argv: CArrayPointer<JSValue>): CValue<JSValue> {
    val id = JsNumberToInt(JsValueArrayToInstanceRef(argv, 0))
    val childrenTag = JsNumberToInt(JsValueArrayToInstanceRef(argv, 1))
    val index = JsNumberToInt(JsValueArrayToInstanceRef(argv, 2))
    rdmaChangeSink?.createRemove(id, childrenTag, index, false)
    val currentIndex = removeCounter
    removeCounter++
    return JsNewTagInt(currentIndex)
  }

  internal fun rdmaSetRemoveDetach(argc: Int, argv: CArrayPointer<JSValue>): CValue<JSValue> {
    val idx = JsNumberToInt(JsValueArrayToInstanceRef(argv, 0))
    rdmaChangeSink?.setRemoveDetach(idx)
    return JsUndefined()
  }

  internal fun rdmaAppendMove(argc: Int, argv: CArrayPointer<JSValue>): CValue<JSValue> {
    val id = JsNumberToInt(JsValueArrayToInstanceRef(argv, 0))
    val childrenTag = JsNumberToInt(JsValueArrayToInstanceRef(argv, 1))
    val fromIndex = JsNumberToInt(JsValueArrayToInstanceRef(argv, 2))
    val toIndex = JsNumberToInt(JsValueArrayToInstanceRef(argv, 3))
    val count = JsNumberToInt(JsValueArrayToInstanceRef(argv, 4))
    rdmaChangeSink?.createMove(id, childrenTag, fromIndex, toIndex, count)
    return JsUndefined()
  }

  internal fun rdmaAppendBridgeChange(argc: Int, argv: CArrayPointer<JSValue>): CValue<JSValue> {
    val id = JsNumberToInt(JsValueArrayToInstanceRef(argv, 0))
    val jsToWrap = JsValueArrayToInstanceRef(argv, 1)
    val bridgeDispatchVal = JS_GetPropertyStr(context, jsToWrap, "bridge_dispatch")
    if (JS_IsUndefined(bridgeDispatchVal) != 0) {
      val ctorName = JS_GetPropertyStr(context, jsToWrap, "constructor")
      val ctorNameStr = JS_GetPropertyStr(context, ctorName, "name")
      val cstr = JS_ToCString(context, ctorNameStr)
      val name = if (cstr != null) cstr.toKStringFromUtf8().also { JS_FreeCString(context, cstr) } else "unknown"
      JS_FreeValue(context, ctorNameStr)
      JS_FreeValue(context, ctorName)
      JS_FreeValue(context, bridgeDispatchVal)
      throw NullPointerException("bridge_dispatch not set on JS object, constructor: $name")
    }
    val dispatchFn = JsValueGetFloat64(bridgeDispatchVal).toRawBits()
      .toCPointer<UByteVar>()!!.asStableRef<(CPointer<JSContext>, CValue<JSValue>) -> Any>()
    val kToWrap = dispatchFn.get()(context, jsToWrap)
    rdmaChangeSink!!.createBridgeChange(id, kToWrap)
    JS_FreeValue(context, bridgeDispatchVal)
    return JsUndefined()
  }

  // -- Bridge registration table (populated by @WithJS2HostBridge module-level inits) --

  internal fun bridgeRegisterJsHandler(
    argc: Int,
    argv: CArrayPointer<JSValue>,
  ): CValue<JSValue> {
    if (argc < 2) return JsUndefined()
    val cstr = JS_ToCString(context, JsValueArrayToInstanceRef(argv, 0))
    if (cstr == null) return JsUndefined()
    val fq = cstr.toKStringFromUtf8()
    JS_FreeCString(context, cstr)

    val ctor = JsValueArrayToInstanceRef(argv, 1)
    if (JS_IsUndefined(ctor) != 0) return JsUndefined()

    // Retain the class prototype for host2js instance creation (newJsObject) and set
    // bridge_dispatch only when a JS2Host converter exists. Lenient: host2js-only classes
    // register fine, and unknown FQNs are logged, never thrown.
    val proto = JS_GetPropertyStr(context, ctor, "prototype")
    if (JS_IsUndefined(proto) == 0 && JS_IsNull(proto) == 0) {
      bridgeProtos[fq]?.let { JS_FreeValue(context, it) }
      bridgeProtos[fq] = JS_DupValue(context, proto)

      val dispatchFn = bridgeTable[fq]
      if (dispatchFn == null) {
        println("BRIDGE: __bridgeRegister FQN not found in bridgeTable: '$fq' (host2js-only class)")
      } else {
        val bits = dispatchFn.asCPointer().rawValue.toLong()
        JS_SetPropertyStr(context, proto, "bridge_dispatch",
          JsNewFloat64(Double.fromBits(bits)))
      }
    }
    JS_FreeValue(context, proto)
    return JsUndefined()
  }

  /**
   * Handles the guest's module-load `__bridgeRegisterRuntime(__BridgeRuntimeFactories)` call,
   * retaining the three factory methods for host2js collection/Long construction. A guest
   * compiled with the bridge plugin always calls this; a missing call is a crash at conversion
   * time (see [app.cash.zipline.anyToJs]).
   */
  internal fun bridgeRegisterRuntimeJsHandler(
    argc: Int,
    argv: CArrayPointer<JSValue>,
  ): CValue<JSValue> {
    if (argc < 1) {
      println("BRIDGE: __bridgeRegisterRuntime expected 1 arg, got $argc")
      return JsUndefined()
    }
    val factories = JsValueArrayToInstanceRef(argv, 0)
    bridgeNewLong?.let { JS_FreeValue(context, it) }
    bridgeNewArrayList?.let { JS_FreeValue(context, it) }
    bridgeNewLinkedHashMap?.let { JS_FreeValue(context, it) }
    bridgeNewLong = JS_GetPropertyStr(context, factories, "newLong")
    bridgeNewArrayList = JS_GetPropertyStr(context, factories, "newArrayList")
    bridgeNewLinkedHashMap = JS_GetPropertyStr(context, factories, "newLinkedHashMap")
    return JsUndefined()
  }

  internal fun rdmaFinishChanges(): CValue<JSValue> {
    removeCounter = 0
    rdmaChangeSink?.sendChanges()
    return JsUndefined()
  }

  internal fun rdmaChangesLength(): CValue<JSValue> {
    return JsNewTagInt(removeCounter)
  }

  private fun jsValueToJsonElement(jsValue: CValue<JSValue>): JsonElement {
    return when (JsValueGetNormTag(jsValue)) {
      JS_TAG_INT -> JsonPrimitive(JsValueGetInt(jsValue))
      JS_TAG_BOOL -> JsonPrimitive(JsValueGetBool(jsValue) != 0)
      JS_TAG_FLOAT64 -> {
        val v = JsValueGetFloat64(jsValue)
        val lv = v.toLong()
        if (v == lv.toDouble()) {
          JsonPrimitive(lv)
        } else {
          JsonPrimitive(v)
        }
      }
      JS_TAG_STRING -> {
        val cString = JS_ToCString(context, jsValue)!!
        val string = cString.toKStringFromUtf8()
        JS_FreeCString(context, cString)
        JsonPrimitive(string)
      }
      JS_TAG_NULL, JS_TAG_UNDEFINED -> JsonNull
      JS_TAG_OBJECT -> {
        if (JS_IsArray(context, jsValue) != 0) {
          jsArrayToJsonArray(jsValue)
        } else {
          jsObjectToJsonObject(jsValue)
        }
      }
      else -> JsonNull
    }
  }

  private fun jsArrayToJsonArray(jsValue: CValue<JSValue>): JsonArray {
    val lengthProp = JS_GetPropertyStr(context, jsValue, "length")
    val length = JsValueGetInt(lengthProp)
    JS_FreeValue(context, lengthProp)
    return buildJsonArray {
      for (i in 0 until length) {
        val element = JS_GetPropertyUint32(context, jsValue, i.convert())
        val jsonElement = jsValueToJsonElement(element)
        add(jsonElement)
        JS_FreeValue(context, element)
      }
    }
  }

  private fun jsObjectToJsonObject(jsValue: CValue<JSValue>): JsonObject {
    return buildJsonObject {
      memScoped {
        val count = alloc<IntVar>()
        val ptab = JsGetOwnPropertyNames(context, jsValue, count.ptr) ?: return@buildJsonObject
        val n = count.value
        for (i in 0 until n) {
          val keyCStr = JsGetPropertyName(context, ptab, i) ?: continue
          val key = keyCStr.toKStringFromUtf8()
          JS_FreeCString(context, keyCStr)
          val propVal = JsGetPropertyAt(context, jsValue, ptab, i)
          val jsonElement = jsValueToJsonElement(propVal)
          put(key, jsonElement)
          JS_FreeValue(context, propVal)
        }
        JsFreePropertyEnum(context, ptab)
      }
    }
  }

  private fun jsArrayToModifierElements(jsValue: CValue<JSValue>): List<Pair<Int, JsonElement>> {
    val lengthProp = JS_GetPropertyStr(context, jsValue, "length")
    val length = JsValueGetInt(lengthProp)
    JS_FreeValue(context, lengthProp)
    val elements = mutableListOf<Pair<Int, JsonElement>>()
    for (j in 0 until length) {
      val elem = JS_GetPropertyUint32(context, jsValue, j.convert())
      val modTagVal = JS_GetPropertyUint32(context, elem, 0u)
      val modTag = JsNumberToInt(modTagVal)
      JS_FreeValue(context, modTagVal)
      val modVal = JS_GetPropertyUint32(context, elem, 1u)
      val jsonVal = if (JS_IsUndefined(modVal) != 0) {
        JsonNull
      } else {
        jsValueToJsonElement(modVal)
      }
      JS_FreeValue(context, modVal)
      elements.add(Pair(modTag, jsonVal))
      JS_FreeValue(context, elem)
    }
    return elements
  }
}

internal fun jsInterruptHandlerGlobal(runtime: CPointer<JSRuntime>?, opaque: COpaquePointer?): Int {
  val quickJs = opaque!!.asStableRef<QuickJs>().get()
  return quickJs.jsInterruptHandler(runtime)
}

@Suppress("UNUSED_PARAMETER") // API shape mandated by QuickJs.
internal fun outboundCall(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.jsOutboundCall(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace() // TODO throw to JS return null
    throw t
  }
}

@Suppress("UNUSED_PARAMETER") // API shape mandated by QuickJs.
internal fun outboundDisconnect(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.jsOutboundDisconnect(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace() // TODO throw to JS return null
    throw t
  }
}

// --- RDMA Changes C callbacks (registered via staticCFunction) ---

@Suppress("UNUSED_PARAMETER")
internal fun rdmaAppendCreateGlobal(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.rdmaAppendCreate(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace()
    throw t
  }
}

@Suppress("UNUSED_PARAMETER")
internal fun rdmaAppendPropertyChangeGlobal(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.rdmaAppendPropertyChange(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace()
    throw t
  }
}

@Suppress("UNUSED_PARAMETER")
internal fun rdmaAppendModifierChangeGlobal(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.rdmaAppendModifierChange(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace()
    throw t
  }
}

@Suppress("UNUSED_PARAMETER")
internal fun rdmaAppendAddGlobal(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.rdmaAppendAdd(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace()
    throw t
  }
}

@Suppress("UNUSED_PARAMETER")
internal fun rdmaAppendRemoveGlobal(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.rdmaAppendRemove(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace()
    throw t
  }
}

@Suppress("UNUSED_PARAMETER")
internal fun rdmaSetRemoveDetachGlobal(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.rdmaSetRemoveDetach(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace()
    throw t
  }
}

@Suppress("UNUSED_PARAMETER")
internal fun rdmaAppendMoveGlobal(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.rdmaAppendMove(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace()
    throw t
  }
}

@Suppress("UNUSED_PARAMETER")
internal fun rdmaAppendBridgeChangeGlobal(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.rdmaAppendBridgeChange(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace()
    throw t
  }
}

@Suppress("UNUSED_PARAMETER")
internal fun rdmaFinishChangesGlobal(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.rdmaFinishChanges()
  } catch (t: Throwable) {
    t.printStackTrace()
    throw t
  }
}

@Suppress("UNUSED_PARAMETER")
internal fun rdmaChangesLengthGlobal(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.rdmaChangesLength()
  } catch (t: Throwable) {
    t.printStackTrace()
    throw t
  }
}

@Suppress("UNUSED_PARAMETER")
internal fun bridgeRegisterGlobal(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.bridgeRegisterJsHandler(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace()
    throw t
  }
}

@Suppress("UNUSED_PARAMETER")
internal fun bridgeRegisterRuntimeGlobal(
  context: CPointer<JSContext>,
  thisVal: CValue<JSValue>,
  argc: Int,
  argv: CArrayPointer<JSValue>,
): CValue<JSValue> {
  val quickJs = JS_GetRuntimeOpaque(JS_GetRuntime(context))!!.asStableRef<QuickJs>().get()
  return try {
    quickJs.bridgeRegisterRuntimeJsHandler(argc, argv)
  } catch (t: Throwable) {
    t.printStackTrace()
    throw t
  }
}
