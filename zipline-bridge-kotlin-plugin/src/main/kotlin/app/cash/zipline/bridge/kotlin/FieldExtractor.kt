package app.cash.zipline.bridge.kotlin

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrConstructor
import org.jetbrains.kotlin.ir.declarations.IrField
import org.jetbrains.kotlin.ir.declarations.IrModuleFragment
import org.jetbrains.kotlin.ir.declarations.IrParameterKind
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.IrTypeProjection
import org.jetbrains.kotlin.ir.types.getClass
import org.jetbrains.kotlin.ir.types.isMarkedNullable
import org.jetbrains.kotlin.ir.util.classId
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isInterface
import org.jetbrains.kotlin.ir.util.parentAsClass
import org.jetbrains.kotlin.ir.util.properties

// -- Field extraction and class discovery (shared between C, Native, and JS generators) --

/** Collect all classes in the module annotated with @WithJS2HostBridge. */
internal fun findAnnotatedClasses(
  moduleFragment: IrModuleFragment,
): List<IrClass> {
  val result = mutableListOf<IrClass>()
  for (irFile in moduleFragment.files) {
    for (declaration in irFile.declarations) {
      collectAnnotatedClasses(declaration, result)
    }
  }
  return result
}

/** Walk [declaration] and its nested classes, collecting @WithJS2HostBridge-annotated ones. */
internal fun collectAnnotatedClasses(
  declaration: org.jetbrains.kotlin.ir.declarations.IrDeclaration,
  acc: MutableList<IrClass>,
) {
  if (declaration is IrClass) {
    if (hasWithJS2HostBridgeAnnotation(declaration)) {
      acc.add(declaration)
    }
    for (nested in declaration.declarations) {
      collectAnnotatedClasses(nested, acc)
    }
  }
}

/** Collect all classes in the module annotated with @WithHost2JSBridge. */
internal fun findHost2JsAnnotatedClasses(
  moduleFragment: IrModuleFragment,
): List<IrClass> {
  val result = mutableListOf<IrClass>()
  for (irFile in moduleFragment.files) {
    for (declaration in irFile.declarations) {
      collectHost2JsAnnotatedClasses(declaration, result)
    }
  }
  return result
}

/** Walk [declaration] and its nested classes, collecting @WithHost2JSBridge-annotated ones. */
internal fun collectHost2JsAnnotatedClasses(
  declaration: org.jetbrains.kotlin.ir.declarations.IrDeclaration,
  acc: MutableList<IrClass>,
) {
  if (declaration is IrClass) {
    if (hasWithHost2JSBridgeAnnotation(declaration)) {
      acc.add(declaration)
    }
    for (nested in declaration.declarations) {
      collectHost2JsAnnotatedClasses(nested, acc)
    }
  }
}

internal fun importForType(fqName: String): String {
  val lastDot = fqName.lastIndexOf('.')
  if (lastDot < 0) return ""
  val pkg = fqName.substring(0, lastDot)
  val shortName = fqName.substring(lastDot + 1)
  return "import $pkg.$shortName"
}

/**
 * Generates a single file that retains all bridge functions in the module,
 * preventing the linker from dead-code eliminating them.
 */
internal fun extractFields(annotatedClass: IrClass, includeValBodyFields: Boolean = false): List<FieldInfo> {
  val primaryConstructor = annotatedClass.declarations
    .filterIsInstance<IrConstructor>()
    .firstOrNull { it.isPrimary }
  val primaryConstructorParams = primaryConstructor
    ?.parameters
    ?.filter { it.kind == IrParameterKind.Regular }
    ?.associateBy { it.name.asString() }
    ?: emptyMap()

  return annotatedClass.properties.mapNotNull {
    extractField(it, primaryConstructorParams, includeValBodyFields)
  }.toList()
}

