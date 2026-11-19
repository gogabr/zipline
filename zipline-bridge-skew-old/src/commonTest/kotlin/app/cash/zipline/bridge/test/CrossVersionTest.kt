package app.cash.zipline.bridge.test

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Bridge version-skew matrix for the **old host**.
 *
 * Each test pairs this tree's host — whose converters were compiled from this tree's pre-change
 * fixture shapes — with one of the two guest bundles. Own-bundle rows are the same-version control
 * and prove the harness itself; new-bundle rows are the old-host / new-guest direction. The reverse
 * direction (new host consuming the old bundle) lives in
 * `zipline-bridge-skew-new/src/commonTest/.../CrossVersionTest.kt`.
 *
 * Classes that only exist in the new tree (`OnlyInNew`, `SealedShape.LeafB`) cannot be constructed
 * here, so those rows are exercised only in the guest -> host direction from this side.
 *
 * Skew rows go through [assertObserved], which compares the observed value or thrown exception as a
 * string and prints it under `-Dskew.capture=true` instead of asserting, so the silent rows (absent
 * field, mistyped read, out-of-range ordinal) were pinned from the bridge rather than guessed.
 */
class CrossVersionTest {

  // ------------------------------------------------------------------------------------------
  // Control: old host + old guest.
  // ------------------------------------------------------------------------------------------

  @Test fun control_guestToHost() = withOldGuest { host, id ->
    assertEquals("ControlPayload(a=11, b=ctl)", host.evaluateOne(provide("provideControl", id)).toString())
    assertEquals("AddedFieldPayload(a=11, b=af)", host.evaluateOne(provide("provideAddedField", id)).toString())
    assertEquals("RemovedFieldPayload(a=11, b=rf, c=3.5)", host.evaluateOne(provide("provideRemovedField", id)).toString())
    assertEquals("RenamedFieldPayload(oldName=old)", host.evaluateOne(provide("provideRenamedField", id)).toString())
    assertEquals("RetypedFieldPayload(v=99)", host.evaluateOne(provide("provideRetypedField", id)).toString())
    assertEquals("NullabilityPayload(v=nonnull)", host.evaluateOne(provide("provideNullability", id)).toString())
    assertEquals("HierarchyPayload(a=11, b=hp)", host.evaluateOne(provide("provideHierarchy", id)).toString())
    assertEquals("DroppedBasePayload(keep=keep)", host.evaluateOne(provide("provideDroppedBase", id)).toString())
    assertEquals("InterfacePayload(v=11)", host.evaluateOne(provide("provideInterface", id)).toString())
    assertEquals("GrowthEnumHolder(value=B)", host.evaluateOne(provide("provideGrowthEnumHolder", id)).toString())
    assertEquals("ShiftedEnumHolder(value=X)", host.evaluateOne(provide("provideShiftedEnumHolder", id)).toString())
    assertEquals("WrappedHolder(w=WrappedValue(v=42))", host.evaluateOne(provide("provideWrappedHolder", id)).toString())
    assertEquals("SealedHolder(s=LeafA(v=11))", host.evaluateOne(provide("provideSealedLeafA", id)).toString())
    assertEquals("OnlyInOld(x=11)", host.evaluateOne(provide("provideOnlyInOld", id)).toString())
  }

  /**
   * Host -> guest with no skew. Enum payloads are absent here: the host refuses to send an enum at all,
   * even to a matching guest, so those rows live in [hostToGuest_enumRefused].
   */
  @Test fun control_hostToGuest() = withOldGuest { host, _ ->
    assertEquals("11|ctl", host.read("readBackControl", ControlPayload(a = 11, b = "ctl")))
    assertEquals("11|af", host.read("readBackAddedField", AddedFieldPayload(a = 11, b = "af")))
    assertEquals("11|rf|3.5", host.read("readBackRemovedField", RemovedFieldPayload(a = 11, b = "rf", c = 3.5)))
    assertEquals("old", host.read("readBackRenamedField", RenamedFieldPayload(oldName = "old")))
    assertEquals("99", host.read("readBackRetypedField", RetypedFieldPayload(v = 99)))
    assertEquals("nonnull", host.read("readBackNullability", NullabilityPayload(v = "nonnull")))
    assertEquals("11|hp", host.read("readBackHierarchy", HierarchyPayload(a = 11, b = "hp")))
    assertEquals("keep", host.read("readBackDroppedBase", DroppedBasePayload(keep = "keep")))
    assertEquals("11", host.read("readBackInterface", InterfacePayload(v = 11)))
    assertEquals("LeafA(v=11)", host.read("readBackSealed", SealedHolder(s = SealedShape.LeafA(v = 11))))
    assertEquals("11", host.read("readBackOnlyInOld", OnlyInOld(x = 11)))
  }

