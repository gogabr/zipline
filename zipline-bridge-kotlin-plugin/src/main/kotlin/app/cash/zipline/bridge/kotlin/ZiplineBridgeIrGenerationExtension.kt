package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.backend.common.extensions.DeclarationFinder
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
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
  /**
   * Fill the body of the `bridgeCreate` the FIR extension declares. Off by default: until the
   * generated callers are rewritten to use the factory, the shipped plugin must not depend on it.
   */
  internal val bridgeCreateBodies: Boolean = false,
) : IrGenerationExtension {
  override fun generate(moduleFragment: IrModuleFragment, pluginContext: IrPluginContext) {
    if (bridgeCreateBodies) fillBridgeCreateBodies(moduleFragment, pluginContext)
    // Use IC-compatible DeclarationFinder (deprecated referenceClass crashes with K2/FIR).
    val finder = pluginContext.finderForBuiltins()
    finder.findClass(WITH_JS2HOST_BRIDGE_CLASS_ID) ?: return

    val annotatedClasses = findAnnotatedClasses(moduleFragment)
    val host2JsAnnotatedClasses = findHost2JsAnnotatedClasses(moduleFragment)

    if (annotatedClasses.isEmpty() && host2JsAnnotatedClasses.isEmpty()) return

    // Enum entries cross as singleton instances resolved by ordinal, so a mutable (var) property
    // cannot be carried and reading one from the shared singleton would silently corrupt global
    // state. Reject the shape up front, before any bridge is generated.
    reportMutableEnumProperties(
      (annotatedClasses + host2JsAnnotatedClasses).distinct(),
      pluginContext.messageCollector,
    )

    // Host2JS classes (both-annotated included). An enum class can be ABSTRACT or OPEN when its
    // entries override members, but it still has a fixed, closed set of singleton instances, so it
    // stays bridgeable regardless of modality.
    val host2JsClasses = host2JsAnnotatedClasses.filter { isBridgeableClass(it) }

    // Include all annotated classes except abstract/sealed (no JS constructor to export) — and
    // enums, which are bridgeable regardless of modality for the same reason as above.
    val dispatchClasses = annotatedClasses.filter { isBridgeableClass(it) }

    // -- @JsName annotations (stable JS property names for all bridge backends) --
    annotateJsNames(finder, dispatchClasses, pluginContext)

    // -- Kotlin/Native bridge generation (iOS) --
    if (nativeOutputDir != null) {
      generateNativeBridges(nativeOutputDir, dispatchClasses, pluginContext.messageCollector)
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
        finder,
        moduleFragment,
        dispatchClasses.filterNot { it in host2JsClasses },
        pluginContext,
      )
      // Host2JS classes register at module load (prototypes + runtime factories), so the host
      // can build payload objects for classes the guest never constructs. Modules with only
      // JS2Host classes still need the value ops: their collections/Longs/enums reach the host
      // through the same untyped path.
      injectModuleLoadBridgeRegistration(
        finder,
        pluginContext,
        moduleFragment,
        host2JsClasses,
        js2HostOnlyClasses = dispatchClasses.filterNot { it in host2JsClasses },
      )
    }
  }

  // -- shared helpers --

  /** True when [clazz] gets bridge support: enums regardless of modality, other classes only when
   * concrete and not an interface. */
  private fun isBridgeableClass(clazz: IrClass): Boolean {
    return clazz.kind == ClassKind.ENUM_CLASS ||
      (
        clazz.modality != org.jetbrains.kotlin.descriptors.Modality.ABSTRACT &&
       clazz.modality != org.jetbrains.kotlin.descriptors.Modality.SEALED &&
       clazz.kind != ClassKind.INTERFACE
      )
  }

  /** Reject mutable (var) properties on annotated enum classes with a clear compile-time error. */
  private fun reportMutableEnumProperties(classes: List<IrClass>, messageCollector: MessageCollector) {
    for (clazz in classes) {
      if (clazz.kind != ClassKind.ENUM_CLASS) continue
      val fqn = clazz.fqNameWhenAvailable?.asString() ?: clazz.name.asString()
      for (property in clazz.properties) {
        if (!property.isVar || property.isFakeOverride) continue
        messageCollector.report(
          CompilerMessageSeverity.ERROR,
          "enum class $fqn declares mutable property '${property.name.asString()}'; bridged enum " +
            "entries cross as singleton instances resolved by ordinal, so a mutable property cannot " +
            "be carried and reading one from the shared singleton would corrupt global state. " +
            "Make the property read-only, or remove the bridge annotation.",
        )
      }
    }
  }

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
