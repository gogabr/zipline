import org.jetbrains.kotlin.gradle.dsl.JsModuleKind
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.plugin.PLUGIN_CLASSPATH_CONFIGURATION_NAME

// CLV-121: real-engine service version-skew contract. The js target builds the guest bundle the
// host loads; jvm + macosArm64 run the host that takes/binds across the engine boundary. No C
// bridge: the JSON service path keys by signature hash, so the old/new fixture shapes coexist in
// one compilation and the host picks the version per row.
plugins {
  kotlin("multiplatform")
  kotlin("plugin.serialization")
}

kotlin {
  jvm()

  js {
    binaries.executable()
    compilerOptions {
      target.set("es2015")
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
      }
    }

    val hostTest by creating {
      dependsOn(getByName("commonTest"))
      dependencies {
        implementation(kotlin("test"))
        implementation(projects.zipline)
        implementation(projects.ziplineTesting)
      }
    }

    val jvmTest by getting {
      dependsOn(hostTest)
    }

    val nativeTest by getting {
      dependsOn(hostTest)
    }
  }

  // The zipline Kotlin plugin rewrites bind/take to the @PublishedApi adapter overloads; without it
  // the public overloads throw. Required on every compilation, including the js guest.
  targets.all {
    compilations.all {
      val pluginConfigurationName = PLUGIN_CLASSPATH_CONFIGURATION_NAME +
        target.disambiguationClassifier.orEmpty().replaceFirstChar { it.uppercaseChar() } +
        compilationName.replaceFirstChar { it.uppercaseChar() }
      project.dependencies.add(pluginConfigurationName, projects.ziplineKotlinPlugin)
    }
  }
}

tasks {
  // The host reads the guest's compiled JS at runtime; the bundle must exist before any host test runs.
  // jvmTest is a Gradle Test task; macosArm64Test is a Kotlin/Native test task and needs its own hook.
  withType<Test>().configureEach {
    dependsOn("compileDevelopmentExecutableKotlinJs")
  }
  named("macosArm64Test") {
    dependsOn(":zipline:buildHermesHostMacosArm64")
    dependsOn("compileDevelopmentExecutableKotlinJs")
  }

  // Forward the capture switch: `-D` on the Gradle CLI lands on the daemon JVM, not the test JVM.
  // Capture is a JVM-only affordance; the native actuals hardcode it off.
  withType<Test>().configureEach {
    System.getProperty("skew.capture")?.let { systemProperty("skew.capture", it) }
    if (System.getProperty("skew.capture") == "true") {
      testLogging.showStandardStreams = true
    }
  }
}
