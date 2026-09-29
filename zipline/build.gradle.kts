import java.util.concurrent.TimeUnit

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.jetbrains.kotlin.gradle.plugin.NATIVE_COMPILER_PLUGIN_CLASSPATH_CONFIGURATION_NAME
import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME
import org.jetbrains.kotlin.gradle.plugin.mpp.Framework
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTargetWithTests
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.konan.target.Architecture
import org.jetbrains.kotlin.konan.target.Family
import org.jetbrains.kotlin.konan.target.KonanTarget
import org.gradle.api.file.FileTree
import org.gradle.api.publish.maven.MavenPublication

plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
  id("com.android.library")
  id("org.jetbrains.dokka")
  id("com.vanniktech.maven.publish.base")
  id("co.touchlab.cklib")
  id("com.github.gmazzo.buildconfig")
  id("binary-compatibility-validator")
  id("com.jakewharton.test-distribution")
}

val copyTestingJs = tasks.register<Copy>("copyTestingJs") {
  dependsOn(":zipline-testing:compileDevelopmentLibraryKotlinJs")
  destinationDir = rootProject.layout.buildDirectory.dir("generated/testingJs").get().asFile
  from(rootDir.resolve("zipline-testing/build/compileSync/js/main/developmentLibrary/kotlin"))
}

// Hermes is built per platform, all via Gradle-driven CMake (no shell scripts):
//   1. Android: AGP's externalNativeBuild (native/hermes-jni-build per ABI).
//   2. Host JVM + Kotlin/Native macOS/Linux: the buildHermesHost* tasks below
//      produce a per-arch libhermesvm.dylib/.so (dynamic, full non-lean VM).
//   3. iOS: the buildHermesStatic* tasks produce a single static archive
//      `libhermesvm.a`, embedded in the iOS Kotlin/Native klib via cinterop
//      staticLibraries, so consumers only need the Gradle dependency.
tasks.withType<KotlinNativeTest>().configureEach {
  dependsOn(":zipline-testing:compileDevelopmentLibraryKotlinJs")
}

dependencies {
  add(PLUGIN_CLASSPATH_CONFIGURATION_NAME, projects.ziplineKotlinPlugin)
  add(NATIVE_COMPILER_PLUGIN_CLASSPATH_CONFIGURATION_NAME, projects.ziplineKotlinPlugin)
}

// Lean (no JIT/parser, ~1MB smaller per ABI) is the default. Pass
// -PhermesProd=false for the full engine: required for CDP debugging
// (Runtime.evaluate / evaluateOnCallFrame compile JS at runtime). Also gates
// the CDP test sources and their Ktor client dependencies.
val hermesProd = providers.gradleProperty("hermesProd").orNull?.toBooleanStrictOrNull() ?: true

/** The buildHermesHost* task producing the libhermesvm that [target] binaries link, if any. */
fun hermesHostBuildTaskName(target: KonanTarget): String? = when (target) {
  KonanTarget.MACOS_ARM64 -> "buildHermesHostMacosArm64"
  KonanTarget.MACOS_X64 -> "buildHermesHostMacosX86_64"
  KonanTarget.LINUX_X64 -> "buildHermesHostLinuxX64"
  else -> null
}

