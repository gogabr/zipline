package app.cash.zipline.bridge.test

import app.cash.zipline.bridge.support.WithHost2JSBridge
import app.cash.zipline.bridge.support.WithJS2HostBridge
import kotlin.jvm.JvmInline

/**
 * Pre-change (old-tree) fixture shapes for the bridge version-skew matrix.
 *
 * Each type shares its FQN with the new tree's counterpart but has a deliberately different shape, so
 * a host compiled from one tree can be paired with a guest bundle compiled from the other. The direct
 * bridge keys a class by its dotted FQN only and carries no field names on the wire: the host
 * converter reads the guest object through *its own* compiled field set. That is why every change kind
 * is a distinct type rather than a variation on one class — add/delete cases need a type present in
 * exactly one tree.
 *
 * Only primary-constructor parameters cross by default (`FieldExtractor.extractField` skips non-`var`
 * properties that are not constructor parameters), so the hierarchy fixtures keep their crossing
 * fields as own constructor parameters and vary only the supertype.
 */

/** Control: identical in both trees; proves the harness itself works. */
@WithJS2HostBridge
@WithHost2JSBridge
data class ControlPayload(val a: Int, val b: String)

/** Field added: the new tree carries an extra trailing field `c`. */
@WithJS2HostBridge
@WithHost2JSBridge
data class AddedFieldPayload(val a: Int, val b: String)

/** Field removed: the new tree drops the trailing field `c`. */
@WithJS2HostBridge
@WithHost2JSBridge
data class RemovedFieldPayload(val a: Int, val b: String, val c: Double)

/** Field renamed: same slot, different name; no alias mechanism exists on trunk. */
@WithJS2HostBridge
@WithHost2JSBridge
data class RenamedFieldPayload(val oldName: String)

/** Property type changed: Int here, String in the new tree. */
@WithJS2HostBridge
@WithHost2JSBridge
data class RetypedFieldPayload(val v: Int)

/** Nullability flipped: non-null String here, String? in the new tree. */
@WithJS2HostBridge
@WithHost2JSBridge
data class NullabilityPayload(val v: String)

/**
 * Superclass inserted mid-chain: the new tree gives this class a superclass. Its crossing fields stay
 * own constructor parameters, so the wire shape is unchanged.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class HierarchyPayload(val a: Int, val b: String)

/**
 * Superclass removed from the chain: this tree extends [DroppedBase]; the new tree drops the base.
 * `keep` is the only crossing field in both trees.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class DroppedBasePayload(val keep: String) : DroppedBase(5)

@WithJS2HostBridge
@WithHost2JSBridge
open class DroppedBase(val inherited: Int)

/** Interface gained: the new tree adds the `Marked` supertype. Crossing fields unchanged. */
@WithJS2HostBridge
@WithHost2JSBridge
data class InterfacePayload(val v: Int)

/** Enum entry appended in the new tree; existing ordinals are preserved. */
@WithJS2HostBridge
@WithHost2JSBridge
enum class GrowthEnum { A, B }

/** Enum entry inserted mid-chain in the new tree; every existing entry shifts by one. */
@WithJS2HostBridge
@WithHost2JSBridge
enum class ShiftedEnum { X, Y }

/**
 * Value class, Int-backed in both trees: a control. Retyping the underlying type is NOT expressible
 * on the JVM host: the C generator's `_fromValue` casts the numeric JS tags (FLOAT64/INT) straight
 * to the underlying C type, so a reference-typed underlying fails to compile the generated bridge
 * (`cannot cast from type 'jdouble' to pointer type 'jstring'` in
 * `app_cash_zipline_bridge_test_WrappedValue.cpp`). Recorded as a blocked row in the README.
 */
@WithJS2HostBridge
@WithHost2JSBridge
@JvmInline
value class WrappedValue(val v: Int)

/**
 * Holder for the value-class fixture: Kotlin/JS inlines a standalone value-class return value to the
 * underlying primitive, which carries no bridge dispatch, so the value class must ride inside a field.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class WrappedHolder(val w: WrappedValue)

/** Enum holder, so enum skew is exercised through the same field path as other payloads. */
@WithJS2HostBridge
@WithHost2JSBridge
data class GrowthEnumHolder(val value: GrowthEnum)

@WithJS2HostBridge
@WithHost2JSBridge
data class ShiftedEnumHolder(val value: ShiftedEnum)

/** Sealed hierarchy: the new tree adds a second leaf. */
@WithJS2HostBridge
@WithHost2JSBridge
sealed interface SealedShape {
  @WithJS2HostBridge
  @WithHost2JSBridge
  data class LeafA(val v: Int) : SealedShape
}

/**
 * Holder for the sealed fixture: the leaves cross as a field rather than as a direct guest export
 * parameter, so the export signature never names a nested class.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class SealedHolder(val s: SealedShape)

/** Present only in the old tree: exercises the deleted-class direction. */
@WithJS2HostBridge
@WithHost2JSBridge
data class OnlyInOld(val x: Int)