  // ------------------------------------------------------------------------------------------
  // Guest -> host: old host decoding the new guest's objects.
  // ------------------------------------------------------------------------------------------

  /**
   * A class the new guest sends that this host never declares. The decode is loud on both hosts:
   * the JVM entry point passes `throwOnUnsupportedType = true` (ContextJni.cpp) and Kotlin/Native
   * throws the same message, so the unknown object surfaces as a catchable mismatch naming the class.
   */
  @Test fun guestToHost_classAddedOnGuest() = withNewGuest { host, id ->
    assertObserved(
      "added-class/OnlyInNew",
      "throws ZiplineApiMismatchException: host bridge: no converter registered for JS class 'OnlyInNew'; " +
        "annotate the class with @WithJS2HostBridge to send it to the host",
    ) {
      host.evaluateOne(provide("provideOnlyInNew", id))
    }
  }

  /**
   * Field added in the guest: this host's converter reads only its own field set, so the guest's
   * extra `c` never reaches the host. Compatible but lossy.
   */
  @Test fun guestToHost_extraFieldDropped() = withNewGuest { host, id ->
    assertEquals("AddedFieldPayload(a=11, b=af)", host.evaluateOne(provide("provideAddedField", id)).toString())
  }

  /**
   * Field removed in the guest: this host still reads `c`, and the guest's object has no such
   * property, so the non-null Double read throws rather than silently defaulting to 0.0.
   */
  @Test fun guestToHost_fieldAbsentOnGuest() = withNewGuest { host, id ->
    assertObserved(
      "field-absent/RemovedFieldPayload.c",
      "throws ZiplineApiMismatchException: host bridge: app.cash.zipline.bridge.test.RemovedFieldPayload.c " +
        "expects a number but the guest sent undefined",
    ) {
      host.evaluateOne(provide("provideRemovedField", id))
    }
  }

  /**
   * Field renamed with no alias: the new guest sends `newName`, this host reads `oldName`, so the
   * property is absent and the non-null String read throws naming the field and the JS kind.
   */
  @Test fun guestToHost_fieldRenamed() = withNewGuest { host, id ->
    assertObserved(
      "renamed/RenamedFieldPayload.oldName",
      "throws ZiplineApiMismatchException: host bridge: app.cash.zipline.bridge.test.RenamedFieldPayload.oldName " +
        "expects a string but the guest sent undefined",
    ) {
      host.evaluateOne(provide("provideRenamedField", id))
    }
  }

  /**
   * Property retyped String -> Int: the new guest sends a string where this host's converter expects
   * a number, so the tag check fires and names the kind seen.
   */
  @Test fun guestToHost_fieldRetyped() = withNewGuest { host, id ->
    assertObserved(
      "retyped/RetypedFieldPayload.v",
      "throws ZiplineApiMismatchException: host bridge: app.cash.zipline.bridge.test.RetypedFieldPayload.v " +
        "expects a number but the guest sent a string",
    ) {
      host.evaluateOne(provide("provideRetypedField", id))
    }
  }

  /**
   * Null sent into a field this host declares non-null: the tag check fires on the null value and
   * names the kind seen rather than letting a null reach the constructor.
   */
  @Test fun guestToHost_nullIntoNonNullable() = withNewGuest { host, id ->
    assertObserved(
      "null-into-nonnull/NullabilityPayload.v",
      "throws ZiplineApiMismatchException: host bridge: app.cash.zipline.bridge.test.NullabilityPayload.v " +
        "expects a string but the guest sent null",
    ) {
      host.evaluateOne(provide("provideNullability", id))
    }
  }

