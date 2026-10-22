@file:OptIn(ExperimentalAtomicApi::class)

package app.cash.zipline.internal.cdp

import app.cash.zipline.CdpListener
import app.cash.zipline.JsEngine
import app.cash.zipline.internal.log
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okio.IOException

/*
 * Exposes a running [JsEngine] to Chrome DevTools via the Chrome DevTools Protocol (CDP).
 *
 * This is the platform-neutral core: debug sessions, the CDP message dispatch
 * and the DevTools protocol shims the Hermes CDP agent doesn't implement. The
 * actual HTTP/WebSocket server is platform-specific (Ktor CIO on JNI
 * platforms, raw sockets on Kotlin/Native; see initCdpServer). Typical usage
 * on Android:
 *
 * ```
 * Zipline.cdpDebugPort = 9222 // before Zipline starts
 * // on the host machine:
 * adb forward tcp:9222 tcp:9222
 * // then open chrome://inspect, or Chrome with the devtoolsFrontendUrl from /json/list
 * ```
 *
 * On Kotlin/Native (iOS), set the ZIPLINE_CDP_PORT environment variable instead.
 */

/** A connected debugger client (Ktor WebSocket on JNI, raw socket elsewhere). */
internal interface CdpClientConnection {
  /** Sends a text frame; on failure the connection is marked closed ([isOpen] false). */
  fun sendText(text: String)

  fun isOpen(): Boolean

