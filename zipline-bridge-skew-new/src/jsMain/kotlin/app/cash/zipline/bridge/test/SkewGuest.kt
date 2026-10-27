package app.cash.zipline.bridge.test

/**
 * The guest half of the new tree. Its bundle is loaded by the old tree's host, and this tree's host
 * loads the old tree's bundle.
 *
 * Mirrors the old tree's SkewGuest.kt with the post-change shapes and the same sentinel values, so a
 * given `provide*`/`readBack*` pair means the same thing in both trees. This tree has no
 * `provideOnlyInOld`: that class does not exist here, which is what makes the deleted-class direction
 * observable. The old tree has no `provideSealedLeafB` or `readBackOnlyInNew` for the same reason.
 *
 * Every `provide*` returns non-default sentinel values (never 0, null, or empty), so a value that
 * never arrived cannot pass as a default. Every `readBack*` reads the fields the *guest's own* shape
 * declares and joins them with `|`. Kotlin/JS string interpolation keeps the three states of a
 * host-written property distinguishable: a property the host never wrote interpolates as `undefined`,
 * a Kotlin null interpolates as `null`, and a value interpolates as its string form.
 */

@JsExport
fun provideControl(): ControlPayload = ControlPayload(a = 11, b = "ctl")

@JsExport
fun provideAddedField(): AddedFieldPayload = AddedFieldPayload(a = 11, b = "af", c = 3.5)

@JsExport
fun provideRemovedField(): RemovedFieldPayload = RemovedFieldPayload(a = 11, b = "rf")

@JsExport
fun provideRenamedField(): RenamedFieldPayload = RenamedFieldPayload(newName = "new")

@JsExport
fun provideRetypedField(): RetypedFieldPayload = RetypedFieldPayload(v = "ninety-nine")

@JsExport
fun provideNullability(): NullabilityPayload = NullabilityPayload(v = null)

@JsExport
fun provideHierarchy(): HierarchyPayload = HierarchyPayload(a = 11, b = "hp")

@JsExport
fun provideDroppedBase(): DroppedBasePayload = DroppedBasePayload(keep = "keep")

@JsExport
fun provideInterface(): InterfacePayload = InterfacePayload(v = 11)

@JsExport
fun provideWrappedHolder(): WrappedHolder = WrappedHolder(w = WrappedValue(v = 42))

@JsExport
fun provideGrowthEnumHolder(): GrowthEnumHolder = GrowthEnumHolder(value = GrowthEnum.C)

@JsExport
fun provideShiftedEnumHolder(): ShiftedEnumHolder = ShiftedEnumHolder(value = ShiftedEnum.X)


@JsExport
fun provideSealedLeafA(): SealedHolder = SealedHolder(s = SealedShape.LeafA(v = 11))

@JsExport
fun provideSealedLeafB(): SealedHolder = SealedHolder(s = SealedShape.LeafB(w = "lb"))

@JsExport
fun provideOnlyInNew(): OnlyInNew = OnlyInNew(x = 11)

/** Guest-side reads of a host-built payload, one per fixture. */
@JsExport
fun readBackControl(p: ControlPayload): String = "${p.a}|${p.b}"

@JsExport
fun readBackAddedField(p: AddedFieldPayload): String = "${p.a}|${p.b}|${p.c}"

@JsExport
fun readBackRemovedField(p: RemovedFieldPayload): String = "${p.a}|${p.b}"

@JsExport
fun readBackRenamedField(p: RenamedFieldPayload): String = "${p.newName}"

@JsExport
fun readBackRetypedField(p: RetypedFieldPayload): String = "${p.v}"

@JsExport
fun readBackNullability(p: NullabilityPayload): String = "${p.v}"

@JsExport
fun readBackHierarchy(p: HierarchyPayload): String = "${p.a}|${p.b}"

@JsExport
fun readBackDroppedBase(p: DroppedBasePayload): String = "${p.keep}"

@JsExport
fun readBackInterface(p: InterfacePayload): String = "${p.v}"

@JsExport
fun readBackGrowthEnum(p: GrowthEnumHolder): String = "${p.value}"

@JsExport
fun readBackShiftedEnum(p: ShiftedEnumHolder): String = "${p.value}"

@JsExport
fun readBackWrapped(p: WrappedHolder): String = "${p.w}"

@JsExport
fun readBackSealed(p: SealedHolder): String = "${p.s}"

@JsExport
fun readBackOnlyInNew(p: OnlyInNew): String = "${p.x}"
