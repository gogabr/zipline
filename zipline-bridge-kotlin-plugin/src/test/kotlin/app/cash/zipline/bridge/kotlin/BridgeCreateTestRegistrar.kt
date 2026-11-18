package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/**
 * The bridge plugin together with both halves of the generated factory, for tests only.
 *
 * The shipped registrar leaves the factory out on purpose: a function declared in FIR with no body
 * filled in IR does not survive compilation, so the two halves only become useful together, and they
 * go live together when the generated callers are rewritten to reach for the factory. This registrar
 * is how the pair is compiled and checked in the meantime.
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

/** Declares `bridgeCreate` in FIR; its IR body comes from [fillBridgeCreateBodies]. */
private class BridgeCreateFirConfigurator : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    val declaration: FirDeclarationGenerationExtension.Factory =
      FirDeclarationGenerationExtension.Factory { session -> ZiplineBridgeFirExtension(session) }
    +declaration
  }
}
