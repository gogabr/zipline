package app.cash.zipline.bridge.test

/**
 * The guest half of the new tree. Its bundle is loaded by the old tree's host, and this tree's host
 * loads the old tree's bundle.
 *
 * Mirrors the new tree's SkewGuest.kt with the pre-change shapes and the same sentinel values, so a
 * given `provide*`/`readBack*` pair means the same thing in both trees. This tree has no
 * `provideSealedLeafB` or `readBackOnlyInNew`: those classes do not exist here, which is what makes
 * the added-class direction observable.
 */

@JsExport
fun provideControl(): ControlPayload = ControlPayload(a = 11, b = "ctl")

@JsExport
fun provideAddedField(): AddedFieldPayload = AddedFieldPayload(a = 11, b = "af")

@JsExport
fun provideRemovedField(): RemovedFieldPayload = RemovedFieldPayload(a = 11, b = "rf", c = 3.5)

@JsExport
fun provideRenamedField(): RenamedFieldPayload = RenamedFieldPayload(oldName = "old")

@JsExport
fun provideRetypedField(): RetypedFieldPayload = RetypedFieldPayload(v = 99)

@JsExport
fun provideNullability(): NullabilityPayload = NullabilityPayload(v = "nonnull")

@JsExport
fun provideHierarchy(): HierarchyPayload = HierarchyPayload(a = 11, b = "hp")

@JsExport
fun provideDroppedBase(): DroppedBasePayload = DroppedBasePayload(keep = "keep")

@JsExport
fun provideInterface(): InterfacePayload = InterfacePayload(v = 11)

/** The old tree's highest entry is B; the new tree's extra C is what trips an old host. */
@JsExport
fun provideGrowthEnumHolder(): GrowthEnumHolder = GrowthEnumHolder(value = GrowthEnum.B)

/** Old X is ordinal 0; the new tree's mid-chain insert makes the same name ordinal 1. */
@JsExport
fun provideShiftedEnumHolder(): ShiftedEnumHolder = ShiftedEnumHolder(value = ShiftedEnum.X)

@JsExport
fun provideWrappedHolder(): WrappedHolder = WrappedHolder(w = WrappedValue(v = 42))

@JsExport
fun provideSealedLeafA(): SealedHolder = SealedHolder(s = SealedShape.LeafA(v = 11))

/** Present only in the old tree: exercises the deleted-class direction from the other side. */
@JsExport
fun provideOnlyInOld(): OnlyInOld = OnlyInOld(x = 11)

/** Guest-side reads of a host-built payload, one per fixture. */
@JsExport
fun readBackControl(p: ControlPayload): String = "${p.a}|${p.b}"

@JsExport
fun readBackAddedField(p: AddedFieldPayload): String = "${p.a}|${p.b}"

@JsExport
fun readBackRemovedField(p: RemovedFieldPayload): String = "${p.a}|${p.b}|${p.c}"

@JsExport
fun readBackRenamedField(p: RenamedFieldPayload): String = "${p.oldName}"

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
fun readBackOnlyInOld(p: OnlyInOld): String = "${p.x}"
