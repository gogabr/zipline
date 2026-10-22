/*
 * Copyright (C) 2023 Cash App
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
package app.cash.zipline.gradle

import java.io.File
import java.util.EnumSet
import java.util.Timer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.concurrent.schedule
import org.eclipse.jetty.ee10.servlet.ResourceServlet
import org.eclipse.jetty.ee10.servlet.ServletContextHandler
import org.eclipse.jetty.ee10.servlet.ServletHolder
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServlet
import org.eclipse.jetty.ee10.websocket.server.JettyWebSocketServletFactory
import org.eclipse.jetty.ee10.websocket.server.config.JettyWebSocketServletContainerInitializer
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.websocket.api.Callback
import org.eclipse.jetty.websocket.api.Session
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketOpen
import org.eclipse.jetty.websocket.api.annotations.WebSocket
import org.gradle.api.file.Directory
import org.gradle.deployment.internal.Deployment
import org.gradle.deployment.internal.DeploymentHandle

/**
 * Serves .zipline and manifest files from a directory to a nearby ZiplineLoader. That loader may
 * subscribe to change notifications with a web socket, which will cause this loader to send a
 * 'reload' method whenever the manifest should be checked for an update.
 *
 * For CDP debugging it additionally serves source files: the repo root (so DevTools can open
 * original Kotlin sources referenced from source maps) and sibling checkouts under /__kt_root__/.
 */