  /**
   * Supertype added in the guest: field extraction is own-class-only, so the wire shape never
   * changed and this host decodes the object exactly.
   */
  @Test fun guestToHost_superclassAddedOnGuest() = withNewGuest { host, id ->
    assertEquals("HierarchyPayload(a=11, b=hp)", host.evaluateOne(provide("provideHierarchy", id)).toString())
  }

  /**
   * Supertype removed in the guest is NOT wire-neutral on the JVM host: its converter still extracts
   * the base's `inherited` property, which the new guest never sends, so the read throws naming the
   * inherited field. The Kotlin/Native host never extracts inherited body fields at all (its
   * `extractFields` default excludes them), so it never asks and decodes the object silently — a
   * pre-existing host asymmetry in *which* fields are read, not in the loud/quiet invariant.
   */
  @Test fun guestToHost_superclassRemovedOnGuest() = withNewGuest { host, id ->
    assertObserved(
      "superclass-removed/DroppedBasePayload.inherited",
      "throws ZiplineApiMismatchException: host bridge: app.cash.zipline.bridge.test.DroppedBasePayload.inherited " +
        "expects a number but the guest sent undefined",
      nativeExpected = "DroppedBasePayload(keep=keep)",
    ) {
      host.evaluateOne(provide("provideDroppedBase", id))
    }
  }

  /** Interface added in the guest: wire-invisible. */
  @Test fun guestToHost_interfaceAddedOnGuest() = withNewGuest { host, id ->
    assertEquals("InterfacePayload(v=11)", host.evaluateOne(provide("provideInterface", id)).toString())
  }

  /**
   * Enum entry appended in the guest: ordinal 2 has no constant in this host's enum. Both hosts fire
   * the generated range check and throw the same loud out-of-range message naming the enum.
   */
  @Test fun guestToHost_enumOutOfRange() = withNewGuest { host, id ->
    assertObserved(
      "enum-out-of-range/GrowthEnum.C",
      "throws ZiplineApiMismatchException: host bridge: ordinal 2 is out of range for " +
        "app.cash.zipline.bridge.test.GrowthEnum",
    ) {
      host.evaluateOne(provide("provideGrowthEnumHolder", id))
    }
  }

  /**
   * Enum entry inserted mid-chain in the guest: the guest's X is ordinal 1, which this host maps to
   * Y. No error — the wrong value crosses.
   */
  @Test fun guestToHost_enumShifted() = withNewGuest { host, id ->
    assertEquals("ShiftedEnumHolder(value=Y)", host.evaluateOne(provide("provideShiftedEnumHolder", id)).toString())
  }

  /** Sealed leaf added in the guest: this host has no class to decode it into. */
  @Test fun guestToHost_sealedLeafAddedOnGuest() = withNewGuest { host, id ->
    assertObserved(
      "added-class/SealedShape.LeafB",
      "throws ZiplineApiMismatchException: host bridge: no converter registered for JS class 'LeafB'; " +
        "annotate the class with @WithJS2HostBridge to send it to the host",
    ) {
      host.evaluateOne(provide("provideSealedLeafB", id))
    }
  }

  /** Sealed leaf both trees have: decodes through the holder. */
  @Test fun guestToHost_sealedLeafStillPresent() = withNewGuest { host, id ->
    assertEquals("SealedHolder(s=LeafA(v=11))", host.evaluateOne(provide("provideSealedLeafA", id)).toString())
  }

  // ------------------------------------------------------------------------------------------
  // Host -> guest: old host writing objects the new guest reads.
  // ------------------------------------------------------------------------------------------

  /** Host sends a class the new guest never declared: its prototype was never registered. */
  @Test fun hostToGuest_classDeletedOnGuest() = withNewGuest { host, id ->
    host.installReader(id, "readBackOnlyInOld")
    assertObserved(
      "host2js/OnlyInOld",
      "throws IllegalStateException: host2js: no registered prototype for " +
        "app.cash.zipline.bridge.test.OnlyInOld; the guest module did not call __bridgeRegister for this class",
    ) {
      host.read("readBackOnlyInOld", OnlyInOld(x = 11))
    }
  }

