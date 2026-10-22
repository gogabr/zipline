package app.cash.zipline

import app.cash.zipline.internal.cdp.CdpTestClient
import java.io.EOFException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking

class SourceMapUrlProbeTest {
  private val executor = Executors.newSingleThreadExecutor()
  private val dispatcher = executor.asCoroutineDispatcher()
  private var zipline: Zipline? = null

  @BeforeTest
  fun setUp() {
    Zipline.cdpDebugPort = PORT
  }

  @AfterTest
  fun tearDown() {
    Zipline.cdpDebugPort = null
    zipline?.close()
    zipline = null
  }

  @Test
  fun scriptParsedCarriesSourceMapUrl() = runBlocking<Unit> {
    val zipline = Zipline.create(dispatcher)
    this@SourceMapUrlProbeTest.zipline = zipline

    val sessionId = discoverSessionId()
    CdpTestClient.connect(PORT, sessionId).use { cdp ->
      cdp.send(1, "Runtime.enable")
      cdp.send(2, "Debugger.enable")
      cdp.awaitResponse(2)

      zipline.loadJsModule(TEST_JS, SCRIPT_URL)

      val scriptParsed = cdp.awaitMessage("""scriptParsed"""", """probe.js""")
      println("scriptParsed: $scriptParsed")
      assertTrue(scriptParsed.contains("probe.js"), scriptParsed)
      assertTrue(
        scriptParsed.contains("sourceMapURL"),
        "scriptParsed should announce sourceMapURL: $scriptParsed",
      )

      // Breakpoint on `return x + 1;` (0-based line 2), after x is assigned.
      cdp.send(
        3,
        "Debugger.setBreakpointByUrl",
        """"params":{"url":"$SCRIPT_URL","lineNumber":2}""",
      )
      val setBp = cdp.awaitResponse(3)
      assertTrue(setBp.contains("\"locations\":[{"), "breakpoint should bind: $setBp")

      // Trigger the function; execution pauses at the breakpoint.
      cdp.send(4, "Runtime.evaluate", """"params":{"expression":"probeMe()"}""")
      val paused = cdp.awaitMessage("""paused"""")
      assertTrue(paused.contains("probeMe"), paused)

      // Frame eval requires the in-memory scoping table; constant folding
      // aside, `x` must resolve and `1+2` must compute.
      cdp.send(
        5,
        "Debugger.evaluateOnCallFrame",
        """"params":{"callFrameId":"0","expression":"x"}""",
      )
      val evalX = cdp.awaitResponse(5)
      assertTrue(evalX.contains("\"value\":41"), "frame eval of x: $evalX")

      cdp.send(
        6,
        "Debugger.evaluateOnCallFrame",
        """"params":{"callFrameId":"0","expression":"1+2"}""",
      )
      val evalConst = cdp.awaitResponse(6)
      assertTrue(evalConst.contains("\"value\":3"), "frame eval of 1+2: $evalConst")

      cdp.send(7, "Debugger.resume")
      cdp.awaitResponse(7)
      cdp.awaitResponse(4)
    }
  }

  private fun discoverSessionId(): String {
    val url = URL("http://127.0.0.1:$PORT/json/list")
    val deadline = System.currentTimeMillis() + 10_000
    while (true) {
      try {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 1000
        conn.readTimeout = 1000
        val body = conn.inputStream.bufferedReader().readText()
        val match = Regex("\"id\"\\s*:\\s*\"([^\"]+)\"").find(body)
        if (match != null) return match.groupValues[1]
      } catch (e: Exception) {
        if (System.currentTimeMillis() > deadline) throw e
        Thread.sleep(200)
      }
    }
  }

  companion object {
    // Same port as CdpDebugTest: CdpDebugSupport keeps one process-wide server,
    // so every CDP test in this process must use cdpDebugTestPort().
    private val PORT = cdpDebugTestPort()
    private const val SCRIPT_URL = "http://localhost:8080/probe.js"
    private val TEST_JS = """
      function probeMe() {
        var x = 41;
        return x + 1;
      }
      probeMe();
      //# sourceMappingURL=probe.js.map
      //# sourceURL=http://localhost:8080/probe.js
    """.trimIndent()
  }
}