internal open class ZiplineDevelopmentServer internal constructor(
  private val inputDirectory: File,
  private val sourceRootDirectory: File,
  private val siblingRootDirectory: File,
  private val port: Int,
) : DeploymentHandle {
  @Inject constructor(
    inputDirectory: Directory,
    sourceRootDirectory: Directory,
    siblingRootDirectory: Directory,
    port: Int,
  ) : this(
    inputDirectory.asFile,
    sourceRootDirectory.asFile,
    siblingRootDirectory.asFile,
    port,
  )

  private val webSockets = CopyOnWriteArrayList<ZiplineWebSocket>()
  private var timer: Timer? = null
  private var server: Server? = null

  override fun isRunning() = server != null

  override fun start(deployment: Deployment) {
    // Let on-device fetches of sources/source maps (used by CDP debugging) go
    // through the adb reverse tunnel instead of the flaky emulator NAT path
    // (10.0.2.2). The device can then reach this server via localhost:<port>.
    tryAdbReverse(port)

    val context = ServletContextHandler(ServletContextHandler.SESSIONS)
      .apply {
        JettyWebSocketServletContainerInitializer.configure(this, null)

        // Chrome DevTools fetches .js sources and .js.map source maps from this
        // server cross-origin (the frontend runs on chrome-devtools-frontend
        // .appspot.com), so everything must be served with CORS headers, incl.
        // Private Network Access preflights (public site -> loopback fetch).
        addFilter(
          org.eclipse.jetty.ee10.servlet.FilterHolder(
            object : jakarta.servlet.Filter {
              override fun doFilter(
                request: jakarta.servlet.ServletRequest,
                response: jakarta.servlet.ServletResponse,
                chain: jakarta.servlet.FilterChain,
              ) {
                val http = response as jakarta.servlet.http.HttpServletResponse
                http.setHeader("Access-Control-Allow-Origin", "*")
                http.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS")
                http.setHeader("Access-Control-Allow-Headers", "*")
                http.setHeader("Access-Control-Allow-Private-Network", "true")
                if ((request as jakarta.servlet.http.HttpServletRequest).method == "OPTIONS") {
                  http.status = 200
                  return
                }
                chain.doFilter(request, response)
              }
            },
          ),
          "/*",
          EnumSet.of(jakarta.servlet.DispatcherType.REQUEST),
        )

        // Offer a web socket for reload events.
        addServlet(
          ServletHolder(
            "ws",
            object : JettyWebSocketServlet() {
            public override fun configure(factory: JettyWebSocketServletFactory) {
              factory.addMapping("/ws") { _, _ ->
                ZiplineWebSocket().also { webSockets.add(it) }
              }
            }
          },
          ),
          "/ws",
        )

        // Serve .zipline bytecode and manifest JSON files at the file system root,
        // falling back to the repo root and sibling checkouts for Kotlin sources.
        addServlet(
          ServletHolder("default", DevSourceServlet()).apply {
            // Note that 'no-cache' is different from 'no-store'. It permits conditional requests.
            setInitParameter("cacheControl", "no-cache")
            setInitParameter("etags", "true")
          },
          "/*",
        )
      }

    // Keep the connection open by sending a message periodically.
    timer = Timer("WebsocketHeartbeat", true).apply {
      schedule(0, 10000) {
        sendMessageToAllWebSockets(HEARTBEAT_MESSAGE)
      }
    }

    server = Server(port).apply {
      handler = context
      start()
    }
  }

  @Suppress("unused") // Invoked reflectively by ZiplineServeTask.
  fun sendReloadToAllWebSockets() {
    sendMessageToAllWebSockets(RELOAD_MESSAGE)
  }

  private fun sendMessageToAllWebSockets(message: String) {
    for (webSocket in webSockets) {
      val session = webSocket.awaitSession() ?: continue
      session.sendText(message, Callback.NOOP)
    }
  }

  override fun stop() {
    try {
      timer?.cancel()
      server?.stop()
    } finally {
      timer = null
      server = null
    }
  }

  @WebSocket
  internal inner class ZiplineWebSocket {
    private val opened = CountDownLatch(1)

    @Volatile
    var session: Session? = null

    /**
     * Jetty writes the 101 before [onWebSocketOpen], so a reload can arrive
     * while [session] is still null. Wait for that callback.
     */
    fun awaitSession(): Session? {
      if (session == null) {
        opened.await(1, TimeUnit.SECONDS)
      }
      return session
    }

    @OnWebSocketClose
    fun onWebSocketClose(statusCode: Int, reason: String?) {
      session = null
      // Unblock awaitSession() if the socket closes before onWebSocketOpen.
      // After a normal open the latch is already at 0, so this is a no-op.
      opened.countDown()
      webSockets.remove(this)
    }

    @OnWebSocketOpen
    fun onWebSocketOpen(session: Session?) {
      this.session = session
      opened.countDown()
    }
  }

  /**
   * Serves files from [inputDirectory] first, then from the repo root
   * ([sourceRootDirectory]), and finally from sibling checkouts
   * ([siblingRootDirectory]) under the `__kt_root__/` prefix. All resolutions
   * are canonicalized and confined to their base directory.
   */
  internal inner class DevSourceServlet : jakarta.servlet.http.HttpServlet() {
    override fun doGet(
      req: jakarta.servlet.http.HttpServletRequest,
      resp: jakarta.servlet.http.HttpServletResponse,
    ) {
      val path = (req.pathInfo ?: req.servletPath ?: "").removePrefix("/")
      val file = resolve(path)
      if (file == null || !file.isFile) {
        resp.sendError(404)
        return
      }
      // Weak ETag from size + mtime: clients revalidate with If-None-Match
      // (Cache-Control is no-cache), and unchanged files answer 304.
      val etag = "W/\"${file.length()}-${file.lastModified()}\""
      resp.setHeader("ETag", etag)
      if (req.getHeader("If-None-Match") == etag) {
        resp.status = 304
        return
      }
      resp.contentType = when (file.extension) {
        "js" -> "text/javascript"
        "map", "json" -> "application/json"
        "kt", "kts" -> "text/plain"
        "html" -> "text/html"
        "css" -> "text/css"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "woff", "woff2" -> "font/woff2"
        else -> "application/octet-stream"
      }
      resp.setHeader("Cache-Control", "no-cache")
      file.inputStream().use { it.copyTo(resp.outputStream) }
    }

    private fun resolve(path: String): File? {
      if (path.isEmpty() || path.contains("..")) return null
      if (path.startsWith(SIBLING_PREFIX)) {
        return File(siblingRootDirectory, path.removePrefix(SIBLING_PREFIX)).confinedTo(siblingRootDirectory)
      }
      File(inputDirectory, path).confinedTo(inputDirectory)?.let { if (it.isFile) return it }
      File(sourceRootDirectory, path).confinedTo(sourceRootDirectory)?.let { if (it.isFile) return it }
      return null
    }

    private fun File.confinedTo(base: File): File? {
      val canonicalBase = base.canonicalFile
      val canonical = canonicalFile
      return if (canonical.path == canonicalBase.path ||
        canonical.path.startsWith(canonicalBase.path + File.separator)
      ) {
        canonical
      } else {
        null
      }
    }
  }

  companion object {
    const val HEARTBEAT_MESSAGE = "heartbeat"
    const val RELOAD_MESSAGE = "reload"
    private const val SIBLING_PREFIX = "__kt_root__/"
    private val logger = org.gradle.api.logging.Logging.getLogger(ZiplineDevelopmentServer::class.java)

    /** Best-effort `adb reverse` so devices can reach this server via localhost. */
    private fun tryAdbReverse(port: Int) {
      val adb = findAdb() ?: return
      try {
        val process = ProcessBuilder(adb, "reverse", "tcp:$port", "tcp:$port")
          .redirectErrorStream(true)
          .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        if (exitCode == 0) {
          logger.lifecycle("Zipline dev server: adb reverse tcp:$port tcp:$port set ($output)")
        } else {
          logger.warn("Zipline dev server: adb reverse failed ($output)")
        }
      } catch (e: Exception) {
        logger.warn("Zipline dev server: could not run adb reverse: ${e.message}")
      }
    }

    private fun findAdb(): String? {
      val fromEnv = System.getenv("ANDROID_HOME")?.let { "$it/platform-tools/adb" }
        ?: System.getenv("ANDROID_SDK_ROOT")?.let { "$it/platform-tools/adb" }
      if (fromEnv != null && File(fromEnv).exists()) return fromEnv
      return runCatching {
        ProcessBuilder("which", "adb").start().inputStream.bufferedReader().readText().trim()
          .takeIf { it.isNotEmpty() && File(it).exists() }
      }.getOrNull()
    }
  }
}
