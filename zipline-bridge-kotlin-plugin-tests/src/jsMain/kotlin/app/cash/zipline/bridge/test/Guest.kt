/*
 * JS guest: constructs @WithJS2HostBridge values on the JS side. Constructing each value
 * triggers the bridge registration injected by the compiler plugin, so the host can
 * dispatch the result back to a Kotlin object.
 */
package app.cash.zipline.bridge.test

@JsExport
fun provideBridgedData(): BridgedData = BridgedTestValues.data

@JsExport
fun provideBridgedInline(): BridgedInline = BridgedTestValues.inline

@JsExport
fun provideBridgedInlineHolder(): BridgedInlineHolder = BridgedTestValues.inlineHolder

@JsExport
fun provideBridgedFloatHolder(): BridgedFloatHolder = BridgedTestValues.floatHolder

@JsExport
fun provideBridgedDoubleHolder(): BridgedDoubleHolder = BridgedTestValues.doubleHolder

@JsExport
fun provideBridgedNestedInlineHolder(): BridgedNestedInlineHolder = BridgedTestValues.nestedInlineHolder

@JsExport
fun provideBridgedNestedInlineHolderNull(): BridgedNestedInlineHolder = BridgedTestValues.nestedInlineHolderNull

@JsExport
fun provideBridgedEnum(): BridgedEnum = BridgedTestValues.enumSecond

@JsExport
fun provideBridgedEnumHolder(): BridgedEnumHolder = BridgedTestValues.enumHolder

@JsExport
fun provideBridgedListHolder(): BridgedListHolder = BridgedTestValues.listHolder
@JsExport
fun provideBridgedFloatListHolder(): BridgedFloatListHolder = BridgedTestValues.floatList

@JsExport
fun provideBridgedNested(): BridgedNested = BridgedTestValues.nested

@JsExport
fun provideBridgedNullableNull(): BridgedNullable = BridgedTestValues.nullableNull

@JsExport
fun provideBridgedNullableValue(): BridgedNullable = BridgedTestValues.nullableValue

// New exports for collections
@JsExport
fun provideBridgedArray(): BridgedArray = BridgedTestValues.array
@JsExport
fun provideBridgedNestedStructure(): BridgedNestedStructure = BridgedTestValues.nestedStructure

@JsExport
fun provideBridgedEmptyCollections(): BridgedEmptyCollections = BridgedTestValues.emptyCollections

// New exports for inheritance
@JsExport
fun provideBridgedBaseClass(): BridgedBaseClass = BridgedTestValues.baseClass

@JsExport
fun provideBridgedInheritanceChild(): BridgedInheritanceChild = BridgedTestValues.inheritanceChild

@JsExport
fun provideBridgedDeepInheritance(): BridgedDeepInheritance = BridgedTestValues.deepInheritance

@JsExport
fun provideBridgedOverrideBase(): BridgedOverrideBase = BridgedTestValues.overrideBase

@JsExport
fun provideBridgedOverrideChild(): BridgedOverrideChild = BridgedTestValues.overrideChild

@JsExport
fun provideBridgedInterfaceImplementation(): BridgedInterfaceImplementation = BridgedTestValues.interfaceImpl

// New exports for generics
@JsExport
fun provideBridgedGenericClassInt(): BridgedGenericClass<Int> = BridgedTestValues.genericInt

@JsExport
fun provideBridgedGenericClassString(): BridgedGenericClass<String> = BridgedTestValues.genericString

@JsExport
fun provideBridgedMultiGenericClass(): BridgedMultiGenericClass<String, Int> = BridgedTestValues.multiGeneric

@JsExport
fun provideBridgedBoundedGenericClass(): BridgedBoundedGenericClass<BridgedBoundedGenericBase> = BridgedTestValues.boundedGeneric

@JsExport
fun provideBridgedNestedGeneric(): BridgedNestedGeneric = BridgedTestValues.nestedGeneric

@JsExport
fun provideBridgedLongHolder(): BridgedLongHolder = BridgedTestValues.longHolder

@JsExport
fun provideBridgedLongInlineHolder(): BridgedLongInlineHolder = BridgedTestValues.longInlineHolder

@JsExport
fun provideBridgedLongInline(): BridgedLongInline = BridgedTestValues.longInline

/**
 * Constructs every @WithHost2JSBridge test value on the guest side. Construction fires each
 * class's companion-constructor bridge registration (prototype + runtime factories), so the
 * host can later build JS counterparts of host values without the guest constructing them
 * mid-test. Returns true on success.
 */
@JsExport
fun warmUpHost2Js(): Boolean {
  BridgedTestValues.data
  BridgedTestValues.inline
  BridgedTestValues.float
  BridgedTestValues.double
  BridgedTestValues.nestedInline
  BridgedTestValues.enumSecond
  BridgedTestValues.inlineHolder
  BridgedTestValues.floatHolder
  BridgedTestValues.doubleHolder
  BridgedTestValues.nestedInlineHolder
  BridgedTestValues.nestedInlineHolderNull
  BridgedTestValues.enumHolder
  BridgedTestValues.listHolder
  BridgedTestValues.nested
  BridgedTestValues.nullableNull
  BridgedTestValues.nullableValue
  BridgedTestValues.array
  BridgedTestValues.nestedStructure
  BridgedTestValues.emptyCollections
  BridgedTestValues.baseClass
  BridgedTestValues.inheritanceChild
  BridgedTestValues.deepInheritance
  BridgedTestValues.overrideBase
  BridgedTestValues.overrideChild
  BridgedTestValues.interfaceImpl
  BridgedTestValues.genericInt
  BridgedTestValues.genericString
  BridgedTestValues.multiGeneric
  BridgedTestValues.boundedGeneric
  BridgedTestValues.nestedGeneric
  BridgedTestValues.longInline
  BridgedTestValues.longHolder
  BridgedTestValues.longInlineHolder
  return true
}
