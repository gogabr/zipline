import org.jetbrains.kotlin.gradle.dsl.JsModuleKind
import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import java.util.Base64

// The "old" side of the cross-version test pair: this module declares
// app.cash.zipline.bridge.test.WirePayload with its pre-rename field names, and its test loads the
// guest bundle built by :zipline-bridge-skew-new (and vice versa there). See
// zipline-bridge-kotlin-plugin-tests/build.gradle.kts, which this file follows.
plugins {
  kotlin("multiplatform")
}

// Resolvable classpath for running the ZiplineCompiler CLI (app.cash.zipline.cli.Main).
val cliRuntimeClasspath: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}
dependencies {
  add("cliRuntimeClasspath", project(":zipline-cli"))
}

/** The other tree's generated guest holder; see the cross wiring below. */
val crossGuestDir = project(":zipline-bridge-skew-new").layout.buildDirectory.dir("generated/guest")

val bridgePluginId = "app.cash.zipline.bridge.kotlin"
val bridgeCOutputDir = layout.buildDirectory.dir("generated/bridge/c")
val bridgeNativeOutputDir = layout.buildDirectory.dir("generated/bridge/native")
val generatedGuestDir = layout.buildDirectory.dir("generated/guest")
val bridgeObjDir = layout.buildDirectory.dir("generated/bridge/obj")
val isMac = System.getProperty("os.name").contains("mac", ignoreCase = true)
// Per module: both trees are loaded by the same test JVM classpath otherwise the second
// System.load would re-register the first tree's converters.
val bridgeSoFile = layout.buildDirectory.file(
  if (isMac) "generated/bridge/libskewold.dylib" else "generated/bridge/libskewold.so",
)
/** Resolve a JDK home that ships JNI headers (the Gradle daemon may run on a JRE). */
fun resolveJdkHome(): String {
  if (isMac) {
    val process = ProcessBuilder("/usr/libexec/java_home").start()
    val home = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0 && home.isNotEmpty()) return home
  }
  return System.getProperty("java.home")
}

kotlin {
  jvm()

  js {
    binaries.executable()
    compilerOptions {
      moduleKind.set(JsModuleKind.MODULE_UMD)
    }
  }

  macosArm64 {
    binaries.all {
      linkerOpts += listOf(
        "-L${rootDir}/zipline/build/hermes-jni/macos-arm64",
        "-lhermesvm",
        "-lsqlite3",
        "-rpath", "${rootDir}/zipline/build/hermes-jni/macos-arm64",
      )
    }
  }

  applyDefaultHierarchyTemplate()

  sourceSets {
    all {
      languageSettings.optIn("kotlin.js.ExperimentalJsExport")
    }

    val commonMain by getting {
      dependencies {
        implementation(projects.zipline)
        implementation(projects.ziplineBridgeAnnotations)
      }
    }
    val jvmMain by getting {
      dependencies {
        implementation(projects.zipline)
      }
    }
    val nativeMain by getting {
      dependencies {
        implementation(projects.zipline)
      }
      kotlin.srcDir(bridgeNativeOutputDir)
    }
    val jsMain by getting {
      dependencies {
        implementation(projects.zipline)
        implementation(projects.ziplineBridgeAnnotations)
      }
    }
    val commonTest by getting {
      dependencies {
        implementation(kotlin("test"))
      }
      kotlin.srcDir(generatedGuestDir)
      // The other tree's guest bytecode, so this tree's host can load it. Generated guest *source*
      // only: the other tree never contributes classes, so the shared FQN stays unambiguous.
      kotlin.srcDir(crossGuestDir)
    }
    val jvmTest by getting {
      dependencies {
        implementation(kotlin("test-junit"))
        // decodeGuestModule parses the embedded ZiplineFile container, which is host-only: the
        // loader has no JS target, so this must not sit in commonTest (jsTest cannot resolve it).
        implementation(projects.ziplineLoader)
      }
    }
    val nativeTest by getting {
      dependencies {
        implementation(projects.ziplineLoader)
      }
    }
  }

  // The bridge compiler plugin runs on every compilation so the guest (JS) gets the injected
  // __bridgeRegister dispatch, and the hosts generate their bridge sources.
  targets.all {
    compilations.all {
      val pluginConfigurationName = PLUGIN_CLASSPATH_CONFIGURATION_NAME +
        target.disambiguationClassifier.orEmpty().capitalize() +
        compilationName.capitalize()
      project.dependencies.add(pluginConfigurationName, projects.ziplineBridgeKotlinPlugin)
    }
  }
}

