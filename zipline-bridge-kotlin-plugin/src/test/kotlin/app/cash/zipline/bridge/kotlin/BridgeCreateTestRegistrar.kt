package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/**
 * The bridge plugin together with both halves of the generated factory, for tests only.
 *
 * The shipped registrar turns the factory on only for compilations that generate Kotlin/Native
 * bridges. This one turns it on unconditionally, so the fixture classes — which are plain JVM
 * sources with no native target — can be compiled with the factory present and called through it.
 */
@OptIn(ExperimentalCompilerApi::class)
class BridgeCreateTestRegistrar : CompilerPluginRegistrar() {
  override val pluginId: String get() = BuildConfig.KOTLIN_PLUGIN_ID
  override val supportsK2 get() = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    FirExtensionRegistrarAdapter.registerExtension(BridgeCreateFirConfigurator())
    IrGenerationExtension.registerExtension(
      // isJsTarget = false: the JS bridge text is a separate concern (it does not know about the
      // factory yet), and the JVM fixture classpath has no `kotlin.js` to satisfy its generator.
      ZiplineBridgeIrGenerationExtension(
        cOutputDir = null,
        nativeOutputDir = null,
        isJsTarget = false,
        bridgeCreateBodies = true,
      ),
    )
  }
}
