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
fun provideBridgedCallbackHolder(): BridgedCallbackHolder = BridgedTestValues.callbackHolder

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

/** Returns kotlin.Unit, the shape a Unit-returning guest function (e.g. an event sink) produces. */
@JsExport
fun provideUnit(): Unit = Unit

/** A Kotlin/JS Map sent through the untyped channel (decoded by bridgeForAny host-side). */
@JsExport
fun provideGuestMap(): Map<String, String> = mapOf("a" to "1", "b" to "2")

/**
 * Kotlin/JS Lists sent through the untyped channel, in the shapes the stdlib actually produces:
 * `listOf` is an ArrayList (array-backed), `emptyList()`/`listOf(x)` are the single-object
 * singletons. A guest-authored List must decode host-side in all three, top-level or embedded,
 * without the guest reshaping the value.
 */
@JsExport
fun provideGuestList(): List<String> = listOf("a", "b")

@JsExport
fun provideGuestSingletonList(): List<String> = listOf("only")

@JsExport
fun provideGuestEmptyList(): List<String> = emptyList()

/** An unannotated Kotlin class: sending an instance to the host must fail loudly, naming it. */
class NotBridgedGuest(val payload: String)

@JsExport
fun provideNotBridgedGuest(): NotBridgedGuest = NotBridgedGuest("nope")