kotlin {
  androidTarget {
    // Substitute release AAR with debug AAR when the
    // zipline-debug project property is set (e.g.,
    // ./gradlew :zipline:publishToMavenLocal -Pzipline-debug=true).
    // Use this to produce a debug AAR (with full symbols) that can be
    // consumed by downstream apps for debugging runtime crashes.
    val debugAar = providers.gradleProperty("zipline-debug").orNull?.toBooleanStrictOrNull() == true
    if (debugAar) {
      publishLibraryVariants("debug")
    } else {
      publishLibraryVariants("release")
    }
  }
  jvm()

  js {
    nodejs()
  }

  linuxX64()
  macosX64()
  macosArm64()
  iosArm64()
  iosX64()
  iosSimulatorArm64()
  tvosArm64()
  tvosSimulatorArm64()
  tvosX64()

  applyDefaultHierarchyTemplate()

  sourceSets {
    val commonMain by getting {
      dependencies {
        api(libs.kotlinx.coroutines.core)
        api(libs.kotlinx.serialization.core)
        implementation(libs.kotlinx.serialization.json)
      }
    }
    val commonTest by getting {
      dependencies {
        implementation(libs.assertk)
        implementation(kotlin("test"))
        implementation(projects.ziplineCryptography)
        implementation(libs.kotlinx.coroutines.test)
        implementation(projects.ziplineTesting)
      }
    }

    val hostMain by creating {
      dependsOn(commonMain)
      dependencies {
        api(libs.okio.core)
      }
      if (hermesProd) {
        // Prod: no CDP debug server and no Ktor dependencies.
        kotlin {
          srcDir("kotlin")
          srcDir("src/hostMainProd/kotlin")
          exclude("app/cash/zipline/internal/cdp/KtorWebSocketConnection.kt")
          exclude("app/cash/zipline/internal/cdp/KtorNetworkCdpServer.kt")
          exclude("app/cash/zipline/internal/cdp/KtorHttpGet.kt")
        }
      } else {
        dependencies {
          implementation(libs.ktor.network)
          implementation(libs.ktor.websockets)
          implementation(libs.ktor.client.core)
          implementation(libs.ktor.client.cio)
        }
      }
    }
    val hostTest by creating {
      dependsOn(commonTest)
      if (hermesProd) {
        // CDP tests need the full (debuggable) engine; skip their sources in
        // prod builds so the Ktor client deps aren't needed either.
        kotlin {
          srcDir("kotlin")
          exclude("app/cash/zipline/CdpDebugTest.kt")
          exclude("app/cash/zipline/internal/cdp/**")
        }
      } else {
        dependencies {
          // CDP test client (Ktor client WebSockets work on JVM and Native).
          implementation(libs.ktor.client.core)
          implementation(libs.ktor.client.cio)
          implementation(libs.ktor.client.websockets)
          implementation(libs.ktor.network)
        }
      }
    }

    val jniMain by creating {
      dependsOn(hostMain)
      dependencies {
        api(libs.androidx.annotation)
      }
    }
    val jniTest by creating {
      dependsOn(hostTest)
      if (hermesProd) {
        kotlin {
          srcDir("kotlin")
          exclude("app/cash/zipline/internal/cdp/**")
        }
      }
    }

    val androidMain by getting {
      dependsOn(jniMain)
    }
    val androidInstrumentedTest by getting {
      dependsOn(jniTest)
      dependencies {
        implementation(libs.assertk)
        implementation(libs.junit)
        implementation(libs.androidx.test.runner)
        implementation(libs.kotlinx.coroutines.test)
        implementation(kotlin("test"))
        implementation(projects.ziplineTesting)
      }
    }
    val jvmMain by getting {
      dependsOn(jniMain)
    }
    val jvmTest by getting {
      dependsOn(jniTest)
      resources.srcDir(copyTestingJs)
      if (hermesProd) {
        kotlin {
          srcDir("kotlin")
          exclude("app/cash/zipline/SourceMapUrlProbeTest.kt")
        }
      }
      dependencies {
        implementation(libs.junit)
        implementation(projects.ziplineTesting)
      }
    }

    val nativeMain by getting {
      dependsOn(hostMain)
    }
    val nativeTest by getting {
      dependsOn(hostTest)
      if (hermesProd) {
        kotlin {
          srcDir("kotlin")
          exclude("app/cash/zipline/internal/cdp/**")
        }
      }
    }

    targets.withType<KotlinNativeTarget> {
      val main by compilations.getting

      // iOS uses a generated cinterop definition that embeds the static
      // Hermes+glue archive built by the per-target Gradle tasks below.
      // macOS/Linux keep using the checked-in def file plus dynamic lookup
      // of the host dylib built by the buildHermesHost* tasks below.
      val hermesDefFile = when (konanTarget.family) {
        Family.IOS -> layout.buildDirectory
          .file("generated/cinterop/hermes-${konanTarget.name}.def").get().asFile
        else -> file("src/nativeInterop/cinterop/hermes.def")
      }

      main.cinterops {
        create("hermes") {
          defFile(hermesDefFile)
          packageName("app.cash.zipline.hermes")
          if (konanTarget.family != Family.IOS) {
            // Header/include paths must come from the DSL (not the def file) so
            // they are anchored at the project directory and stay portable.
            // Use compilerOpts instead of includeDirs: an includeDir on the
            // whole native/ dir would also track preBuildHermesHost's output
            // (native/hermes/build_host_hermesc) as an input, which Gradle
            // rejects as an undeclared task dependency.
            headers(
              files(
                file("native/hermes-ios/hermes-ios.h"),
                file("native/hermes-core.h"),
              )
            )
            compilerOpts(
              "-I${file("native/hermes-ios").absolutePath}",
              "-I${file("native").absolutePath}",
            )
          }
        }
      }

      // The hermes.def cinterop file cannot use an absolute `-L` path (it must
      // stay portable across machines), so the library search path is supplied
      // here for every native binary (test executables need it too). iOS has
      // the static archive embedded in its klib, so it does not need -L.
      // macOS/Linux host targets link the per-arch dylib built by the
      // buildHermesHost* tasks below.
      binaries.all {
        if (konanTarget.family != Family.IOS) {
          val hermesHostLibDir = when {
            konanTarget.family == Family.LINUX -> "linux-x64"
            konanTarget.architecture == Architecture.ARM64 -> "macos-arm64"
            // Matches registerBuildHermesHostMacos("x86_64").
            else -> "macos-x86_64"
          }
          linkerOpts += listOf(
            "-L${rootDir}/zipline/build/hermes-jni/$hermesHostLibDir",
            // Let the loader find libhermesvm.dylib at test/executable runtime.
            "-rpath", "${rootDir}/zipline/build/hermes-jni/$hermesHostLibDir",
          )
        }
        // Link against a freshly built host library rather than whatever is (or isn't) on disk.
        hermesHostBuildTaskName(konanTarget)?.let { taskName ->
          linkTaskProvider.configure { dependsOn(taskName) }
        }
      }

      binaries.withType<Framework> {
        when (konanTarget.family) {
          Family.IOS -> linkerOpts += listOf(
            "-framework", "Foundation",
            "-lsqlite3",
          )
          else -> linkerOpts += listOf(
            "-lhermesvm",
            "-lsqlite3",
            // Tell the dynamic loader where to find bundled dylibs at runtime.
            // Consumers must place libhermesvm.dylib inside the framework's
            // Frameworks/ subdirectory (or the app's Frameworks/ directory) for
            // the loader to find it at app launch.
            "-rpath", "@loader_path/Frameworks",
            "-rpath", "@executable_path/Frameworks",
          )
        }
      }
    }

    targets.withType<KotlinNativeTargetWithTests<*>> {
      binaries {
        // Configure a separate test where code is compiled in release mode.
        test(setOf(NativeBuildType.RELEASE))
      }
      testRuns {
        create("release") {
          setExecutionSourceFrom(binaries.getByName("releaseTest") as TestExecutable)
        }
      }
    }
  }
}

