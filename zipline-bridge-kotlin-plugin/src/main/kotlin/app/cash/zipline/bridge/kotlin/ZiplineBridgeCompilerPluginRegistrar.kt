package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

@OptIn(ExperimentalCompilerApi::class)
class ZiplineBridgeCompilerPluginRegistrar : CompilerPluginRegistrar() {
  override val pluginId: String get() = BuildConfig.KOTLIN_PLUGIN_ID
  override val supportsK2 get() = true

  override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
    val cOutputDir = configuration[COutputDirKey]
    val nativeOutputDir = configuration[NativeOutputDirKey]
    val jsDispatch = configuration[JsDispatchKey] ?: (cOutputDir == null && nativeOutputDir == null)

    // A compilation that generates Kotlin/Native bridges also has to declare the companion factory
    // those bridges call: `bridgeCreate` holds the default expressions of the class being built, and
    // a default belongs to whoever constructs — which on a native host is the host module itself.
    // The generated bridge text and the annotated classes are compiled in that same module, so the
    // factory the bridge reaches for is the one declared for those classes.
    //
    // Not for the JVM host, which constructs through the JVM's own masked constructor that the
    // generated C calls directly.
    //
    // The JS guest needs it because the guest is the side that constructs `@WithHost2JSBridge`
    // payloads: the host hands it a plain object of the fields it owns and calls the guest's
    // `create_<fqn>` factory, which must run the guest's real constructor — defaults, initializer
    // blocks and validation included. A guest module with no bridge annotations resolves no factory
    // and is unaffected.
    val declaresBridgeCreate = nativeOutputDir != null || jsDispatch
    if (declaresBridgeCreate) {
      FirExtensionRegistrarAdapter.registerExtension(BridgeCreateFirConfigurator())
    }

    IrGenerationExtension.registerExtension(
      extension = ZiplineBridgeIrGenerationExtension(
        cOutputDir = cOutputDir,
        nativeOutputDir = nativeOutputDir,
        isJsTarget = jsDispatch,
        bridgeCreateBodies = declaresBridgeCreate,
      ),
    )
  }
}

/**
 * Declares `bridgeCreate` in FIR; [ZiplineBridgeIrGenerationExtension] fills in its body. The two
 * halves have to be registered together — a function declared in FIR with no body does not survive
 * compilation — so neither is registered on its own.
 */
internal class BridgeCreateFirConfigurator : FirExtensionRegistrar() {
  override fun ExtensionRegistrarContext.configurePlugin() {
    val declaration: FirDeclarationGenerationExtension.Factory =
      FirDeclarationGenerationExtension.Factory { session -> ZiplineBridgeFirExtension(session) }
    +declaration
  }
}
