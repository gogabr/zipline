package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal val WITH_JS2HOST_BRIDGE_CLASS_ID = ClassId(
  FqName("app.cash.zipline.bridge.support"),
  Name.identifier("WithJS2HostBridge"),
)

internal val WITH_HOST2JS_BRIDGE_CLASS_ID = ClassId(
  FqName("app.cash.zipline.bridge.support"),
  Name.identifier("WithHost2JSBridge"),
)

class ZiplineBridgeIrGenerationExtension(
  internal val cOutputDir: String?,
  internal val nativeOutputDir: String? = null,
  internal val isJsTarget: Boolean = (cOutputDir == null && nativeOutputDir == null),
) : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    // Use IC-compatible DeclarationFinder (deprecated referenceClass crashes with K2/FIR).
    val finder = pluginContext.finderForBuiltins()
    finder.findClass(WITH_JS2HOST_BRIDGE_CLASS_ID) ?: return

    val annotatedClasses = findAnnotatedClasses(moduleFragment)
    if (annotatedClasses.isEmpty()) return

    // Include all annotated classes except abstract/sealed (no JS constructor to export)
    val dispatchClasses = annotatedClasses.filter {
      it.modality != org.jetbrains.kotlin.descriptors.Modality.ABSTRACT &&
      it.modality != org.jetbrains.kotlin.descriptors.Modality.SEALED &&
      it.kind != ClassKind.INTERFACE
    }

    // Host2JS classes (both-annotated included): same abstract/sealed/interface filter.
    val host2JsClasses = findHost2JsAnnotatedClasses(moduleFragment).filter {
      it.modality != org.jetbrains.kotlin.descriptors.Modality.ABSTRACT &&
      it.modality != org.jetbrains.kotlin.descriptors.Modality.SEALED &&
      it.kind != ClassKind.INTERFACE
    }

    // -- @JsName annotations (stable JS property names for all bridge backends) --
    annotateJsNames(finder, dispatchClasses, pluginContext)

    // -- Kotlin/Native bridge generation (iOS) --
    if (nativeOutputDir != null) {
      generateNativeBridges(nativeOutputDir, dispatchClasses)
    }

    // -- C/JNI bridge generation (Android) --
    if (cOutputDir != null) {
      generateCBridges(cOutputDir, annotatedClasses, host2JsClasses)
      generateKeepNames(cOutputDir, annotatedClasses)
      // JVM member injection: the external convertToJs(J)J native method, implemented by the
      // generated C. Injected with the C generation so the member and its implementation exist
      // together.
      injectJvmConvertToJsMembers(pluginContext, host2JsClasses)
    }

    // -- Kotlin/Native host2js member injection (override + Host2JsConvertible supertype) --
    if (pluginContext.platform?.componentPlatforms?.any { it is org.jetbrains.kotlin.platform.NativePlatform } == true) {
      injectNativeConvertToJsMembers(finder, pluginContext, host2JsClasses)
    }

    // -- JS bridge dispatch injection (Kotlin/JS) --
    if (isJsTarget) {
      // JS2Host-only classes keep the companion __bridgeRegister injection.
      injectCompanionInitBlocks(
        finder, moduleFragment,
        dispatchClasses.filterNot { it in host2JsClasses },
        pluginContext,
      )
      // Host2JS classes register via their companion constructor (prototype + runtime
      // factories); see injectCompanionBridgeRegistration for the fallback rationale.
      injectCompanionBridgeRegistration(finder, pluginContext, moduleFragment, host2JsClasses)
    }
  }

  // -- shared helpers --

  /** Add @JsName to properties of non-inline @WithJS2HostBridge classes for stable JS field names. */
  private fun annotateJsNames(
    finder: DeclarationFinder,
    dispatchClasses: List<IrClass>,
    pluginContext: IrPluginContext,
  ) {
    val jsNameClass = finder.findClass(JS_NAME_CLASS_ID) ?: return
    val jsNameCtor = jsNameClass.owner.declarations
      .filterIsInstance<IrConstructor>()
      .firstOrNull { it.isPrimary } ?: return
    for (clazz in dispatchClasses) {
      if (isInlineClass(clazz)) continue
      for (property in clazz.properties) {
        if (property.hasAnnotation(JS_NAME_CLASS_ID)) continue
        addJsNameAnnotation(property, property.name.asString(), jsNameCtor, pluginContext.irBuiltIns.stringType)
      }
    }
  }
}

internal val JVM_INLINE_CLASS_ID = ClassId(
  FqName("kotlin.jvm"),
  Name.identifier("JvmInline"),
)
internal val JS_NAME_CLASS_ID = ClassId(
  FqName("kotlin.js"),
  Name.identifier("JsName"),
)

/** Reads the optional [WithJS2HostBridge.targetFqn] from a class annotation. */
internal fun resolveTargetFqn(irClass: IrClass): String? {
  val annotation = irClass.annotations.firstOrNull {
    (it.symbol.owner.returnType.getClass()?.classId) == WITH_JS2HOST_BRIDGE_CLASS_ID
  } ?: return null
  val arg = annotation.arguments.getOrNull(0) ?: return null
  val value = (arg as? org.jetbrains.kotlin.ir.expressions.IrConst)?.value as? String
  if (!value.isNullOrEmpty()) return value
  return null
}