buildConfig {
  useKotlinOutput {
    internalVisibility = true
    topLevelConstants = true
  }

  sourceSets.named("hostMain") {
    packageName("app.cash.zipline")
    buildConfigField("String", "jsEngineVersion", "\"${jsEngineVersion()}\"")
  }
}

// JsEngine native libraries are built by Gradle-driven CMake (see the
// buildHermes* tasks below for host/iOS and the android externalNativeBuild
// block for Android).

fun jsEngineVersion(): String {
  // The vendored JsEngine source is pinned by its git revision (see
  // zipline/native/hermes/hermes-git-revision). Expose that here so the
  // generated BuildConfig mirrors the same value across host + Android.
  return File(projectDir, "native/hermes/hermes-git-revision").readText().trim()
}

// -----------------------------------------------------------------------------
// JsEngine host-side toolchain. The Android build needs a host
// `hermesc` (and `shermes`) binary to AOT-compile the InternalJavaScript
// stub. We pre-build them once per configure via a Gradle task, then
// point the Android CMake at the resulting ImportHostCompilers.cmake.
// This mirrors the React Native JsEngine Android setup (see
// native/hermes/android/build.gradle.kts).
//
// JsEngine is vendored at zipline/native/hermes. We resolve all paths via
// `rootProject.projectDir` (the build root), which makes them stable
// across subproject working directories.
val jsEngineRoot: File =
  File(rootProject.projectDir, "zipline/native/hermes")
val hermesImportCompilers =
  File(jsEngineRoot, "build_host_hermesc/ImportHostCompilers.cmake")

// Helper: pick up JAVA_HOME from the environment if it has JNI headers
// (the Gradle daemon's JBR does not), otherwise fall back to
// /usr/libexec/java_home or any installed JDK on macOS. AGP's
// externalNativeBuild doesn't always forward env vars to CMake, so we set
// JAVA_HOME on the relevant tasks.
val javaHome: String? = run {
  fun hasJniHeaders(home: String?) = home != null && File(home, "include/jni.h").exists()

  val fromEnv = System.getenv("JAVA_HOME")
  if (hasJniHeaders(fromEnv)) {
    fromEnv
  } else {
    val fromJavaHomeUtil = run {
      val os = System.getProperty("os.name").lowercase()
      if (os.contains("mac")) {
        try {
          val p = ProcessBuilder("/usr/libexec/java_home")
            .redirectErrorStream(true).start()
          if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0) {
            p.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
          } else null
        } catch (_: Exception) { null }
      } else null
    }
    when {
      hasJniHeaders(fromJavaHomeUtil) -> fromJavaHomeUtil
      else -> File("/Library/Java/JavaVirtualMachines").listFiles()
        ?.map { File(it, "Contents/Home") }
        ?.firstOrNull { hasJniHeaders(it.path) }
        ?.path
    }
  }
}

val preBuildHermesHost: TaskProvider<Exec> =
  tasks.register<Exec>("preBuildHermesHost") {
    description = "Pre-build host hermesc and shermes (used by Android cross-build's InternalJavaScript step)."
    group = "build"
    workingDir(jsEngineRoot)
    inputs.dir(jsEngineRoot)
    outputs.file(hermesImportCompilers)
    val cmakeBin = System.getenv("CMAKE_BIN") ?: "cmake"
    val jobs = Runtime.getRuntime().availableProcessors().toString()
    if (javaHome != null) {
      val jh: Any = javaHome!!
      environment("JAVA_HOME" to jh)
    }
    commandLine(
      "sh", "-c",
      """
      if [ ! -f '${hermesImportCompilers.absolutePath}' ]; then
        $cmakeBin -S '${jsEngineRoot.absolutePath}' -B '${jsEngineRoot.absolutePath}/build_host_hermesc' -G Ninja -DCMAKE_BUILD_TYPE=Release
        $cmakeBin --build '${jsEngineRoot.absolutePath}/build_host_hermesc' --target hermesc shermes -j $jobs
      fi
      """.trimIndent()
    )
  }

// ---- Host (JVM + Kotlin/Native macOS/Linux) Hermes dylib builds ----------
// The glue (native/hermes-jni-build/CMakeLists.txt) links a full, non-lean
// Hermes into a single libhermesvm.dylib/.so. The shared Hermes build hides
// compileJS() via -fvisibility=hidden, so the glue force-loads the static
// archives (libhermesvm_a.a + libjsi.a + boost_context) instead; those are
// produced once as universal (fat) macOS binaries by buildHermesMacosStatic.
// One dylib per host arch: the JVM loads it from jar resources
// (/jni/<arch>/libhermesvm.*, see jvmMain/JsNativeLoader.kt) and the
// Kotlin/Native host targets link it via -L (see the binaries block above).

