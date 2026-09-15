import org.jetbrains.kotlin.gradle.dsl.JsModuleKind
import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeCompile
import java.util.Base64

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

val bridgePluginId = "app.cash.zipline.bridge.kotlin"
val bridgeCOutputDir = layout.buildDirectory.dir("generated/bridge/c")
val bridgeNativeOutputDir = layout.buildDirectory.dir("generated/bridge/native")
val generatedGuestDir = layout.buildDirectory.dir("generated/guest")
val bridgeObjDir = layout.buildDirectory.dir("generated/bridge/obj")
val isMac = System.getProperty("os.name").contains("mac", ignoreCase = true)
val bridgeSoFile = layout.buildDirectory.file(
  if (isMac) "generated/bridge/libbridgetests.dylib" else "generated/bridge/libbridgetests.so",
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

  macosArm64()

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
    }
    val jvmTest by getting {
      dependencies {
        implementation(kotlin("test-junit"))
        // ZiplineFile parsing for the embedded guest container is host-only; the loader
        // has no JS target, so this dependency must not sit in commonTest.
        implementation(projects.ziplineLoader)
      }
    }
    val nativeTest by getting {
      dependencies {
        implementation(projects.ziplineLoader)
      }
    }
  }

  // The bridge compiler plugin runs on every compilation so the guest (JS) gets the
  // injected __bridgeRegister dispatch, and the hosts generate their bridge sources.
  targets.all {
    compilations.all {
      // Naming logic from https://github.com/JetBrains/kotlin/blob/a0e6fb03f0288f0bff12be80c402d8a62b5b045a/libraries/tools/kotlin-gradle-plugin/src/main/kotlin/org/jetbrains/kotlin/gradle/plugin/KotlinTargetConfigurator.kt#L519-L520
      val pluginConfigurationName = PLUGIN_CLASSPATH_CONFIGURATION_NAME +
        target.disambiguationClassifier.orEmpty().capitalize() +
        compilationName.capitalize()
      project.dependencies.add(pluginConfigurationName, projects.ziplineBridgeKotlinPlugin)
    }
  }
}

tasks {
  // Every compilation must receive at least one -P arg: with no cOutputDir/nativeOutputDir the
  // plugin assumes a JS target and injects JS bridge dispatch into the bytecode. The JVM
  // compilation generates both the C bridge sources and the Kotlin/Native bridge sources.
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

  // Compile the JS guest with ZiplineCompiler into QuickJS bytecode (.zipline files).
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
    inputs.dir(layout.buildDirectory.dir("zipline-guest"))
    outputs.dir(generatedGuestDir)
    doLast {
      val ziplineDir = layout.buildDirectory.dir("zipline-guest").get().asFile
      val modules = ziplineDir.listFiles { f -> f.extension == "zipline" }!!
        .sortedBy { it.name }
      val sb = StringBuilder()
      sb.appendLine("// GENERATED FILE. DO NOT MODIFY MANUALLY.")
      sb.appendLine("package app.cash.zipline.bridge.test")
      sb.appendLine()
      sb.appendLine("object GeneratedGuest {")
      sb.appendLine("  val modules: List<Pair<String, String>> = listOf(")
      for (f in modules) {
        val moduleId = "./${f.nameWithoutExtension}.js"
        val b64 = Base64.getEncoder().encodeToString(f.readBytes())
        sb.appendLine("    \"$moduleId\" to \"$b64\",")
      }
      sb.appendLine("  )")
      sb.appendLine("}")
      val outFile = generatedGuestDir.get().asFile.resolve("GeneratedGuest.kt")
      outFile.parentFile.mkdirs()
      outFile.writeText(sb.toString())
    }
  }

  // Compile the generated C bridge sources into a host .dylib/.so that links against the
  // already-loaded libquickjs (undefined symbols resolve at dlopen time).
  val compileBridgeC = register<Exec>("compileBridgeC") {
    dependsOn("compileKotlinJvm")
    inputs.dir(bridgeCOutputDir)
    inputs.file(projectDir.resolve("src/jvmTest/c/host2js_test_jni.c"))
    outputs.dir(bridgeObjDir)
    doFirst {
      // The Gradle daemon may run on a JRE without JNI headers (e.g. Android Studio's JBR),
      // so resolve the JDK via /usr/libexec/java_home on macOS.
      val javaHome = resolveJdkHome()
      val jniInclude = if (isMac) "$javaHome/include/darwin" else "$javaHome/include/linux"
      val repoNative = rootProject.projectDir.resolve("zipline/native")
      val cFiles = bridgeCOutputDir.get().asFile.listFiles { f -> f.extension == "c" }!!
        .sortedBy { it.name }
        .map { it.absolutePath } +
        // Handwritten JNI hooks for Host2JsBridgeEndToEndTest (bridgeAnyToJs/bridgeForAny
        // symbols resolve at dlopen time against the already-loaded libquickjs).
        listOf(projectDir.resolve("src/jvmTest/c/host2js_test_jni.c").absolutePath)
      val objDir = bridgeObjDir.get().asFile
      objDir.mkdirs()
      workingDir(objDir)
      commandLine(
        listOf(
          "clang", "-c", "-fPIC", "-std=gnu99",
          "-I$repoNative",
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
      val cmd = mutableListOf("clang", "-shared")
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

  // commonTest sources include the generated guest bytecode; wire the dependency explicitly
  // (srcDir with a plain directory provider does not infer it).
  kotlin.targets.withType<KotlinJvmTarget>().configureEach {
    compilations.getByName("test").compileTaskProvider.configure {
      dependsOn("generateGuestSource")
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
  }
}
