package app.cash.zipline.bridge.test

import app.cash.zipline.bridge.support.WithHost2JSBridge
import app.cash.zipline.bridge.support.WithJS2HostBridge
import kotlin.jvm.JvmInline

/*
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

/**
 * Wide-arity default fixture. The new tree declares 40 defaulted parameters, the frozen old tree
 * only the first 32, so the new host decoding the old guest's object takes the last 8 defaults.
 * Those land in the second flag word, which pins the packing against the descriptor the JVM
 * generated for the runtime class.
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
  val p32: Int = 32,
  val p33: Int = 33,
  val p34: Int = 34,
  val p35: Int = 35,
  val p36: Int = 36,
  val p37: Int = 37,
  val p38: Int = 38,
  val p39: Int = 39,
)
// ------------------------------------------------------------------------------------------
// Defaulted constructor parameters: the post-change half of each pair. See the old tree for the
// matching declarations and for why these carry only `@WithHost2JSBridge` — except `SubsetPayload`,
// which crosses both ways so that a chained default can be pinned with the HOST evaluating it.
// ------------------------------------------------------------------------------------------

/** `added` is the parameter this tree declares and the old host never sends. */
@WithHost2JSBridge
data class DefaultedFieldPayload(val a: Int, val b: String, val added: Int = 99)

/**
 * `b` and `c` are absent from the old host's shape, `d` is required in both, and `twin` is defaulted
 * from a value that is itself defaulted and a value that arrived — so a factory that evaluated each
 * default against placeholders rather than chosen values prints something else.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class SubsetPayload(
  val a: Int = 1,
  val b: String = "b",
  val c: Double = 2.5,
  val d: Int,
  val twin: Int = c.toInt() + a,
)

/** A defaulted `@JvmInline value class` field: the guest has to construct the wrapper, not receive it. */
@WithHost2JSBridge
data class WrappedDefaultPayload(val n: Int, val w: WrappedValue = WrappedValue(7))

/**
 * The reason the payload has to be built rather than stamped: `area` is computed and no host declares
 * it, and the initializer judges a value the old host ships without a second thought.
 */
@WithHost2JSBridge
class ValidatedPayload(val w: Int) {
  val area: Int = w * 3

  init {
    require(w > 0) { "w must be positive, got $w" }
  }

  override fun toString(): String = "ValidatedPayload(w=$w, area=$area)"
}

/** `base` and `doubled` live in the superclass, with a default — the state a stamped object lacks. */
@WithHost2JSBridge
open class BaseStateHost(val base: Int = 5) {
  val doubled: Int = base * 2
}

@WithHost2JSBridge
class BaseStatePayload(val own: Int = 3) : BaseStateHost()

/**
 * Visible to this file only. A default that calls it compiles inside the annotated class and nowhere
 * else, which is the guarantee the generated companion exists to give: a factory emitted at file scope
 * would not see this, and the build would say so.
 */

private fun hiddenMagic(): Int = 4242

/**
 * The receiving half of the narrow-scalar pair. `s` and `c` are defaulted, so this is also the row
 * that puts a `Short` and a `Char` through the flag word: a defaulted narrow parameter used to be
 * refused outright by the guest factory, which had no placeholder for a type that is neither an `Int`
 * nor a reference.
 */
@WithJS2HostBridge
@WithHost2JSBridge
data class NarrowDefaultsPayload(
  val b: Byte,
  val s: Short = 9,
  val c: Char = 'z',
  val u: UInt = 4u,
)

/**
 * The receiving half of the mutable-body-field pair: `extra` is a mutable body property, so it is not
 * a constructor parameter and `bridgeCreate`'s signature cannot carry it — the factory assigns it
 * after construction, only when the carrier actually has it. Both directions are exercised: as a
 * guest it receives the host's value, as a host its own guest may or may not send one.
 */
@WithHost2JSBridge
@WithJS2HostBridge
class MutableBodyPayload(val a: Int) {
  var extra: String = "guest-default"

  // Explicit, because the row has to show a body field the class's own toString would omit: a `val`
  // in the constructor is printed by the generated toString, a body `var` is not.
  override fun toString(): String = "MutableBodyPayload(a=$a, extra=$extra)"
}

/** `tag` reads a private companion value, `magic` the private top-level function above. */
@WithJS2HostBridge
@WithHost2JSBridge
data class PrivateDefaultsPayload(
  val a: Int,
  val tag: String = TAG,
  val magic: Int = hiddenMagic(),
) {
  private companion object {
    private const val TAG = "pc"
  }
}