val hermesMacosStaticDir = layout.buildDirectory.dir("hermes-macos-static")
val cmakeBin = System.getenv("CMAKE_BIN") ?: "cmake"
val hermesJobs = Runtime.getRuntime().availableProcessors().toString()

// Input tracking for the native build tasks below. Kept as trees so new glue
// files are picked up automatically and the lists can't drift from CMake's
// GLUE_*_SOURCES. Build output dirs (and the vendored Hermes tree, tracked
// separately via jsEngineRoot where needed) are excluded.
val hermesGlueInputFiles: FileTree = fileTree(File(rootProject.projectDir, "zipline/native")) {
  include("*.cpp", "*.h")
  include("hermes-ios/*.cpp", "hermes-ios/*.h")
  include("common/*.cpp", "common/*.h")
  exclude("hermes/**", "hermes-jni-build/**", "mimalloc/**", "include/**")
}
val hermesCmakeInputFiles: FileTree =
  fileTree(File(rootProject.projectDir, "zipline/native/hermes-jni-build")) {
    exclude("build*/**")
  }

val buildHermesMacosStatic: TaskProvider<Exec> =
  tasks.register<Exec>("buildHermesMacosStatic") {
    description = "Build universal static Hermes libs (force-loaded into the host dylib)"
    group = "build"
    dependsOn(preBuildHermesHost)
    inputs.dir(jsEngineRoot)
    val staticDir = hermesMacosStaticDir.get().asFile
    outputs.files(
      File(staticDir, "lib/libhermesvm_a.a"),
      File(staticDir, "jsi/libjsi.a"),
      File(staticDir, "external/boost/boost_1_86_0/libs/context/libboost_context.a"),
    )
    if (javaHome != null) {
      environment("JAVA_HOME" to javaHome!!)
    }
    commandLine(
      "sh", "-c",
      """
      $cmakeBin -S '${jsEngineRoot.absolutePath}' -B '${staticDir.absolutePath}' -G Ninja \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_OSX_ARCHITECTURES="x86_64;arm64" \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=10.15 \
        -DHERMES_ENABLE_DEBUGGER=ON \
        -DHERMES_ENABLE_INTL=ON \
        -DHERMES_ENABLE_TEST_SUITE=OFF \
        -DHERMES_ENABLE_TOOLS=OFF \
        -DHERMES_BUILD_SHARED_JSI=OFF \
        -DHERMES_BUILD_APPLE_FRAMEWORK=OFF \
        -DJSI_DIR='${jsEngineRoot.absolutePath}/API/jsi' \
        -DIMPORT_HOST_COMPILERS='${hermesImportCompilers.absolutePath}'
      $cmakeBin --build '${staticDir.absolutePath}' --target hermesvm jsi -j $hermesJobs
      """.trimIndent()
    )
  }

// Build the glue dylib (full Hermes + JSI statically linked) for one macOS arch.
fun registerBuildHermesHostMacos(arch: String): TaskProvider<Exec> {
  val buildDir = layout.buildDirectory.dir("hermes-jni/macos-$arch").get().asFile
  val dylib = File(buildDir, "libhermesvm.dylib")
  return tasks.register<Exec>("buildHermesHostMacos${arch.replaceFirstChar { it.uppercase() }}") {
    description = "Build host libhermesvm.dylib (macOS $arch)"
    group = "build"
    dependsOn(buildHermesMacosStatic)
    inputs.files(hermesGlueInputFiles)
    inputs.files(hermesCmakeInputFiles)
    inputs.files(buildHermesMacosStatic.map { it.outputs.files })
    outputs.file(dylib)
    if (javaHome != null) {
      environment("JAVA_HOME" to javaHome!!)
    }
    commandLine(
      "sh", "-c",
      """
      $cmakeBin -S '${file("native/hermes-jni-build").absolutePath}' -B '${buildDir.absolutePath}' -G Ninja \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_OSX_ARCHITECTURES='$arch' \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=10.15 \
        -DHERMESVM_LEAN=OFF \
        -DHERMES_SRC='${jsEngineRoot.absolutePath}' \
        -DHERMES_STATIC_BUILD_DIR='${hermesMacosStaticDir.get().asFile.absolutePath}' \
        -DIMPORT_HOST_COMPILERS='${hermesImportCompilers.absolutePath}' \
        -DJAVA_HOME='${javaHome ?: ""}'
      $cmakeBin --build '${buildDir.absolutePath}' --target hermesvm_jni -j $hermesJobs
      """.trimIndent()
    )
  }
}

val buildHermesHostMacosArm64 = registerBuildHermesHostMacos("arm64")
val buildHermesHostMacosX64 = registerBuildHermesHostMacos("x86_64")