  /**
   * A `@JvmInline value class` field: it crosses, and the guest's own constructor builds it.
   *
   * The wrapper crosses as its underlying value (both hosts read the single backing field) and the
   * guest's create-factory rebuilds the wrapper around it, so the holder arrives holding a real
   * `WrappedValue`. The observed value is the same one this row read while the shape crossed by
   * stamping — which is exactly the trap: a `toString` cannot tell a real instance from a lookalike.
   * What tells them apart is that the constructor ran, pinned in
   * `Host2JsBridgeEndToEndTest.valueClassField*`.
   */
  @Test fun hostToGuest_valueClassField() = withNewGuest { host, _ ->
    assertObserved(
      "host2js-value-class-field/WrappedHolder.w",
      "WrappedValue(v=42)",
    ) {
      host.read("readBackWrapped", WrappedHolder(w = WrappedValue(v = 42)))
    }
  }

  /**
   * Host omits a field the new guest declares. This row used to read `11|af|undefined`: the guest
   * read an absent JS property and Kotlin/JS interpolation rendered it as `undefined`, so a wrong
   * value crossed quietly.
   *
   * It is loud now, and the reason is the guest's own factory: since the guest builds host->guest
   * payloads by running its constructor, the first thing that knows this parameter is required is the
   * code that needs it. Both hosts report the guest's words - the Kotlin/Native one through the JS
   * error text, which is why this row needs no `nativeExpected`.
   *
   * Recorded in the frozen tree rather than left stale because the fixture pair is what is frozen
   * here, not the plugin: this host is compiled by the current plugin, so this is what the pairing
   * does today.
   */
  @Test fun hostToGuest_fieldMissingOnGuest() = withNewGuest { host, _ ->
    assertObserved(
      "host2js-field-missing/AddedFieldPayload.c",
      "throws IllegalStateException: host2js: app.cash.zipline.bridge.test.AddedFieldPayload.c " +
        "was not sent by the host",
    ) {
      host.read("readBackAddedField", AddedFieldPayload(a = 11, b = "af"))
    }
  }

  /** Host writes a field the new guest's shape lacks: the guest ignores it. Compatible. */
  @Test fun hostToGuest_extraFieldIgnored() = withNewGuest { host, _ ->
    assertEquals("11|rf", host.read("readBackRemovedField", RemovedFieldPayload(a = 11, b = "rf", c = 3.5)))
  }

  /**
   * Renamed field, host side: the guest wants `newName`, the host wrote `oldName`. It used to arrive
   * as a bare property read, so raw `undefined` crossed back and decoded to null - a different shape of
   * the same silence as [hostToGuest_fieldMissingOnGuest].
   *
   * Now the guest's create-factory names the field and the class before any of that can happen.
   */
  @Test fun hostToGuest_fieldRenamed() = withNewGuest { host, _ ->
    assertObserved(
      "host2js-field-renamed/RenamedFieldPayload.newName",
      "throws IllegalStateException: host2js: app.cash.zipline.bridge.test.RenamedFieldPayload.newName " +
        "was not sent by the host",
    ) {
      host.read("readBackRenamedField", RenamedFieldPayload(oldName = "old"))
    }
  }

  /**
   * Retyped field, host side: the guest's String-typed property receives a JS number. The guest
   * stringifies it, so the host reads back the number's JS rendering: `99` on the JVM host and
   * `99.0` on Kotlin/Native, which renders the JS double with its fractional part.
   */
  @Test fun hostToGuest_fieldRetyped() = withNewGuest { host, _ ->
    assertObserved("host2js-retyped/RetypedFieldPayload.v", "99", nativeExpected = "99.0") {
      host.read("readBackRetypedField", RetypedFieldPayload(v = 99))
    }
  }

