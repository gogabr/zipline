package app.cash.zipline.bridge.test

import app.cash.zipline.bridge.support.WithHost2JSBridge
import app.cash.zipline.bridge.support.WithJS2HostBridge
import kotlin.jvm.JvmInline

/*
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

/** Parameterised enum: the old revision declares no `label`; the new tree adds one. */
@WithJS2HostBridge
@WithHost2JSBridge
enum class ParamEnum {
  A,
  B,
}

@WithJS2HostBridge
@WithHost2JSBridge
data class ParamEnumHolder(val value: ParamEnum)

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

/**
 * Wide-arity default fixture: this frozen tree declares only the first 32 defaulted parameters,
 * where the new tree declares 40. The new host therefore takes the last 8 as defaults, and they
 * fall past the first flag word.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class WideDefaults(
  val p0: Int = 0,
  val p1: Int = 1,
  val p2: Int = 2,
  val p3: Int = 3,
  val p4: Int = 4,
  val p5: Int = 5,
  val p6: Int = 6,
  val p7: Int = 7,
  val p8: Int = 8,
  val p9: Int = 9,
  val p10: Int = 10,
  val p11: Int = 11,
  val p12: Int = 12,
  val p13: Int = 13,
  val p14: Int = 14,
  val p15: Int = 15,
  val p16: Int = 16,
  val p17: Int = 17,
  val p18: Int = 18,
  val p19: Int = 19,
  val p20: Int = 20,
  val p21: Int = 21,
  val p22: Int = 22,
  val p23: Int = 23,
  val p24: Int = 24,
  val p25: Int = 25,
  val p26: Int = 26,
  val p27: Int = 27,
  val p28: Int = 28,
  val p29: Int = 29,
  val p30: Int = 30,
  val p31: Int = 31,
)
// ------------------------------------------------------------------------------------------
// Defaulted constructor parameters: the pre-change half of each pair.
//
// Each class below exists in both trees with the same FQN and a different declaration, the way
// `AddedFieldPayload` and `RemovedFieldPayload` already do: this tree is the host that does not know
// about a parameter, the new tree is the guest that declares it with a default. They carry only
// `@WithHost2JSBridge` because the rows through them go host -> guest, except `SubsetPayload`, which
// also runs guest -> host so that the HOST is the side evaluating the chained default.
// (`BaseStatePayload` could not go both ways even if the row wanted it: an inherited property crossing
// the wire is open item T4.)
// ------------------------------------------------------------------------------------------

/** The new tree adds `added`, defaulted. This shape has no such parameter to send. */
@WithHost2JSBridge
data class DefaultedFieldPayload(val a: Int, val b: String)

/**
 * The new tree defaults `b` and `c`, requires `d`, and declares `twin` defaulted from BOTH of them.
 * This shape sends `a` and `d` and nothing else, so the guest has to produce the other three — one
 * of them by evaluating an expression over a value that is itself a default.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class SubsetPayload(val a: Int, val d: Int)

/** The new tree adds a defaulted `@JvmInline value class` field. */
@WithHost2JSBridge
data class WrappedDefaultPayload(val n: Int)

/**
 * The new tree computes a body property and refuses non-positive `w`. This shape neither: it accepts
 * any value, so a bad one can leave this host and has to be judged by the guest that receives it.
 */
@WithHost2JSBridge
data class ValidatedPayload(val w: Int)

/** The new tree inherits a defaulted `base` from its superclass and computes `doubled` there. */
@WithHost2JSBridge
data class BaseStatePayload(val own: Int)

/**
 * The old half of the visibility pair. This shape has one parameter, so a receiver that declares three
 * has to work out `tag` and `magic` itself — from a private companion value and a private top-level
 * function, which is what forces a generated factory to sit inside the class rather than beside it.
 *
 * It bridges both ways on purpose: the guest -> host rows are the ones that make the HOST evaluate the
 * defaults, and that is the Kotlin/Native half of the claim.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class PrivateDefaultsPayload(val a: Int)

/**
 * The sending half of the mutable-body-field pair: this tree declares no `extra` at all, so whatever
 * the receiver's own initializer says is what has to arrive. The other tree adds `var extra` and
 * prints it, which turns "absent" into a readable value instead of a crash — the state no
 * single-module test can produce, because a host always sends the fields its own declaration has.
 */
@WithJS2HostBridge
@WithHost2JSBridge
class MutableBodyPayload(val a: Int) {
  override fun toString(): String = "MutableBodyPayload(a=$a)"
}

/**
 * The sending half of the narrow-scalar pair: `Byte`, `Short` and `Char`, and this tree has only the
 * `Byte`. Kotlin/JS holds all three as numbers, and until the native decoder grew arms for them a
 * `Char` or `Short` field made the generated Kotlin fail to compile — a bridged class could declare
 * them and only discover it on the host that had no arm. The defaults this tree omits are what the
 * other tree has to evaluate, over the same flag word that carries an `Int`.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class NarrowDefaultsPayload(val b: Byte)