// Linux host .so, cross-compiled from macOS. Best-effort: skipped when no
// Linux cross-toolchain is available (mirrors the old host-build.sh probe).
val linuxToolchainFile = file("native/hermes-jni-build/x86_64-linux-gnu-cross.cmake")
val linuxCrossToolchainAvailable: Boolean by lazy {
  fun commandExists(cmd: String): Boolean = try {
    ProcessBuilder("sh", "-c", "command -v $cmd >/dev/null 2>&1").start().waitFor() == 0
  } catch (_: Exception) {
    false
  }
  commandExists("x86_64-linux-gnu-gcc") ||
    commandExists("x86_64-unknown-linux-gnu-gcc") ||
    File("/opt/homebrew/Cellar/x86_64-unknown-linux-gnu").exists() ||
    System.getenv("LINUX_SYSROOT") != null
}

val hermesLinuxStaticDir = layout.buildDirectory.dir("hermes-linux-static")

fun Task.requireLinuxCrossToolchain() {
  doFirst {
    require(linuxCrossToolchainAvailable) {
      """
      Linux cross toolchain not found; it is required to build jni/amd64/libhermesvm.so
      for the zipline jvm jar. Install it and retry:
        brew install x86_64-unknown-linux-gnu
      (or provide x86_64-linux-gnu-gcc on PATH, or set the LINUX_SYSROOT env var)
      """.trimIndent()
    }
  }
}

val buildHermesLinuxStatic: TaskProvider<Exec> =
  tasks.register<Exec>("buildHermesLinuxStatic") {
    description = "Cross-build static Hermes libs for Linux x86_64"
    group = "build"
    requireLinuxCrossToolchain()
    dependsOn(preBuildHermesHost)
    inputs.dir(jsEngineRoot)
    inputs.file(linuxToolchainFile)
    val staticDir = hermesLinuxStaticDir.get().asFile
    outputs.files(
      File(staticDir, "lib/libhermesvm_a.a"),
      File(staticDir, "jsi/libjsi.a"),
      File(staticDir, "external/boost/boost_1_86_0/libs/context/libboost_context.a"),
    )
    commandLine(
      "sh", "-c",
      """
      $cmakeBin -S '${jsEngineRoot.absolutePath}' -B '${staticDir.absolutePath}' -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE='${linuxToolchainFile.absolutePath}' \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DHERMES_ENABLE_DEBUGGER=ON \
        -DHERMES_ENABLE_INTL=FALSE \
        -DHERMES_UNICODE_LITE=TRUE \
        -DHERMES_ENABLE_TEST_SUITE=OFF \
        -DHERMES_ENABLE_TOOLS=OFF \
        -DHERMES_BUILD_SHARED_JSI=OFF \
        -DHERMES_BUILD_APPLE_FRAMEWORK=OFF \
        -DJSI_DIR='${jsEngineRoot.absolutePath}/API/jsi' \
        -DIMPORT_HOST_COMPILERS='${hermesImportCompilers.absolutePath}'
      $cmakeBin --build '${staticDir.absolutePath}' --target hermesvm hermesvmlean jsi -j $hermesJobs
      """.trimIndent()
    )
  }

val buildHermesHostLinuxX64: TaskProvider<Exec> =
  tasks.register<Exec>("buildHermesHostLinuxX64") {
    description = "Cross-build host libhermesvm.so (Linux x86_64)"
    group = "build"
    requireLinuxCrossToolchain()
    dependsOn(buildHermesLinuxStatic)
    inputs.files(hermesGlueInputFiles)
    inputs.files(hermesCmakeInputFiles)
    inputs.files(buildHermesLinuxStatic.map { it.outputs.files })
    val buildDir = layout.buildDirectory.dir("hermes-jni/linux-x64").get().asFile
    val so = File(buildDir, "libhermesvm.so")
    outputs.file(so)
    commandLine(
      "sh", "-c",
      """
      $cmakeBin -S '${file("native/hermes-jni-build").absolutePath}' -B '${buildDir.absolutePath}' -G Ninja \
        -DCMAKE_TOOLCHAIN_FILE='${linuxToolchainFile.absolutePath}' \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DHERMESVM_LEAN=OFF \
        -DHERMES_SRC='${jsEngineRoot.absolutePath}' \
        -DHERMES_STATIC_BUILD_DIR='${hermesLinuxStaticDir.get().asFile.absolutePath}' \
        -DJAVA_HOME='${javaHome ?: ""}'
      $cmakeBin --build '${buildDir.absolutePath}' --target hermesvm_jni -j $hermesJobs
      """.trimIndent()
    )
  }

// Stage the host dylibs into the JVM jar resources; JsNativeLoader extracts
// and System.load()s them from /jni/<arch>/ at runtime.
val stageHermesHostDylibs: TaskProvider<Sync> =
  tasks.register<Sync>("stageHermesHostDylibs") {
    description = "Stage host libhermesvm dylibs into jvmMain resources"
    group = "build"
    into(projectDir.resolve("src/jvmMain/resources/jni"))
    from(buildHermesHostMacosArm64) { into("aarch64") }
    from(buildHermesHostMacosX64) { into("x86_64") }
    from(buildHermesHostLinuxX64) { into("amd64") }
    outputs.dir(projectDir.resolve("src/jvmMain/resources/jni"))
  }