  /**
   * Host -> guest with an enum payload: refused by design, in both directions and with or without
   * skew. The JVM host's C generator and the Kotlin/Native host both throw rather than hand the
   * guest a lookalike object, so no enum ordinal ever crosses host -> guest and enum entry skew is
   * unreachable in this direction.
   */
  @Test fun hostToGuest_enumRefused() = withNewGuest { host, _ ->
    assertObserved(
      "host2js-enum/GrowthEnum.B",
      "throws IllegalStateException: host2js: app.cash.zipline.bridge.test.GrowthEnum is an enum; " +
        "the guest would only see a lookalike, not an enum instance",
    ) {
      host.read("readBackGrowthEnum", GrowthEnumHolder(value = GrowthEnum.B))
    }
    assertObserved(
      "host2js-enum/ShiftedEnum.X",
      "throws IllegalStateException: host2js: app.cash.zipline.bridge.test.ShiftedEnum is an enum; " +
        "the guest would only see a lookalike, not an enum instance",
    ) {
      host.read("readBackShiftedEnum", ShiftedEnumHolder(value = ShiftedEnum.X))
    }
  }

  // Value-class underlying-type retyping is not expressible: the C generator's `_fromValue` casts
  // the numeric JS tags straight to the underlying C type, so a reference-backed value class fails
  // to compile the host bridge. Blocked row; recorded in the README.

  /** Hierarchy and interface changes stay invisible host -> guest too. */
  @Test fun hostToGuest_shapeChangesInvisible() = withNewGuest { host, _ ->
    assertEquals("11|hp", host.read("readBackHierarchy", HierarchyPayload(a = 11, b = "hp")))
    assertEquals("keep", host.read("readBackDroppedBase", DroppedBasePayload(keep = "keep")))
    assertEquals("11", host.read("readBackInterface", InterfacePayload(v = 11)))
  }

  // ------------------------------------------------------------------------------------------
  // Defaulted constructor parameters. This host's converters emit exactly the fields its own class
  // declares, so an absent parameter can only arise from a pair like these: the new guest declares a
  // parameter this tree's shape does not have. Every expectation is a value that cannot come from the
  // host — a default, a computation, superclass state, or a refusal.
  // ------------------------------------------------------------------------------------------

  /** A parameter this host has never heard of, defaulted by the guest. */
  @Test fun hostToGuest_addedDefaultedField() = withNewGuest { host, _ ->
    assertObserved("host2js/defaulted-field/added", "11|df|99") {
      host.read("readBackDefaultedField", DefaultedFieldPayload(a = 11, b = "df"))
    }
  }

  /** `b` and `c` absent, `d` required and sent, `twin` defaulted from one of each. */
  @Test fun hostToGuest_subsetDefaultsIncludingChained() = withNewGuest { host, _ ->
    assertObserved("host2js/subset/defaults-and-chained", "7|b|2.5|9|9") {
      host.read("readBackSubset", SubsetPayload(a = 7, d = 9))
    }
  }

  /** A defaulted `@JvmInline value class` parameter: the guest builds the wrapper itself. */
  @Test fun hostToGuest_defaultedValueClassField() = withNewGuest { host, _ ->
    assertObserved("host2js/wrapped-default/absent", "3|7") {
      host.read("readBackWrappedDefault", WrappedDefaultPayload(n = 3))
    }
  }

  /** A body property no host declares, computed by the guest's constructor. */
  @Test fun hostToGuest_constructorComputesBodyValue() = withNewGuest { host, _ ->
    assertObserved("host2js/validated/computed", "4|12") {
      host.read("readBackValidated", ValidatedPayload(w = 4))
    }
  }

  /**
   * The same value judged by the two guests. This tree's shape has no validation, so -2 leaves here
   * without objection; the new guest's initializer refuses it. That asymmetry is the point: a stamped
   * payload would have been accepted exactly as the old guest accepts it.
   */
  @Test fun hostToGuest_constructorValidatesTheHostsValue() = withNewGuest { host, _ ->
    assertObserved("host2js/validated/refused", "throws IllegalStateException: w must be positive, got -2") {
      host.read("readBackValidated", ValidatedPayload(w = -2))
    }
  }

  /** Defaulted and computed one level up, in the superclass the host's shape does not have. */
  @Test fun hostToGuest_baseClassState() = withNewGuest { host, _ ->
    assertObserved("host2js/base-state/inherited", "8|5|10") {
      host.read("readBackBaseState", BaseStatePayload(own = 8))
    }
  }