tasks {
  withType<KotlinJvmCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
      "-P", "plugin:$bridgePluginId:cOutputDir=${bridgeCOutputDir.get().asFile}",
      "-P", "plugin:$bridgePluginId:nativeOutputDir=${bridgeNativeOutputDir.get().asFile}",
    )
  }
  withType<KotlinNativeCompile>().configureEach {
    compilerOptions.freeCompilerArgs.addAll(
      "-P", "plugin:$bridgePluginId:nativeOutputDir=${bridgeNativeOutputDir.get().asFile}",
    )
  }
  named("compileKotlinJvm") {
    outputs.dir(bridgeCOutputDir)
    outputs.dir(bridgeNativeOutputDir)
  }

  // Compile the JS guest with ZiplineCompiler into the engine's bytecode (.zipline files).
  //
  // WARNING: Kotlin/JS incremental compilation can emit a guest bundle carrying none of the bridge
  // plugin's IR injections (no __bridgeRegister, no warm-up hook, no @HostName aliases) when a
  // single jsMain file is rebuilt: the guest sources are current while the emitted JS has no
  // registration, and the tests then fail for reasons unrelated to the bridge (a decode that
  // returns null, a missing prototype, an alias that does not forward). Reproduce with a one-line
  // jsMain edit plus `./gradlew :zipline-bridge-skew-old:compileDevelopmentExecutableKotlinJs`; a
  // `--rerun-tasks` compile (or disabling `kotlin.incremental.js`) emits the correct bundle.
  // Check the emitted bundle before believing a red run.
  val compileGuestJs = register<JavaExec>("compileGuestJs") {
    dependsOn("compileDevelopmentExecutableKotlinJs")
    classpath(cliRuntimeClasspath)
    mainClass.set("app.cash.zipline.cli.Main")
    inputs.dir(layout.buildDirectory.dir("compileSync/js/main/developmentExecutable/kotlin"))
    outputs.dir(layout.buildDirectory.dir("zipline-guest"))
    doFirst {
      layout.buildDirectory.dir("zipline-guest").get().asFile.mkdirs()
    }
    args(
      "compile",
      "--input", layout.buildDirectory.dir("compileSync/js/main/developmentExecutable/kotlin").get().asFile,
      "--output", layout.buildDirectory.dir("zipline-guest").get().asFile,
    )
  }

  // Embed the guest bytecode as base64 constants, shared by jvmTest and nativeTest.
  val generateGuestSource = register("generateGuestSource") {
    dependsOn(compileGuestJs)
    // The bytecode is this task's real input. Without it Gradle sees an unchanged output dir and
    // keeps a stale holder after any guest change, so the tests silently run the old bundle.
    inputs.dir(layout.buildDirectory.dir("zipline-guest"))
    outputs.dir(generatedGuestDir)
    doLast {
      val ziplineDir = layout.buildDirectory.dir("zipline-guest").get().asFile
      val modules = ziplineDir.listFiles { f -> f.extension == "zipline" }!!
        .sortedBy { it.name }
      // The app module is the entry point; the rest are its dependencies (the stdlib and the
      // other bridged modules), which loadGuest loads before it. Kotlin/JS names the output file
      // <root project>-<project>, and ZiplineCompiler keeps that name as the module id.
      val appModuleName = "${rootProject.name}-${project.name}"
      val appModule = modules.firstOrNull { it.nameWithoutExtension == appModuleName }
        ?: error("no guest module named $appModuleName in $ziplineDir")
      val sb = StringBuilder()
      sb.appendLine("// GENERATED FILE. DO NOT MODIFY MANUALLY.")
      sb.appendLine("package app.cash.zipline.bridge.test")
      sb.appendLine()
      sb.appendLine("object GeneratedSkewOldGuest {")
      sb.appendLine("  /** The guest app module: the entry point, as assigned by ZiplineCompiler. */")
      sb.appendLine("  val mainModuleId: String = \"./${appModule.nameWithoutExtension}.js\"")
      sb.appendLine()
      sb.appendLine("  /** The app module and its dependencies, in load order. */")
      sb.appendLine("  val modules: List<Pair<String, String>> = listOf(")
      for (f in modules) {
        val moduleId = "./${f.nameWithoutExtension}.js"
        val b64 = Base64.getEncoder().encodeToString(f.readBytes())
        sb.appendLine("    \"$moduleId\" to \"$b64\",")
      }
      sb.appendLine("  )")
      sb.appendLine("}")
      val outFile = generatedGuestDir.get().asFile.resolve("GeneratedSkewOldGuest.kt")
      outFile.parentFile.mkdirs()
      outFile.writeText(sb.toString())
    }
  }

  // Compile the generated C bridge sources into a host .dylib/.so that links against the
  // already-loaded libhermesvm (undefined symbols resolve at dlopen time).
  val compileBridgeC = register<Exec>("compileBridgeC") {
    dependsOn("compileKotlinJvm")
    inputs.dir(bridgeCOutputDir)
    inputs.file(rootProject.projectDir.resolve("zipline/native/bridge_dispatch.h"))
    outputs.dir(bridgeObjDir)
    doFirst {
      val javaHome = resolveJdkHome()
      val jniInclude = if (isMac) "$javaHome/include/darwin" else "$javaHome/include/linux"
      val repoNative = rootProject.projectDir.resolve("zipline/native")
      val cFiles = bridgeCOutputDir.get().asFile.listFiles { f -> f.extension == "cpp" }!!
        .sortedBy { it.name }
        .map { it.absolutePath }
      val objDir = bridgeObjDir.get().asFile
      objDir.mkdirs()
      workingDir(objDir)
      commandLine(
        listOf(
          "clang++", "-c", "-fPIC", "-std=c++17",
          "-I$repoNative",
          "-I$repoNative/hermes/API",
          "-I$repoNative/hermes/API/jsi",
          "-I$repoNative/hermes/public",
          "-I$javaHome/include",
          "-I$jniInclude",
        ) + cFiles,
      )
    }
  }

  val linkBridgeSo = register<Exec>("linkBridgeSo") {
    dependsOn(compileBridgeC)
    inputs.dir(bridgeObjDir)
    outputs.file(bridgeSoFile)
    doFirst {
      val objs = bridgeObjDir.get().asFile.listFiles { f -> f.extension == "o" }!!
        .sortedBy { it.name }
        .map { it.absolutePath }
      val cmd = mutableListOf("clang++", "-shared")
      if (isMac) cmd += "-Wl,-undefined,dynamic_lookup"
      cmd += objs
      cmd += "-o"
      cmd += bridgeSoFile.get().asFile.absolutePath
      commandLine(cmd)
    }
  }

  named<Test>("jvmTest") {
    dependsOn(linkBridgeSo)
    systemProperty("bridgeTestSoPath", bridgeSoFile.get().asFile.absolutePath)
  }

  named("macosArm64Test") {
    dependsOn(":zipline:buildHermesHostMacosArm64")
  }

  // commonTest sources include the generated guest bytecode of both trees; wire the dependencies
  // explicitly (srcDir with a plain directory provider does not infer them). Every target's test
  // compilation compiles commonTest, so all of them need both.
  kotlin.targets.configureEach {
    compilations.matching { it.name == "test" }.configureEach {
      compileTaskProvider.configure {
        dependsOn("generateGuestSource")
        dependsOn(":zipline-bridge-skew-new:generateGuestSource")
      }
    }
  }
}

// Native compilations compile the bridge sources generated by the JVM compilation.
kotlin.targets.withType<KotlinNativeTarget>().configureEach {
  compilations.getByName("main").compileTaskProvider.configure {
    dependsOn("compileKotlinJvm")
  }
  compilations.getByName("test").compileTaskProvider.configure {
    dependsOn("compileKotlinJvm")
    dependsOn("generateGuestSource")
    dependsOn(":zipline-bridge-skew-new:generateGuestSource")
  }
}