// Defense in depth for publish: if the staged jni resources are ever incomplete
// (e.g. deleted, or a staging misconfiguration), fail instead of shipping a jvm
// jar that breaks consumers
val verifyHermesHostLibsStaged: TaskProvider<Task> =
  tasks.register("verifyHermesHostLibsStaged") {
    description = "Verify all host native libraries are staged into jvmMain resources"
    group = "verification"
    dependsOn(stageHermesHostDylibs)
    outputs.upToDateWhen { false }
    doLast {
      val jniDir = projectDir.resolve("src/jvmMain/resources/jni")
      val expected = mapOf(
        "aarch64" to "libhermesvm.dylib",
        "x86_64" to "libhermesvm.dylib",
        "amd64" to "libhermesvm.so",
      )
      val missing = expected.filter { (arch, lib) -> !File(jniDir, "$arch/$lib").isFile }
      check(missing.isEmpty()) {
        "Missing staged host native libraries: ${missing.values}. " +
          "Run ./gradlew :zipline:stageHermesHostDylibs on a machine with the macOS SDK " +
          "and the Linux cross toolchain (x86_64-linux-gnu-gcc or LINUX_SYSROOT) before publishing."
      }
    }
  }

// Build a merged static Hermes+Zipline archive for a single iOS variant.
// The output libhermesvm.a is embedded in the iOS Kotlin/Native klib.
//
// Lean mode (no JS compiler, ~1 MB smaller) is for PRODUCTION publishes and
// is OFF by default so compile()/evaluate() work in dev and in the test
// suite (dev-mode loadJsModule compiles JS on-device). Publish with:
//   ./gradlew publish... -PhermesIosLean=true
val hermesIosLean: Boolean =
  providers.gradleProperty("hermesIosLean").orNull?.toBooleanStrictOrNull() ?: false

fun registerBuildHermesStaticIos(
  konanTarget: KonanTarget,
  sdk: String,
  architectures: String,
): TaskProvider<Exec> {
  val lowerName = konanTarget.name
  val buildDir = layout.buildDirectory.dir("hermes-static/$lowerName/cmake").get().asFile
  val outputFile = File(buildDir, "libhermesvm.a")
  return tasks.register<Exec>("buildHermesStatic${lowerName.replaceUnderscoreCamelCase()}") {
    description = "Build static Hermes archive for ${konanTarget.name}"
    group = "build"
    dependsOn(preBuildHermesHost)
    inputs.dir(jsEngineRoot)
    inputs.files(hermesGlueInputFiles)
    inputs.files(hermesCmakeInputFiles)
    inputs.file(file("native/hermes-ios.exports"))
    inputs.property("hermesProd", hermesProd)
    outputs.file(outputFile)
    val cmakeBin = System.getenv("CMAKE_BIN") ?: "cmake"
    val jobs = Runtime.getRuntime().availableProcessors().toString()
    workingDir(rootProject.projectDir)
    commandLine(
      "sh", "-c",
      """
      set -euo pipefail
      SDK_PATH=$(xcrun --sdk '$sdk' --show-sdk-path)
      CC=$(xcrun --sdk '$sdk' -find clang)
      CXX=$(xcrun --sdk '$sdk' -find clang++)
      $cmakeBin -S '${file("native/hermes-jni-build").absolutePath}' \
        -B '${buildDir.absolutePath}' \
        -G Ninja \
        -DCMAKE_BUILD_TYPE=MinSizeRel \
        -DCMAKE_SYSTEM_NAME=iOS \
        -DCMAKE_C_COMPILER="${'$'}CC" \
        -DCMAKE_CXX_COMPILER="${'$'}CXX" \
        -DCMAKE_OSX_SYSROOT="${'$'}SDK_PATH" \
        -DCMAKE_OSX_DEPLOYMENT_TARGET=14.0 \
        -DCMAKE_OSX_ARCHITECTURES='$architectures' \
        -DHERMESVM_LEAN=${if (hermesProd) "ON" else "OFF"} \
        -DHERMES_ENABLE_DEBUGGER=${if (hermesProd) "OFF" else "ON"} \
        -DHERMES_IOS_STATIC=ON \
        -DHERMES_SRC='${jsEngineRoot.absolutePath}' \
        -DIMPORT_HOST_COMPILERS='${hermesImportCompilers.absolutePath}'
      $cmakeBin --build '${buildDir.absolutePath}' --target hermesvm_static -j $jobs
      """.trimIndent()
    )
  }
}

// Turn ios_arm64 / ios_x64 / ios_simulator_arm64 into IosArm64 / IosX64 / IosSimulatorArm64.
fun String.replaceUnderscoreCamelCase(): String =
  split("_").joinToString("") { it.replaceFirstChar { ch -> ch.uppercase() } }

val buildHermesStaticIosArm64 =
  registerBuildHermesStaticIos(
    KonanTarget.IOS_ARM64,
    sdk = "iphoneos",
    architectures = "arm64",
  )

val buildHermesStaticIosX64 =
  registerBuildHermesStaticIos(
    KonanTarget.IOS_X64,
    sdk = "iphonesimulator",
    architectures = "x86_64",
  )

val buildHermesStaticIosSimulatorArm64 =
  registerBuildHermesStaticIos(
    KonanTarget.IOS_SIMULATOR_ARM64,
    sdk = "iphonesimulator",
    architectures = "arm64",
  )

