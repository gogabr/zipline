import java.io.File
import org.gradle.api.artifacts.Configuration
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinJvm
import com.vanniktech.maven.publish.MavenPublishBaseExtension

plugins {
  kotlin("jvm")
  id("com.github.gmazzo.buildconfig")
  id("com.vanniktech.maven.publish.base")
}

dependencies {
  implementation(projects.ziplineBridgeAnnotations)
  compileOnly(kotlin("compiler-embeddable"))
  compileOnly(kotlin("stdlib"))

  testImplementation(kotlin("compiler-embeddable"))
  testImplementation(kotlin("test-junit"))
  testImplementation(libs.kotlin.compile.testing)
}

kotlin {
  sourceSets {
    configureEach {
      languageSettings.optIn("org.jetbrains.kotlin.ir.symbols.UnsafeDuringIrConstructionAPI")
    }
  }
}

buildConfig {
  useKotlinOutput {
    internalVisibility = true
  }

  packageName("app.cash.zipline.bridge.kotlin")
  buildConfigField("String", "KOTLIN_PLUGIN_ID", "\"${libs.plugins.zipline.bridge.kotlin.get()}\"")
}

configure<MavenPublishBaseExtension> {
  configure(
    KotlinJvm(
      javadocJar = JavadocJar.Empty()
    )
  )
}

// -- Backend coverage tests: drive the real Kotlin/JS and Kotlin/Native compilers --
//
// BridgeBackendCoverageTest asserts that the safety checks are reported by both non-JVM
// backends. It needs the plugin jar plus each backend's view of the annotations module, and
// the Kotlin/Native compiler; the latter is absent on machines without the native toolchain,
// where the native test reports itself as skipped.
val annotationsProject = project(":zipline-bridge-annotations")

val kotlinStdlibJsKlib: Configuration by configurations.creating {
  isCanBeConsumed = false
  isCanBeResolved = true
}
dependencies {
  add(kotlinStdlibJsKlib.name, "org.jetbrains.kotlin:kotlin-stdlib-js:${libs.versions.kotlin.get()}")
}

/** The `kotlinc-native` of the toolchain matching [kotlinVersion], as installed by the KGP. */
fun findKonanCompiler(kotlinVersion: String): String {
  val konanDataDir = File(System.getenv("KONAN_DATA_DIR") ?: "${System.getProperty("user.home")}/.konan")
  return konanDataDir.listFiles()
    .orEmpty()
    .filter { it.isDirectory && it.name.startsWith("kotlin-native-prebuilt-") && it.name.endsWith("-$kotlinVersion") }
    .map { it.resolve("bin/kotlinc-native") }
    .firstOrNull { it.canExecute() }
    ?.absolutePath
    .orEmpty()
}

tasks.named<Test>("test") {
  dependsOn(tasks.named<Jar>("jar"))
  dependsOn(":zipline-bridge-annotations:compileKotlinJs")
  dependsOn(":zipline-bridge-annotations:compileKotlinMacosArm64")

  systemProperty(
    "bridge.pluginJar",
    tasks.named<Jar>("jar").flatMap { it.archiveFile }.get().asFile.absolutePath,
  )
  systemProperty(
    "bridge.stdlibJsKlib",
    kotlinStdlibJsKlib.files.single { it.extension == "klib" }.absolutePath,
  )
  systemProperty(
    "bridge.annotationsJsKlib",
    annotationsProject.layout.buildDirectory.dir("classes/kotlin/js/main").get().asFile.absolutePath,
  )
  systemProperty(
    "bridge.annotationsNativeKlib",
    annotationsProject.layout.buildDirectory
      .dir("classes/kotlin/macosArm64/main/klib/${annotationsProject.name}")
      .get().asFile.absolutePath,
  )
  systemProperty("bridge.konanCompiler", findKonanCompiler(libs.versions.kotlin.get()))
}