private fun extractField(property: IrProperty, primaryConstructorParams: Map<String, IrValueParameter>, includeValBodyFields: Boolean): FieldInfo? {
  val type = property.getter?.returnType ?: return null
  val name = property.name.asString()
  val isConstructorParam = name in primaryConstructorParams

  if (!isConstructorParam && !property.hasBackingField()) return null
  if (!isConstructorParam && !property.isVar && !includeValBodyFields) return null

  // Default detection. A constructor-parameter field reports the primary-constructor parameter's
  // defaultValue. A body field resolves its backing field through overrides (the same traversal as
  // hasBackingField(): the IR fake-override's own backingField is null, so an inherited field must
  // be read from the real owner). A fake-override's backing-field initializer is the base
  // constructor's assignment `this.x = x` — a bare get of the base's IrValueParameter, NOT a field
  // default. Treating that get as a default would wrongly mark a non-defaulted inherited field as
  // defaulted and silently drop its loud use-time failure. The real default is that parameter's own
  // defaultValue (null when the base parameter is not defaulted).
  val defaultExpr: IrExpression? = if (isConstructorParam) {
    primaryConstructorParams[name]?.defaultValue?.expression
  } else {
    val init = property.resolveBackingField()?.initializer?.expression
    val baseParam = (init as? IrGetValue)?.symbol?.owner as? IrValueParameter
    if (baseParam != null) baseParam.defaultValue?.expression else init
  }
  val hasDefault = defaultExpr != null

  return FieldInfo(
    name = name,
    type = type,
    isConstructorParam = isConstructorParam,
    jsPropertyName = property.jsName(),
    hasDefault = hasDefault,
  )
}

/**
 * The backing field for [property], following the override chain to the nearest non-interface owner
 * when this declaration is a fake override (its own `backingField` is null). Mirrors the traversal
 * in [hasBackingField]; returns null when no class in the chain owns a backing field.
 */
private fun IrProperty.resolveBackingField(): IrField? {
  backingField?.let { return it }
  val overridden = overriddenSymbols.singleOrNull { !it.owner.parentAsClass.isInterface }?.owner
  return overridden?.resolveBackingField()
}

private fun IrProperty.hasBackingField(): Boolean = backingField != null ||
      //  TODO(gogabr): should I also check for `isFakeOverride`?
    overriddenSymbols.singleOrNull { !it.owner.parentAsClass.isInterface }?.owner?.hasBackingField() == true

// Public properties are read through their JS accessor (defineProp), which keeps the plain
// Kotlin (and @JsName-pinned) name regardless of override depth — Kotlin/JS mangles the
// backing fields to name_1, name_2, ... per override level, but the accessor is always the
// plain name. Only properties without an accessor must be read from their backing field:
// private properties and value-class boxes (both '_1'-mangled).
private fun IrProperty.jsName(): String {
  val kotlinName = name.asString()
  val isPrivate =
    visibility == org.jetbrains.kotlin.descriptors.DescriptorVisibilities.PRIVATE
  return if (isPrivate || isInlineClass(parentAsClass)) "${kotlinName}_1" else kotlinName
}

internal fun hasWithJS2HostBridgeAnnotation(irClass: IrClass): Boolean {
  return irClass.annotations.any {
    it.symbol.owner.returnType.getClass()?.classId == WITH_JS2HOST_BRIDGE_CLASS_ID
  }
}

internal fun hasWithHost2JSBridgeAnnotation(irClass: IrClass): Boolean {
  return irClass.annotations.any {
    it.symbol.owner.returnType.getClass()?.classId == WITH_HOST2JS_BRIDGE_CLASS_ID
  }
}

/** Check if an [IrClass] is an inline value class. */
internal fun isInlineClass(irClass: IrClass): Boolean {
  // value classes have isValue=true in Kotlin 2.x IR; fallback to @JvmInline annotation
  return irClass.isValue || irClass.hasAnnotation(JVM_INLINE_CLASS_ID)
}

// -- JNI type helpers (used by field extraction) --

internal fun jniTypeDescriptorForClass(irClass: IrClass): String {
  val fqName = irClass.fqNameWhenAvailable?.asString() ?: irClass.name.asString()
  val remapped = kotlinToJvmClass[fqName]
  if (remapped != null) return "L${remapped.replace('.', '/')};"
  return "L${buildJniClassName(irClass).replace('.', '/')};"
}

internal fun jniFieldDescriptor(ktType: String): String {
  val remapped = kotlinToJvmClass[ktType]
  val jvmFqName = remapped ?: ktType
  return "L${jvmFqName.replace('.', '/')};"
}

internal fun isJniPrimitive(ktType: String): Boolean = kotlinToJniFieldType[ktType]?.let { it.length == 1 } ?: false

internal fun isKnownType(ktType: String): Boolean = ktType in kotlinToJniFieldType

internal fun isPrimitiveArray(ktType: String): Boolean = ktType in primitiveArrayJniInfo

internal fun isStringElement(elementType: String?): Boolean = elementType == "kotlin.String"
