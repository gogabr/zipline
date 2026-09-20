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

import app.cash.zipline.internal.bridge.CallChannel

/**
 * An ECMAScript (JavaScript) interpreter backed by the JsEngine native engine.
 *
 * This class is NOT thread safe. If multiple threads access an instance concurrently it must be
 * synchronized externally.
 */
@EngineApi
expect class JsEngine : AutoCloseable {
  companion object {
    /**
     * Create a new interpreter instance. Calls to this method **must** be matched with
     * calls to [close] on the returned instance to avoid leaking native memory.
     */
    fun create(): JsEngine

    val version: String
  }

  /**
   * The interrupt handler is polled frequently during code execution.
   *
   * Using any interrupt handler may have a significant performance cost. Use a null handler for
   * best performance.
   */
  var interruptHandler: InterruptHandler?

  /** Memory usage statistics for the JavaScript engine. */
  val memoryUsage: MemoryUsage

  /** Default is -1. Use -1 for no limit. */
  var memoryLimit: Long

  /** Default is 256 KiB. Use -1 to disable automatic GC. */
  var gcThreshold: Long

  /** Default is 512 KiB. Use 0 to disable the maximum stack size check. */
  var maxStackSize: Long

  /**
   * Evaluate [script] and return the result as a scalar (Int, Double,
   * String, or null for undefined/null/other kinds). Richer values cross
   * the JS/host boundary via call channels (and [getGlobalProperty] for
   * strings).
   *
   * @throws JsException if there is an error evaluating the script.
   */
  fun evaluate(script: String, fileName: String = "?"): Any?

  /**
   * Evaluate [script] like [evaluate], but dispatch any object result through the
   * registered @WithJS2HostBridge converters (the `bridge_dispatch` property). On JVM
   * this is identical to [evaluate] (the JNI dispatcher already routes bridge objects);
   * on Kotlin/Native [evaluate] returns null for plain objects, so this runs the result
   * through `bridgeForAny` instead.
   *
   * @throws JsException if there is an error evaluating the script.
   */
  fun evaluateForBridge(script: String, fileName: String): Any?

  /**
   * Compile [sourceCode] and return the bytecode. [fileName] will be used in error
   * reporting. [sourceMap] is optional to enable Kotlin stacktraces.
   *
   * @throws JsException if the sourceCode could not be compiled.
   */
  fun compile(sourceCode: String, fileName: String, sourceMap: String? = null): ByteArray

  /**
   * Load and execute [bytecode] and return the result as a scalar (Int,
   * Double, String, or null for undefined/null/other kinds). [fileName]
   * will be used in error reporting.
   *
   * @throws JsException if there is an error loading or executing the code.
   */
  fun execute(bytecode: ByteArray, fileName: String = "?"): Any?

  fun getGlobalProperty(name: String): String?
  fun setGlobalProperty(name: String, value: String)
  fun deleteGlobalProperty(name: String)
  fun callGlobalMethod(objectName: String, methodName: String)
  fun callGlobalFunctionWithStringArg(functionName: String, arg: String): String?
  fun callRequireMethod(moduleId: String, methodName: String)
  fun installModuleLoader()

  /**
   * Whether `globalThis[name]` is a function.
   *
   * The host probes with this before it takes a path that needs the guest to accept a call, so a
   * guest that cannot receive it falls back to the serialized path instead of failing.
   */
  fun hasGlobalFunction(name: String): Boolean

  /**
   * Call `globalThis[name]` with [args], converting each argument host -> JS and the result
   * JS -> host. A value with no counterpart is an error, never a silent null.
   *
   * @throws JsException if the global is missing or not a function, or if the call throws.
   */
  fun callGuestFunction(name: String, args: List<Any?> = emptyList()): Any?

  /**
   * Calls [functionName] on the exports of module [moduleId] when that module exports such a
   * function, and does nothing when it does not. This is how the host triggers a module's
   * bridge warm-up: Kotlin/JS cannot run a module's file-level initializers eagerly, so the
   * generated hook is the only thing that can register a module's host->JS prototypes before the
   * application runs.
   */
  internal fun warmUpModule(moduleId: String, functionName: String)

  internal fun initOutboundChannel(outboundChannel: CallChannel)

  internal fun getInboundChannel(): CallChannel

  /**
   * Manually invoke cycle removal. This is intended for testing only and is never necessary to
   * call in regular execution.
   */
  fun gc()

  /**
   * Callback sink for the RDMA changes channel. Set by the host before the guest
   * starts rendering. When non-null, JS guest calls to `app_cash_redwood_rdmaSendChanges`
   * are forwarded to this sink instead of using JSON serialization.
   *
   * On Android, this is handled via JNI (Context.cpp). On iOS (Kotlin/Native),
   * the sink is called directly from Kotlin via [staticCFunction] callbacks.
   */
  @EngineApi
  var rdmaChangeSink: RdmaChangeSink?

  /**
   * Initialize the RDMA changes channel. Registers a JS-Callable function on globalThis
   * so the JS guest can send Changes directly via JNI, bypassing JSON serialization.
   */
  @EngineApi
  fun initRdmaChangesChannel()

  /**
   * Starts a CDP debug session on this engine. Returns false when the engine was built without
   * debugger support. [listener] receives outbound CDP messages from arbitrary threads.
   */
  internal fun cdpAttach(listener: CdpListener): Boolean

  /** Forwards a CDP command (UTF-8 JSON) to the debug session. Safe to call from any thread. */
  internal fun cdpHandleCommand(json: String)

  /** Runs queued debugger runtime tasks. Must be called on the JS thread. */
  internal fun cdpDrainTasks()

  /** Re-creates the CDP agent (preserving breakpoint state) for the next debugger client. */
  internal fun cdpResetAgent()

  override fun close()

  internal fun bridgeInitAll()
}