  /** Closes the connection, best effort. */
  fun closeQuietly()
}
internal class CdpDebugServer(
  private val port: Int,
) {
  private val sessionsLock = Mutex()
  private val sessions = mutableListOf<DebugSession>()

  /**
   * Work queue for blocking fetches to the dev server, serviced by a small
   * fixed pool. DevTools fires hundreds of resource requests (every .js,
   * .js.map and .kt source) at once; spawning a thread per request is fine
   * on the JVM but Kotlin/Native workers are too heavyweight for that.
   */
  private val fetchTasks = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(
    kotlinx.coroutines.channels.Channel.UNLIMITED,
  )

  init {
    repeat(FETCH_POOL_SIZE) {
      startDebugThread("ZiplineCdp-fetch-$it") {
        runBlocking {
          for (task in fetchTasks) {
            try {
              task()
            } catch (t: Throwable) {
              log("warn", "Zipline CDP: fetch task failed: ${t.message}", t)
            }
          }
        }
      }
    }
  }

  private fun submitFetchTask(task: suspend () -> Unit) {
    fetchTasks.trySend(task)
  }

  fun attach(jsEngine: JsEngine, scope: CoroutineScope) {
    // Locked: Zipline instances may be created concurrently, and the
    // smallest-free-id check-then-act below must not hand out duplicate ids.
    runBlocking {
      sessionsLock.withLock {
      // Assign the smallest free id so the usual single-engine flow keeps a
      // stable "1" across hot-reloads (DevTools URLs stay valid).
      val id = generateSequence(1L) { it + 1 }
        .map { it.toString() }
        .first { candidate -> sessions.none { it.id == candidate } }
      val session = DebugSession(id, jsEngine, scope)
      if (!jsEngine.cdpAttach(session.listener)) {
        log("warn", "Zipline CDP: engine does not support debugging (HERMES_ENABLE_DEBUGGER off?)", null)
      } else {
        sessions.add(session)
        log("info", "Zipline CDP: debug session ${session.id} attached (port $port)", null)
      }
    }
    }
  }

  fun detach(jsEngine: JsEngine) {
    val session = runBlocking {
      sessionsLock.withLock {
      sessions.firstOrNull { it.jsEngine === jsEngine }?.also { sessions.remove(it) }
    }
    } ?: return
    runBlocking { session.close() }
  }

  /** The session for `/devtools/page/<id>`, or null when unknown. */
  suspend fun session(id: String): DebugSession? = sessionsLock.withLock { sessions.firstOrNull { it.id == id } }

  private suspend fun firstSessionId(): String? = sessionsLock.withLock { sessions.firstOrNull()?.id }

  /** The `/json/version` response body. [host] is the request's Host header. */
  suspend fun versionJson(host: String?): String {
    val h = sanitizeHost(host)
    return """{"Browser":"Zipline/Hermes","Protocol-Version":"1.3",""" +
      """"webSocketDebuggerUrl":"ws://$h/devtools/page/${firstSessionId() ?: ""}"}"""
  }

  private fun sanitizeHost(host: String?): String {
    // The Host header is interpolated into JSON responses; strip anything
    // that isn't a valid host:port character so it can't break the JSON.
    return host
      ?.filter { it.isLetterOrDigit() || it in ".:-[]" }
      ?.takeIf { it.isNotEmpty() }
      ?: "localhost:$port"
  }

  /** The `/json` and `/json/list` response body. [host] is the request's Host header. */
  suspend fun targetsJson(host: String?): String {
    val host = sanitizeHost(host)
    // Point at Chrome's bundled DevTools frontend (chrome://inspect opens this
    // for discovered targets).
    val snapshot = sessionsLock.withLock { sessions.toList() }
    val entries = snapshot.map { session ->
      val wsUrl = "ws://$host/devtools/page/${session.id}"
      val frontendUrl = "devtools://devtools/bundled/inspector.html" +
        "?ws=$host/devtools/page/${session.id}"
      """{"description":"Hermes JS engine",""" +
        """"devtoolsFrontendUrl":"$frontendUrl",""" +
        """"id":"${session.id}","title":"Zipline (Hermes)","type":"node",""" +
        """"vm":"Hermes","webSocketDebuggerUrl":"$wsUrl"}"""
    }
    return entries.joinToString(prefix = "[", postfix = "]")
  }

  internal inner class DebugSession(
    val id: String,
    val jsEngine: JsEngine,
    private val scope: CoroutineScope,
  ) {
    private val clientsLock = Mutex()
    private val clients = mutableListOf<CdpClientConnection>()
    private val drainScheduled = AtomicBoolean(false)

    private val mapsLock = Mutex()

    /** scriptId -> script URL, observed from Debugger.scriptParsed events. */
    private val scriptUrls = mutableMapOf<String, String>()

    /** Open IO streams for the Network.loadNetworkResource + IO.read protocol. */
    private val ioStreams = mutableMapOf<String, IoStream>()
    private var nextStreamId = 1L
    private val json = Json { ignoreUnknownKeys = true }

    /** Ids of Debugger.enable requests whose responses need a debuggerId injected. */
    private val pendingDebuggerEnableIds = mutableSetOf<Long>()

    /**
     * Ids of Runtime.enable requests; the execution context must be (re-)announced
     * after their responses, because frontends discard executionContextCreated
     * events received while the Runtime domain is disabled.
     */
    private val pendingRuntimeEnableIds = mutableSetOf<Long>()

    val listener = object : CdpListener {
      override fun onMessage(json: String): Unit = runBlocking {
        // DevTools persists breakpoints per URL across windows and engine reloads,
        // so it may try to remove ids the current agent doesn't know. The CDP
        // agent answers those with an "Unknown breakpoint ID" error and the
        // frontend then keeps showing the breakpoint forever. Make removal
        // idempotent: swallow that specific error so the frontend forgets it.
        var out = if (json.contains("Unknown breakpoint ID")) {
          val id = this@DebugSession.json.parseToJsonElement(json).asObject()?.get("id").asString()
          buildJsonObject {
            put("id", id?.toLongOrNull() ?: 0L)
            putJsonObject("result") {}
          }.toString().also {
            log("info", "Zipline CDP: forgiving removal of unknown breakpoint (id=$id)", null)
          }
        } else {
          json
        }
        // The CDP agent answers Debugger.enable with an empty result, but V8
        // returns a unique debuggerId and stock Chrome DevTools' breakpoint
        // model depends on it (without it, gutter toggles are no-ops).
        val responseId = RUNTIME_RESPONSE_ID.find(out)?.groupValues?.get(1)?.toLongOrNull()
        val isDebuggerEnableResponse = responseId != null && mapsLock.withLock {
          pendingDebuggerEnableIds.remove(responseId)
        }
        if (isDebuggerEnableResponse && !out.contains("debuggerId")) {
          out = """{"id":$responseId,"result":{"debuggerId":"$DEBUGGER_ID"}}"""
        }
        log("info", "Zipline CDP: => ${out.take(MAX_LOG_CHARS)}", null)
        recordScriptParsed(out)
        val reannounceContext = responseId != null && mapsLock.withLock {
          pendingRuntimeEnableIds.remove(responseId)
        }
        for (client in clientsLock.withLock { clients.toList() }) {
          client.sendText(out)
          if (reannounceContext) {
            client.sendText(EXECUTION_CONTEXT_CREATED)
          }
          // A dead client must not starve the others.
          if (!client.isOpen()) {
            log("warn", "Zipline CDP: dropping client", null)
            clientsLock.withLock { clients.remove(client) }
          }
        }
      }

      override fun onTasksEnqueued() {
        scheduleDrain()
      }
    }

    /**
     * DevTools loads script content exclusively via Debugger.getScriptSource, which the Hermes
     * CDP agent does not implement. Answer it here by fetching the script's URL from the
     * Zipline development server (like React Native's metro inspector proxy does).
     */
    suspend fun onCdpMessage(jsonText: String) {
      log("info", "Zipline CDP: <= ${jsonText.take(MAX_LOG_CHARS)}", null)
      val message = try {
        json.parseToJsonElement(jsonText).asObject()
      } catch (t: Throwable) {
        // A malformed message must not kill the connection.
        log("warn", "Zipline CDP: unparseable message: ${t.message}", null)
        null
      }
      val method = message?.get("method").asString()
      when (method) {
        "Debugger.enable" -> {
          message?.get("id").asString()?.toLongOrNull()?.let { id ->
            mapsLock.withLock { pendingDebuggerEnableIds.add(id) }
          }
        }

        "Runtime.enable" -> {
          message?.get("id").asString()?.toLongOrNull()?.let { id ->
            mapsLock.withLock { pendingRuntimeEnableIds.add(id) }
          }
        }

        "Debugger.getScriptSource" -> {
          val requestId = message?.get("id").asString()
          val scriptId = message?.get("params").asObject()?.get("scriptId").asString()
          val url = scriptId?.let { sid -> mapsLock.withLock { scriptUrls[sid] } }
          if (requestId != null && url != null) {
            serveScriptSource(requestId, url)
            return
          }
        }

        // Current DevTools uses getPossibleBreakpoints for inline (column-level)
        // breakpoints, which the Hermes CDP agent does not implement. Compute the
        // locations from the engine's debug line table on the JS thread.
        "Debugger.getPossibleBreakpoints" -> {
          val requestId = message?.get("id").asString()
          val params = message?.get("params").asObject()
          val start = params?.get("start").asObject()
          val end = params?.get("end").asObject()
          val scriptId = start?.get("scriptId").asString()?.toIntOrNull()
          if (requestId != null && scriptId != null) {
            servePossibleBreakpoints(
              requestId,
              scriptId,
              start?.get("lineNumber").asString()?.toIntOrNull() ?: 0,
              start?.get("columnNumber").asString()?.toIntOrNull() ?: 0,
              end?.get("lineNumber").asString()?.toIntOrNull() ?: -1,
              end?.get("columnNumber").asString()?.toIntOrNull() ?: -1,
            )
            return
          }
        }

        // Modern DevTools loads source maps (and other resources) through the target
        // via Network.loadNetworkResource + IO.read, which the Hermes CDP agent does
        // not implement. Answer it here, again fetching from the Zipline dev server.
        "Network.loadNetworkResource" -> {
          val requestId = message?.get("id").asString()
          val url = message?.get("params").asObject()?.get("url").asString()
          if (requestId != null && url != null) {
            serveNetworkResource(requestId, url)
            return
          }
        }

        "IO.read" -> {
          val requestId = message?.get("id").asString()
          val params = message?.get("params").asObject()
          val handle = params?.get("handle").asString()
          if (requestId != null && handle != null) {
            serveIoRead(
              requestId,
              handle,
              params?.get("offset").asString()?.toIntOrNull(),
              params?.get("size").asString()?.toIntOrNull(),
            )
            return
          }
        }

        "IO.close" -> {
          val requestId = message?.get("id").asString()
          val handle = message?.get("params").asObject()?.get("handle").asString()
          if (requestId != null && handle != null) {
            mapsLock.withLock { ioStreams.remove(handle) }
            sendToClients(
              buildJsonObject {
              put("id", requestId.toLongOrNull() ?: 0L)
              putJsonObject("result") {}
            }.toString(),
            )
            return
          }
        }
      }
      jsEngine.cdpHandleCommand(jsonText)
      // handleCommand may enqueue runtime tasks synchronously; make sure they run even if the
      // onTasksEnqueued notification raced with a drain already in flight.
      scheduleDrain()
    }

    private suspend fun recordScriptParsed(jsonText: String) {
      val params = json.parseToJsonElement(jsonText).asObject()
        ?.takeIf { it["method"].asString() == "Debugger.scriptParsed" }
        ?.get("params").asObject() ?: return
      val scriptId = params["scriptId"].asString() ?: return
      val url = params["url"].asString() ?: return
      if (url.isNotEmpty()) {
        mapsLock.withLock { scriptUrls[scriptId] = url }
      }
    }

    private fun serveScriptSource(requestId: String, url: String) {
      // Fetch off the WebSocket reader thread; reply directly to the clients.
      submitFetchTask {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        val source = fetchScriptSource(url)
        log(
          "info",
          "Zipline CDP: getScriptSource $url -> ${source?.length ?: "failed"} in ${mark.elapsedNow()}",
          null,
        )
        val response = if (source != null) {
          buildJsonObject {
            put("id", requestId.toLongOrNull() ?: 0L)
            putJsonObject("result") { put("scriptSource", source) }
          }.toString()
        } else {
          buildJsonObject {
            put("id", requestId.toLongOrNull() ?: 0L)
            putJsonObject("error") {
              put("code", -32000)
              put("message", "Could not fetch $url from the Zipline dev server")
            }
          }.toString()
        }
        sendToClients(response)
      }
    }

    private fun servePossibleBreakpoints(
      requestId: String,
      scriptId: Int,
      startLine: Int,
      startCol: Int,
      endLine: Int,
      endCol: Int,
    ) {
      submitFetchTask {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        val locations = possibleBreakpoints(scriptId, startLine, startCol, endLine, endCol)
        log(
          "info",
          "Zipline CDP: getPossibleBreakpoints scriptId=$scriptId -> ${locations.size} locations in ${mark.elapsedNow()}",
          null,
        )
        sendToClients(
          buildJsonObject {
          put("id", requestId.toLongOrNull() ?: 0L)
          putJsonObject("result") {
            put("locations", JsonArray(locations))
          }
        }.toString(),
        )
      }
    }

    /**
     * Possible breakpoint positions for inline (column-level) breakpoints, computed from the
     * script's served source map (each generated segment is a statement position candidate).
     * The frontend re-resolves candidates via setBreakpointByUrl anyway, so map segments are
     * a good approximation of the engine's debug line table.
     */
    private suspend fun possibleBreakpoints(
      scriptId: Int,
      startLine: Int,
      startCol: Int,
      endLine: Int,
      endCol: Int,
    ): List<JsonElement> {
      val url = mapsLock.withLock { scriptUrls[scriptId.toString()] } ?: return emptyList()
      val positions = sourceMapPositions(url)
      val inRange = positions.filter { (line, col) ->
        (line > startLine || (line == startLine && col >= startCol)) &&
          (endLine < 0 || line < endLine || (line == endLine && col < endCol))
      }
      return inRange.map { (line, col) ->
        buildJsonObject {
          put("scriptId", scriptId.toString())
          put("lineNumber", line)
          put("columnNumber", col)
        }
      }
    }

    private val sourceMapCache = mutableMapOf<String, List<Pair<Int, Int>>>()

    private suspend fun sourceMapPositions(scriptUrl: String): List<Pair<Int, Int>> {
      mapsLock.withLock { sourceMapCache[scriptUrl] }?.let { return it }
      val positions = computeSourceMapPositions(scriptUrl)
      mapsLock.withLock { sourceMapCache[scriptUrl] = positions }
      return positions
    }

    private suspend fun computeSourceMapPositions(scriptUrl: String): List<Pair<Int, Int>> {
      val mapText = fetchScriptSource("$scriptUrl.map") ?: return emptyList()
      val mapJson = try {
        json.parseToJsonElement(mapText).asObject()
      } catch (_: Exception) {
        null
      } ?: return emptyList()
      val mappings = mapJson["mappings"].asString() ?: return emptyList()
      val result = ArrayList<Pair<Int, Int>>()
      var line = 0
      for (lineMappings in mappings.split(';')) {
        if (lineMappings.isNotEmpty()) {
          var column = 0
          for (segment in lineMappings.split(',')) {
            if (segment.isEmpty()) continue
            val fields = decodeVlq(segment)
            if (fields.isNotEmpty()) {
              column += fields[0]
              result.add(line to column)
            }
          }
        }
        line += 1
      }
      return result
    }

    /** Decodes one base64-VLQ segment into signed delta values. */
    private fun decodeVlq(segment: String): IntArray {
      val values = IntArray(5)
      var count = 0
      var shift = 0
      var value = 0
      for (c in segment) {
        val digit = if (c.code < VLQ_DIGITS.size) VLQ_DIGITS[c.code] else -1
        if (digit < 0) return values.copyOf(count)
        value = value or ((digit and 0x1F) shl shift)
        if (digit and 0x20 == 0) {
          values[count++] = if (value and 1 == 1) -(value ushr 1) else value ushr 1
          shift = 0
          value = 0
          if (count == values.size) return values
        } else {
          shift += 5
        }
      }
      return values.copyOf(count)
    }

    private fun serveNetworkResource(requestId: String, url: String) {
      submitFetchTask {
        val mark = kotlin.time.TimeSource.Monotonic.markNow()
        val content = fetchScriptSource(url)
        log(
          "info",
          "Zipline CDP: loadNetworkResource $url -> ${content?.length ?: "failed"} in ${mark.elapsedNow()}",
          null,
        )
        // Hoisted out of the non-suspending buildJsonObject lambda.
        val handle = content?.let {
          mapsLock.withLock {
            val h = "zipline-stream-${nextStreamId++}"
            ioStreams[h] = IoStream(content)
            h
          }
        }
        val response = buildJsonObject {
          put("id", requestId.toLongOrNull() ?: 0L)
          putJsonObject("result") {
            putJsonObject("resource") {
              put("url", url)
              if (handle != null) {
                put("success", true)
                put("httpStatusCode", 200)
                put("stream", handle)
              } else {
                put("success", false)
                put("httpStatusCode", 0)
                put("netError", -2)
                put("netErrorName", "net::ERR_FAILED")
              }
            }
          }
        }.toString()
        sendToClients(response)
      }
    }

    /**
     * Answers CDP `IO.read`: serves chunks of a stream previously opened by
     * `Network.loadNetworkResource` (stored in [ioStreams]). Explicit offsets
     * are random-access and don't advance the stream; sequential reads advance
     * it. `eof` is reported only on an empty read after full delivery —
     * DevTools discards data when eof arrives early.
     */
    private suspend fun serveIoRead(requestId: String, handle: String, offset: Int?, size: Int?) {
      val response = mapsLock.withLock {
        val stream = ioStreams[handle]
        buildJsonObject {
          put("id", requestId.toLongOrNull() ?: 0L)
          if (stream == null) {
            putJsonObject("error") {
              put("code", -32000)
              put("message", "Invalid stream handle")
            }
          } else {
            val from = (offset ?: stream.position).coerceIn(0, stream.content.length)
            val limit = size?.coerceAtLeast(0) ?: Int.MAX_VALUE
            val available = (stream.content.length - from).coerceAtLeast(0)
            val end = from + minOf(available, limit)
            val chunk = stream.content.substring(from, end)
            // Random-access reads (explicit offset) don't advance the stream.
            if (offset == null) stream.position = end
            putJsonObject("result") {
              put("data", chunk)
              // DevTools discards data when eof is true, so only report eof on an
              // empty read after all content has been delivered.
              put("eof", chunk.isEmpty())
            }
          }
        }
      }
      sendToClients(response.toString())
    }

    private suspend fun sendToClients(json: String) {
      for (client in clientsLock.withLock { clients.toList() }) {
        client.sendText(json)
        if (!client.isOpen()) {
          clientsLock.withLock { clients.remove(client) }
        }
      }
    }

    /** Bounds concurrent fetches to the dev server (bursts exhaust the HTTP keep-alive pool). */
    private val fetchPermits = Semaphore(4)

    private suspend fun fetchScriptSource(url: String): String? {
      // The URL points at the dev server on the host machine. Best path is the
      // loopback tunnel (adb reverse on Android; the simulator shares the host
      // network on iOS/macOS): the device's 127.0.0.1 then reaches the host.
      // Note we must use the IP literal — some emulators can't even resolve
      // "localhost" (UnknownHostException). Platforms may add extra candidates
      // (e.g. the Android emulator NAT alias 10.0.2.2). DevTools does not retry
      // failed loads, so each candidate gets two attempts.
      val candidates = buildList {
        add(url.replace("://localhost:", "://127.0.0.1:"))
        add(url)
        addAll(extraFetchCandidates(url))
      }.distinct()
      fetchPermits.acquire()
      try {
        for (candidate in candidates) {
          repeat(2) {
            try {
              val body = httpGet(candidate, connectTimeoutMs = 5000, readTimeoutMs = 15000)
              if (body != null) return body
            } catch (t: IOException) {
              // Try again, then fall through to the next candidate.
              log("warn", "Zipline CDP: fetch attempt failed for $candidate: ${t::class.simpleName}: ${t.message}", null)
            }
          }
        }
      } finally {
        fetchPermits.release()
      }
      log("warn", "Zipline CDP: unable to fetch script source: $url", null)
      return null
    }

    private fun scheduleDrain() {
      if (drainScheduled.compareAndSet(false, true)) {
        scope.launch {
          drainScheduled.store(false)
          try {
            jsEngine.cdpDrainTasks()
          } catch (t: Throwable) {
            log("warn", "Zipline CDP: task drain failed: ${t.message}", t)
          }
        }
      }
    }

    suspend fun addClient(conn: CdpClientConnection) {
      // Single-debugger semantics: a new DevTools client replaces any previous
      // one. Sharing one agent across clients cross-talks responses and events
      // (each frontend sees the other's traffic), which breaks client-side
      // state like the breakpoint model.
      val hadClients = clientsLock.withLock {
        val had = clients.isNotEmpty()
        if (had) {
          for (old in clients) {
            old.closeQuietly()
          }
          clients.clear()
        }
        clients.add(conn)
        had
      }
      if (hadClients) {
        // Fresh agent so the new client re-receives scriptParsed events when
        // it enables the Debugger domain (breakpoint state is preserved).
        jsEngine.cdpResetAgent()
      }
      // The Hermes CDP agent never reports its execution context, and without
      // one the DevTools console has nowhere to evaluate (input silently
      // vanishes). Announce the default context to the new client. Stock Chrome
      // DevTools discards it (Runtime domain not yet enabled) and gets a second
      // announcement after its Runtime.enable instead.
      sendSafe(conn, EXECUTION_CONTEXT_CREATED)
    }

    suspend fun removeClient(conn: CdpClientConnection) {
      // remove() is false when close() already dropped this client. The list
      // is empty then too, but the engine is shutting down and must not be
      // reset (cdpResetAgent throws once JsEngine.close has set closed).
      val resetAgent = clientsLock.withLock {
        clients.remove(conn) && clients.isEmpty()
      }
      if (resetAgent) {
        // The next client gets a fresh agent so it re-receives scriptParsed
        // events when it enables the Debugger domain (breakpoint state is
        // preserved across the reset).
        jsEngine.cdpResetAgent()
      }
    }

    private suspend fun sendSafe(conn: CdpClientConnection, json: String) {
      conn.sendText(json)
      if (!conn.isOpen()) {
        clientsLock.withLock { clients.remove(conn) }
      }
    }

    suspend fun close() {
      val snapshot = clientsLock.withLock {
        val copy = clients.toList()
        clients.clear()
        copy
      }
      for (client in snapshot) {
        client.closeQuietly()
      }
    }
  }

  companion object {
    /** Cap CDP traffic logging so big payloads (script sources) don't flood logcat. */
    private const val MAX_LOG_CHARS = 400

    /** Threads servicing dev-server fetches; matches the fetchPermits bound. */
    private const val FETCH_POOL_SIZE = 4

    /** Matches the "id" of an agent response, for response post-processing. */
    private val RUNTIME_RESPONSE_ID = Regex(""""id":(\d+)""")

    /**
     * Stable debuggerId reported in Debugger.enable responses, mimicking V8.
     * Stock Chrome DevTools' breakpoint model misbehaves without one.
     */
    private const val DEBUGGER_ID = "zipline-hermes-debugger"

    /**
     * The Hermes CDP agent never reports an execution context; without one the
     * DevTools console has nowhere to evaluate (input silently vanishes).
     */
    private const val EXECUTION_CONTEXT_CREATED =
      """{"method":"Runtime.executionContextCreated","params":{"context":{"id":1,"origin":"","name":"Hermes","auxData":{"isDefault":true}}}}"""

    private val VLQ_DIGITS: IntArray = IntArray(128) { -1 }.also { table ->
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".forEachIndexed { i, c ->
        table[c.code] = i
      }
    }
  }

  private fun JsonElement?.asObject(): JsonObject? = this as? JsonObject

  private fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.contentOrNull
}

private data class IoStream(val content: String, var position: Int = 0)
