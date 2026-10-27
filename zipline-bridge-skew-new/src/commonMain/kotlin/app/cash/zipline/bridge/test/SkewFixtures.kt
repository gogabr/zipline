package app.cash.zipline.bridge.test

import app.cash.zipline.bridge.support.WithHost2JSBridge
import app.cash.zipline.bridge.support.WithJS2HostBridge
import kotlin.jvm.JvmInline

/**
 * Post-change (new-tree) fixture shapes for the bridge version-skew matrix.
 *
 * Each type shares its FQN with the old tree's counterpart but has a different shape. See the old
 * tree's SkewFixtures.kt for the pairing rationale: the direct bridge keys a class by dotted FQN only
 * and carries no field names on the wire, so the host converter reads the guest object through its own
 * compiled field set.
 */

/** Control: identical in both trees; proves the harness itself works. */
@WithJS2HostBridge
@WithHost2JSBridge
data class ControlPayload(val a: Int, val b: String)

/** Field added: this tree carries an extra trailing field `c`. */
@WithJS2HostBridge
@WithHost2JSBridge
data class AddedFieldPayload(val a: Int, val b: String, val c: Double)

/** Field removed: this tree drops the trailing field `c`. */
@WithJS2HostBridge
@WithHost2JSBridge
data class RemovedFieldPayload(val a: Int, val b: String)

/** Field renamed: same slot, different name; no alias mechanism exists on trunk. */
@WithJS2HostBridge
@WithHost2JSBridge
data class RenamedFieldPayload(val newName: String)

/** Property type changed: String here, Int in the old tree. */
@WithJS2HostBridge
@WithHost2JSBridge
data class RetypedFieldPayload(val v: String)

/** Nullability flipped: String? here, non-null String in the old tree. */
@WithJS2HostBridge
@WithHost2JSBridge
data class NullabilityPayload(val v: String?)

/**
 * Superclass inserted mid-chain: this tree gives the class a superclass. The crossing fields stay own
 * constructor parameters, so the wire shape is unchanged.
 */
@WithJS2HostBridge
@WithHost2JSBridge
open class PayloadBase(val baseValue: Int = 7)

@WithJS2HostBridge
@WithHost2JSBridge
data class HierarchyPayload(val a: Int, val b: String) : PayloadBase()

/**
 * Superclass removed from the chain: this tree drops the `DroppedBase` supertype. `keep` is the only
 * crossing field in both trees.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class DroppedBasePayload(val keep: String)

/** Interface gained: this tree adds the `Marked` supertype. Crossing fields unchanged. */
interface Marked

@WithJS2HostBridge
@WithHost2JSBridge
data class InterfacePayload(val v: Int) : Marked

/** Enum entry appended in this tree; existing ordinals are preserved. */
@WithJS2HostBridge
@WithHost2JSBridge
enum class GrowthEnum { A, B, C }

/** Enum entry inserted mid-chain in this tree; every old entry shifts by one. */
@WithJS2HostBridge
@WithHost2JSBridge
enum class ShiftedEnum { W, X, Y }

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

/** Sealed hierarchy: this tree adds a second leaf `LeafB`. */
@WithJS2HostBridge
@WithHost2JSBridge
sealed interface SealedShape {
  @WithJS2HostBridge
  @WithHost2JSBridge
  data class LeafA(val v: Int) : SealedShape

  @WithJS2HostBridge
  @WithHost2JSBridge
  data class LeafB(val w: String) : SealedShape
}

/**
 * Holder for the sealed fixture: the leaves cross as a field rather than as a direct guest export
 * parameter, so the export signature never names a nested class.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class SealedHolder(val s: SealedShape)

/** Present only in the new tree: exercises the added-class direction. */
@WithJS2HostBridge
@WithHost2JSBridge
data class OnlyInNew(val x: Int)
