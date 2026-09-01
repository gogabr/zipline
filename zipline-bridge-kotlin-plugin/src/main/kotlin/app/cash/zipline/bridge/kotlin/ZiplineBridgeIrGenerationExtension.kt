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

    // -- @JsName annotations (stable JS property names for all bridge backends) --
    annotateJsNames(finder, dispatchClasses, pluginContext)

    // -- Kotlin/Native bridge generation (iOS) --
    if (nativeOutputDir != null) {
      generateNativeBridges(nativeOutputDir, dispatchClasses)
    }

    // -- C/JNI bridge generation (Android) --
    if (cOutputDir != null) {
      generateCBridges(cOutputDir, annotatedClasses)
      generateKeepNames(cOutputDir, annotatedClasses)
    }

    // -- JS bridge dispatch injection (Kotlin/JS) --
    if (isJsTarget) {
      injectCompanionInitBlocks(finder, moduleFragment, dispatchClasses, pluginContext)
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