// Generate the cinterop .def consumed by the iOS cinterop task (referenced
// in the cinterop block above). This must be a real task with a declared
// output: writing the file at configuration time breaks after `clean` when
// the configuration cache reuses a cached configuration.
fun registerGenerateHermesDef(konanTarget: KonanTarget): TaskProvider<Task> {
  val defFile = layout.buildDirectory.file("generated/cinterop/hermes-${konanTarget.name}.def")
  return tasks.register("generateHermesDef${konanTarget.name.replaceUnderscoreCamelCase()}") {
    description = "Generate hermes cinterop def for ${konanTarget.name}"
    group = "build"
    outputs.file(defFile)
    doLast {
      val f = defFile.get().asFile
      f.parentFile.mkdirs()
      val hermesStaticDir = layout.buildDirectory
        .dir("hermes-static/${konanTarget.name}/cmake").get().asFile
      f.writeText(
        """
        package = app.cash.zipline.hermes
        headers = ${file("native/hermes-ios/hermes-ios.h").absolutePath} ${file("native/hermes-core.h").absolutePath}
        compilerOpts = -I${file("native/hermes-ios").absolutePath} -I${file("native").absolutePath}
        staticLibraries = libhermesvm.a
        libraryPaths = ${hermesStaticDir.absolutePath}
        linkerOpts.ios = -framework Foundation -lsqlite3
        """.trimIndent()
      )
    }
  }
}

val generateHermesDefIosArm64 = registerGenerateHermesDef(KonanTarget.IOS_ARM64)
val generateHermesDefIosX64 = registerGenerateHermesDef(KonanTarget.IOS_X64)
val generateHermesDefIosSimulatorArm64 = registerGenerateHermesDef(KonanTarget.IOS_SIMULATOR_ARM64)

// The iOS Kotlin/Native interop tasks need the static archive to exist so
// cinterop can copy it into the hermes.klib, and the generated .def to
// exist as their declared definitionFile input. The archive is also declared
// as an input: dependsOn alone only orders the tasks, so a rebuilt archive
// would otherwise leave a stale copy inside the klib.
listOf(
  Triple("cinteropHermesIosArm64", buildHermesStaticIosArm64, generateHermesDefIosArm64),
  Triple("cinteropHermesIosX64", buildHermesStaticIosX64, generateHermesDefIosX64),
  Triple("cinteropHermesIosSimulatorArm64", buildHermesStaticIosSimulatorArm64, generateHermesDefIosSimulatorArm64),
).forEach { (taskName, staticTask, defTask) ->
  tasks.matching { it.name == taskName }.configureEach {
    dependsOn(staticTask, defTask)
    inputs.files(staticTask.map { it.outputs.files })
  }
}

// The JVM jar ships the host dylibs as resources, so stage them before
// resource processing, jar, and publication tasks. Android gets its .so
// from AGP's externalNativeBuild; iOS klibs pull in the static archive via
// the cinterop dependency above.
tasks.matching { it.name == "jvmJar" || it.name == "jvmProcessResources" }
  .configureEach { dependsOn(stageHermesHostDylibs) }

tasks.matching { it.name == "publishToMavenLocal" || it.name.startsWith("publish") }
  .configureEach { dependsOn(verifyHermesHostLibsStaged) }