  /**
   * Eight parameters past the first flag word, none of them sent by this tree's 32-parameter shape.
   * `p32` is bit 0 of the second word, so a mask packed or read against the wrong base shows up here.
   */
  @Test fun hostToGuest_wideArityDefaultsHostToGuest() = withNewGuest { host, _ ->
    assertObserved(
      "host2js/wide-arity/second-word",
      "WideDefaults(" + (0 until 40).joinToString(", ") { "p$it=$it" } + ")",
    ) {
      host.read("readBackWideDefaults", WideDefaults()).toString().replace(Regex("""\s+"""), " ")
    }
  }

  /** No skew: this host and this tree's own guest, so every field arrives and no default is consulted. */
  @Test fun control_hostToGuest_defaultedParams() = withOldGuest { host, _ ->
    assertEquals("11|df", host.read("readBackDefaultedField", DefaultedFieldPayload(a = 11, b = "df")))
    assertEquals("7|9", host.read("readBackSubset", SubsetPayload(a = 7, d = 9)))
    assertEquals("3", host.read("readBackWrappedDefault", WrappedDefaultPayload(n = 3)))
    assertEquals("4", host.read("readBackValidated", ValidatedPayload(w = 4)))
    assertEquals("8", host.read("readBackBaseState", BaseStatePayload(own = 8)))
  }

  /** The new guest ships all five parameters; this host's shape keeps the two it knows. */
  @Test fun guestToHost_subsetExtraFieldsIgnored() = withNewGuest { host, id ->
    assertEquals(
      "SubsetPayload(a=11, d=9)",
      host.evaluateOne(provide("provideSubset", id)).toString(),
    )
  }

  /** No skew: both sides have the same two parameters. */
  @Test fun control_guestToHost_subset() = withOldGuest { host, id ->
    assertEquals(
      "SubsetPayload(a=11, d=9)",
      host.evaluateOne(provide("provideSubset", id)).toString(),
    )
  }

  // ------------------------------------------------------------------------------------------
  // Defaults that reach private members. The receiver declares two parameters this shape never
  // sends, and both are decided by something no sender can see: a private companion value and a
  // private top-level function in the receiver's own file. A factory generated beside the class
  // instead of inside it would not compile against those.
  // ------------------------------------------------------------------------------------------

  @Test fun hostToGuest_privateDefaults() = withNewGuest { host, _ ->
    assertObserved("host2js/private-defaults/absent", "7|pc|4242") {
      host.read("readBackPrivateDefaults", PrivateDefaultsPayload(a = 7))
    }
  }

  @Test fun guestToHost_privateDefaultsExtraFieldsIgnored() = withNewGuest { host, id ->
    assertEquals(
      "PrivateDefaultsPayload(a=11)",
      host.evaluateOne(provide("providePrivateDefaults", id)).toString(),
    )
  }

  /** No skew: one parameter declared, one parameter sent. */
  @Test fun control_privateDefaults() = withOldGuest { host, id ->
    assertEquals("7", host.read("readBackPrivateDefaults", PrivateDefaultsPayload(a = 7)))
    assertEquals(
      "PrivateDefaultsPayload(a=11)",
      host.evaluateOne(provide("providePrivateDefaults", id)).toString(),
    )
  }

  /**
   * This host has one narrow parameter; the new guest declares three, two of them defaulted, and
   * evaluates both over the flag word — the same machinery an `Int` uses, now with the placeholder
   * each narrow type needs (`Byte`, `Short` and `Char` are all numbers on the wire and none of them
   * is an `Int` to the type checker).
   */
  @Test fun hostToGuest_narrowScalarDefaults() = withNewGuest { host, _ ->
    assertObserved("host2js/narrow-defaults/absent", "11|9|z|4") {
      host.read("readBackNarrowDefaults", NarrowDefaultsPayload(b = 11))
    }
  }

  /**
   * This host has no `extra` at all, so nothing arrives for it and the new guest's own initializer
   * stands. That is the boundary the assignment must respect: writing `undefined` because a peer is
   * older than the property would turn a version difference into corrupted state.
   */
  @Test fun hostToGuest_mutableBodyAbsentKeepsTheGuestsOwnValue() = withNewGuest { host, _ ->
    assertObserved("host2js/mutable-body/absent", "7|guest-default") {
      host.read("readBackMutableBody", MutableBodyPayload(a = 7))
    }
  }
}
