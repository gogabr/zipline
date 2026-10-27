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
    assertEquals("WrappedValue(v=42)", host.read("readBackWrapped", WrappedHolder(w = WrappedValue(v = 42))))
    assertEquals("LeafA(v=11)", host.read("readBackSealed", SealedHolder(s = SealedShape.LeafA(v = 11))))
    assertEquals("11", host.read("readBackOnlyInOld", OnlyInOld(x = 11)))
  }

  // ------------------------------------------------------------------------------------------
  // Guest -> host: old host decoding the new guest's objects.
  // ------------------------------------------------------------------------------------------

  /**
   * A class the new guest sends that this host never declares. The JVM top-level decode is quiet:
   * the entry point passes `throwOnUnsupportedType = false` (ContextJni.cpp), so the unknown
   * object arrives as `null`. Kotlin/Native has no such flag and throws the loud
   * "no converter registered" error instead. The loud throw also fires on JVM for a *field*
   * value, where the declared type makes silence wrong (see [guestToHost_sealedLeafAddedOnGuest]).
   */
  @Test fun guestToHost_classAddedOnGuest() = withNewGuest { host, id ->
    assertObserved(
      "added-class/OnlyInNew",
      "null",
      nativeExpected =
        "throws IllegalStateException: host bridge: no converter registered for JS class 'OnlyInNew'; " +
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
   * property.
   */
  @Test fun guestToHost_fieldAbsentOnGuest() = withNewGuest { host, id ->
    assertObserved("field-absent/RemovedFieldPayload.c", "RemovedFieldPayload(a=11, b=rf, c=0.0)") {
      host.evaluateOne(provide("provideRemovedField", id))
    }
  }

  // Field renamed with no alias, property retyped Int -> String, and null sent into a non-null String
  // are NOT assertable here: the decoder aborts the process instead of throwing catchably. See
  // `SkewAbortForkTest` (jvmTest), which pins the SIGABRT and the Hermes message from a forked JVM.

  /**
   * Supertype added in the guest: field extraction is own-class-only, so the wire shape never
   * changed and this host decodes the object exactly.
   */
  @Test fun guestToHost_superclassAddedOnGuest() = withNewGuest { host, id ->
    assertEquals("HierarchyPayload(a=11, b=hp)", host.evaluateOne(provide("provideHierarchy", id)).toString())
  }

  // Supertype removed in the guest is NOT wire-neutral: this host's converter still extracts the
  // base's `inherited` property, which the new guest never sends, so the decode aborts. See
  // `SkewAbortForkTest.superclassRemovedOnGuest_abortsProcess`.

  /** Interface added in the guest: wire-invisible. */
  @Test fun guestToHost_interfaceAddedOnGuest() = withNewGuest { host, id ->
    assertEquals("InterfacePayload(v=11)", host.evaluateOne(provide("provideInterface", id)).toString())
  }

  /**
   * Enum entry appended in the guest: ordinal 2 has no constant in this host's enum. The JVM
   * converter decodes the unknown ordinal to null instead of firing its range check, and the
   * holder's non-null constructor parameter turns that into a caught NPE. Kotlin/Native fires
   * the generated range check and throws the explicit out-of-range error.
   */
  @Test fun guestToHost_enumOutOfRange() = withNewGuest { host, id ->
    assertObserved(
      "enum-out-of-range/GrowthEnum.C",
      "throws NullPointerException: Parameter specified as non-null is null: " +
        "method app.cash.zipline.bridge.test.GrowthEnumHolder.<init>, parameter value",
      nativeExpected =
        "throws IllegalStateException: host bridge: ordinal 2 is out of range for GrowthEnum",
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
      "throws IllegalStateException: host bridge: no converter registered for JS class 'LeafB'; " +
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
   * Host omits a field the new guest declares: the guest reads an absent JS property, which
   * Kotlin/JS interpolation renders as `undefined` rather than folding into null.
   */
  @Test fun hostToGuest_fieldMissingOnGuest() = withNewGuest { host, _ ->
    assertEquals("11|af|undefined", host.read("readBackAddedField", AddedFieldPayload(a = 11, b = "af")))
  }

  /** Host writes a field the new guest's shape lacks: the guest ignores it. Compatible. */
  @Test fun hostToGuest_extraFieldIgnored() = withNewGuest { host, _ ->
    assertEquals("11|rf", host.read("readBackRemovedField", RemovedFieldPayload(a = 11, b = "rf", c = 3.5)))
  }

  /**
   * Renamed field, host side: the guest reads its new name from an object written with the old one.
   * Kotlin/JS compiles the single-property template `"$" + "{p.newName}"` to a bare property return,
   * so the raw `undefined` reaches the host and decodes to null - unlike the interpolated case in
   * [hostToGuest_fieldMissingOnGuest], where stringification bakes "undefined" into the string.
   */
  @Test fun hostToGuest_fieldRenamed() = withNewGuest { host, _ ->
    assertNull(host.read("readBackRenamedField", RenamedFieldPayload(oldName = "old")))
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
}
