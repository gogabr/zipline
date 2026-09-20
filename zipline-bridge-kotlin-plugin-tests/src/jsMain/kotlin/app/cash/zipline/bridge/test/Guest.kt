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

@JsExport
fun provideBridgedLongBoxHolder(): BridgedLongBoxHolder = BridgedTestValues.longBoxHolder

/** The guest's own JS view of the holder: shows whether value-class instances arrive boxed. */
@JsExport
fun stringifyBridgedLongBoxHolder(): String = JSON.stringify(BridgedTestValues.longBoxHolder)

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

/**
 * Host-to-JS bridge harness entry points.
 *
 * The host pushes a value in with the engine's `callGuestFunction`, so each of these runs in the
 * guest with an already-converted argument:
 *
 * - [echoHostValue] returns its argument unchanged, so the caller exercises the whole host -> JS
 *   conversion and the JS -> host decode of the very same object.
 * - [stringifyHostValue] renders the guest's view of that object as JSON (a debugging and triage
 *   aid: it shows the field names and values the guest actually received).
 */
@JsExport
fun echoHostValue(value: Any?): Any? = value

@JsExport
fun stringifyHostValue(value: Any?): String = JSON.stringify(value)