android {
  namespace = "app.cash.zipline"
  compileSdk = libs.versions.compileSdk.get().toInt()

  buildFeatures {
    buildConfig = true
  }

  defaultConfig {
    minSdk = libs.versions.minSdk.get().toInt()
    multiDexEnabled = true

    buildConfigField(
      "String",
      "hermesLibraryName",
      "\"${if (hermesProd) "hermesvmlean" else "hermesvm"}\"",
    )

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    consumerProguardFiles("proguard-rules.pro")

    ndk {
      abiFilters += listOf("x86", "x86_64", "armeabi-v7a", "arm64-v8a")
    }

    // AGP invokes native/hermes-jni-build/CMakeLists.txt per ABI. That
    // CMakeLists does add_subdirectory(jsEngine) with the Android flags, then
    // adds our glue on top and links the whole thing into a single .so.
    externalNativeBuild {
      cmake {
        // Build only the engine variant we package (full by default via
        // -PhermesProd=false, lean otherwise); the other variant's .so would
        // otherwise be built and packaged too.
        targets(if (hermesProd) "hermesvmlean" else "hermesvm")
        arguments(
          "-DANDROID_TOOLCHAIN=clang",
          "-DANDROID_STL=c++_shared",
          // Pass the path to the host-side ImportHostCompilers.cmake so
          // Hermes InternalJavaScript step can invoke hermesc and shermes.
          "-DIMPORT_HOST_COMPILERS=${hermesImportCompilers.absolutePath}",
          // Pass JAVA_HOME for the JNI include path (on non-Apple; on Android
          // the NDK toolchain's sysroot include dir already has jni.h).
          "-DJAVA_HOME=${javaHome ?: ""}",
          // Pass explicitly: the CMake cache from older builds sticks otherwise.
          "-DHERMESVM_LEAN=${if (hermesProd) "TRUE" else "FALSE"}",
          "-DHERMES_ENABLE_DEBUGGER=${if (hermesProd) "OFF" else "ON"}",
        )
        cFlags("-fstrict-aliasing", "-DCONFIG_VERSION=\\\"${jsEngineVersion()}\\\"")
        cppFlags("-fstrict-aliasing", "-DCONFIG_VERSION=\\\"${jsEngineVersion()}\\\"")
      }
    }

    packaging {
      // We get multiple copies of some license files via JNA, which is a transitive dependency of
      // kotlinx-coroutines-test. Don't fail the build on these duplicates.
      resources {
        excludes += listOf("META-INF/AL2.0", "META-INF/LGPL2.1")
      }

      // Keep debug symbols to get function names if the JsEngine runtime
      // crashes. The release libzipline_jsengine_jni.so is ~5.5 MB; without
      // debug symbols it's ~3 MB. Application release builds can still
      // strip these away later.
      jniLibs.keepDebugSymbols += "**/libzipline_jsengine_jni.so"
      // Also keep debug symbols for the Hermes VM itself so we can debug
      // runtime crashes in the VM code (e.g., in evaluatePreparedJavaScript).
      jniLibs.keepDebugSymbols += "**/libhermesvmlean.so"

      // fbjni is required by Hermes Android CMakeLists when
      // HERMES_ENABLE_INTL=TRUE. We disable INTL so the linker never
      // pulls fbjni into libzipline_jsengine_jni.so, but we still
      // surface it via prefab so CMake's find_package(fbjni) succeeds
      // unconditionally and resolves to the real upstream package.
      // Note: fbjni is NOT a CMake build target; it's an imported
      // find_package target, so we don't list it in cmake.targets.
    }
    dependencies {
      // Real fbjni from Maven Central (prefab). The stub at
      // native/fbjni-stub/ is no longer needed because Hermes can find
      // the real package via CMAKE_PREFIX_PATH populated by prefab.
      //
      // compileOnly so fbjni's bundled libc++_shared.so (older NDK) is not
      // packaged into the final APK; zipline ships its own libc++_shared.so
      // (matching the NDK used to build our .so).
      // Removed because the real fbjni broke compose-live at runtime.
    }
  }

  // TODO: Remove when https://issuetracker.google.com/issues/260059413 is resolved.
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  sourceSets {
    getByName("androidTest") {
      resources.srcDir("src/androidInstrumentationTest/resources/")
      resources.srcDir(copyTestingJs)
    }
  }

  // The above `resources.srcDir(copyTestingJs)` code is supposed to automatically add a task
  // dependency, but it doesn't. So we add it ourselves using this nonsense.
  afterEvaluate {
    libraryVariants.onEach { libraryVariant ->
      libraryVariant.testVariant?.processJavaResourcesProvider?.configure {
        dependsOn(copyTestingJs)
      }
    }
  }

  buildTypes {
    val release by getting {
      externalNativeBuild {
        cmake {
          arguments("-DCMAKE_BUILD_TYPE=MinSizeRel")
          cFlags("-g0", "-Os", "-fomit-frame-pointer", "-DNDEBUG", "-fvisibility=hidden")
          cppFlags("-g0", "-Os", "-fomit-frame-pointer", "-DNDEBUG", "-fvisibility=hidden")
        }
      }
    }
    val debug by getting {
      externalNativeBuild {
        cmake {
          arguments("-DCMAKE_BUILD_TYPE=Debug")
          cFlags("-g", "-DDEBUG", "-DDUMP_LEAKS")
          cppFlags("-g", "-DDEBUG", "-DDUMP_LEAKS")
        }
      }
    }
  }

  externalNativeBuild {
    cmake {
      // Drive native/hermes-jni-build/CMakeLists.txt which does
      // add_subdirectory(jsEngine) + our glue.
      path = file("native/hermes-jni-build/CMakeLists.txt")
    }
  }

  // Make sure the host-side hermesc is built before AGP's native build. AGP
  // runs CMake configure (configureCMake*) as its own task, before
  // buildCMake*/externalNativeBuild*, and configure already includes
  // ImportHostCompilers.cmake.
  tasks.matching {
    it.name.startsWith("configureCMake") ||
      it.name.startsWith("buildCMake") ||
      it.name.startsWith("externalNativeBuild")
  }.configureEach { dependsOn(preBuildHermesHost) }
  // JAVA_HOME is passed to AGP's CMake invocation via -DJAVA_HOME=... on
  // the externalNativeBuild { cmake { arguments(...) } } block above. We
  // don't need to set it on the Gradle JVM itself.
}

configure<MavenPublishBaseExtension> {
  configure(
    KotlinMultiplatform(javadocJar = JavadocJar.Empty())
  )
}

// Bundle native headers into the AAR assets for consumer CMake builds.
val copyBridgeHeaders by tasks.registering(Copy::class) {
  from("native") {
    include("bridge_dispatch.h")
    include("quickjs/quickjs.h")
  }
  from("native/hermes/API/jsi") {
    include("jsi/*.h")
    into("hermes/API/jsi")
  }
  into(layout.buildDirectory.dir("generated/assets/bridge-headers"))
}

android {
  sourceSets {
    getByName("main").assets.srcDir(copyBridgeHeaders)
  }
}
tasks.matching { it.name.startsWith("merge") && it.name.contains("Assets") }.configureEach {
  dependsOn(copyBridgeHeaders)
}
