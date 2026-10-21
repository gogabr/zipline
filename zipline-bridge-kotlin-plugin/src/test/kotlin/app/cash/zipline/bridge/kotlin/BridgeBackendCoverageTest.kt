package app.cash.zipline.bridge.kotlin

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.js.K2JSCompiler
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The safety checks run in the platform-independent IR phase, so the Kotlin/JS and
 * Kotlin/Native backends must report them too; [ZiplineBridgeKotlinPluginTest] only drives the
 * JVM compiler. One bad program per backend is enough, because the diagnostic set itself is
 * platform-neutral.
 *
 * Each test compiles two modules: the sink class without the plugin, then a call site against
 * it with the plugin. Compiling the sink separately is what keeps the fixture independent of
 * the backend's code generation: the second module owns no bridge class, so the plugin
 * validates it and returns before generating anything, and the only possible reason to fail is
 * the check under test.
 */
@OptIn(ExperimentalCompilerApi::class)
class BridgeBackendCoverageTest {
  @Test
  fun `Kotlin JS backend reports an unbridgeable Any slot write`() {
    val workDir = createTempDirectory("bridge-js-backend").toFile()
    val sinkKlib = workDir.resolve("sink/sink.klib").apply { parentFile.mkdirs() }

    val sink = runJsCompiler(
      outputKlib = sinkKlib,
      sources = listOf(writeSource(workDir, "Sink.kt", SINK_SOURCE)),
      pluginJar = null,
    )
    assertEquals(ExitCode.OK.code, sink.exitCode, sink.output)

    val callSite = runJsCompiler(
      outputKlib = workDir.resolve("call/call.klib").apply { parentFile.mkdirs() },
      sources = listOf(writeSource(workDir, "CallSite.kt", CALL_SITE_SOURCE)),
      pluginJar = File(property("bridge.pluginJar")),
      extraLibraries = listOf(sinkKlib),
    )
    assertEquals(ExitCode.COMPILATION_ERROR.code, callSite.exitCode, callSite.output)
    assertTrue(callSite.output.contains(ANY_SLOT_DIAGNOSTIC), callSite.output)
  }

  @Test
  fun `Kotlin Native backend reports an unbridgeable Any slot write`() {
    val compiler = File(property("bridge.konanCompiler"))
    assumeTrue("kotlinc-native not found; skipping the Kotlin/Native backend check", compiler.canExecute())

    val workDir = createTempDirectory("bridge-native-backend").toFile()
    val sinkKlib = workDir.resolve("sink.klib")

    val sink = runNativeCompiler(
      compiler = compiler,
      workDir = workDir,
      outputKlib = sinkKlib,
      source = writeSource(workDir, "Sink.kt", SINK_SOURCE),
      pluginJar = null,
    )
    assertEquals(0, sink.exitCode, sink.output)

    val callSite = runNativeCompiler(
      compiler = compiler,
      workDir = workDir,
      outputKlib = workDir.resolve("call.klib"),
      source = writeSource(workDir, "CallSite.kt", CALL_SITE_SOURCE),
      pluginJar = File(property("bridge.pluginJar")),
      extraLibraries = listOf(sinkKlib),
    )
    assertTrue(callSite.exitCode != 0, callSite.output)
    assertTrue(callSite.output.contains(ANY_SLOT_DIAGNOSTIC), callSite.output)
  }

  private fun runJsCompiler(
    outputKlib: File,
    sources: List<File>,
    pluginJar: File?,
    extraLibraries: List<File> = emptyList(),
  ): Compilation {
    val libraries = buildList {
      add(File(property("bridge.stdlibJsKlib")))
      add(File(property("bridge.annotationsJsKlib")))
      addAll(extraLibraries)
    }.joinToString(File.pathSeparator) { it.absolutePath }

    val arguments = buildList {
      pluginJar?.let { add("-Xplugin=${it.absolutePath}") }
      add("-Xir-produce-klib-file")
      add("-libraries")
      add(libraries)
      add("-ir-output-dir")
      add(outputKlib.parentFile.absolutePath)
      add("-ir-output-name")
      add(outputKlib.nameWithoutExtension)
      addAll(sources.map { it.absolutePath })
    }

    val output = ByteArrayOutputStream()
    val exitCode = K2JSCompiler().exec(PrintStream(output, true), *arguments.toTypedArray())
    return Compilation(exitCode.code, output.toString())
  }

  private fun runNativeCompiler(
    compiler: File,
    workDir: File,
    outputKlib: File,
    source: File,
    pluginJar: File?,
    extraLibraries: List<File> = emptyList(),
  ): Compilation {
    val arguments = buildList {
      pluginJar?.let {
        add("-Xplugin=${it.absolutePath}")
        add("-P")
        add("plugin:${BuildConfig.KOTLIN_PLUGIN_ID}:nativeOutputDir=${workDir.resolve("generated").absolutePath}")
      }
      add("-l")
      add(property("bridge.annotationsNativeKlib"))
      for (library in extraLibraries) {
        add("-l")
        add(library.absolutePath)
      }
      add("-p")
      add("library")
      add("-o")
      add(outputKlib.absolutePath.removeSuffix(".klib"))
      add(source.absolutePath)
    }

    val process = ProcessBuilder(listOf(compiler.absolutePath) + arguments)
      .directory(workDir)
      .redirectErrorStream(true)
      .start()
    val output = process.inputStream.bufferedReader().readText()
    assertTrue(process.waitFor(10, TimeUnit.MINUTES), "kotlinc-native did not finish")
    return Compilation(process.exitValue(), output)
  }

  private fun writeSource(workDir: File, name: String, content: String): File {
    return workDir.resolve(name).apply { writeText(content) }
  }

  private fun property(name: String): String {
    return requireNotNull(System.getProperty(name)) {
      "missing system property '$name'; it is set by the test task in build.gradle.kts"
    }
  }

  private class Compilation(
    val exitCode: Int,
    val output: String,
  )

  private companion object {
    const val ANY_SLOT_DIAGNOSTIC =
      "value of type 'probe.call.Bad' assigned to constructor parameter 'v' of 'Sink' cannot cross the bridge"

    val SINK_SOURCE = """
      package probe

      import app.cash.zipline.bridge.support.WithJS2HostBridge

      @WithJS2HostBridge
      class Sink(val v: Any?)
    """.trimIndent()

    val CALL_SITE_SOURCE = """
      package probe.call

      import probe.Sink

      class Bad

      val badSink = Sink(Bad())
    """.trimIndent()
  }
}
