package app.cash.zipline.bridge.test

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Bridge version-skew matrix for the **new host**.
 *
 * Each test pairs this tree's host — whose converters were compiled from this tree's post-change
 * fixture shapes — with one of the two guest bundles. Own-bundle rows are the same-version control
 * and prove the harness itself; old-bundle rows are the new-host / old-guest direction. The reverse
 * direction (old host consuming the new bundle) lives in
 * `zipline-bridge-skew-old/src/commonTest/.../CrossVersionTest.kt`.
 *
 * Skew rows go through [assertObserved], which compares the observed value or thrown exception as a
 * string and prints it under `-Dskew.capture=true` instead of asserting, so the silent rows (absent
 * field, mistyped read, out-of-range ordinal) were pinned from the bridge rather than guessed.
 */
class CrossVersionTest {

  // ------------------------------------------------------------------------------------------
  // Control: new host + new guest.
  // ------------------------------------------------------------------------------------------

  @Test fun control_guestToHost() = withNewGuest { host, id ->
    assertEquals("ControlPayload(a=11, b=ctl)", host.evaluateOne(provide("provideControl", id)).toString())
    assertEquals("AddedFieldPayload(a=11, b=af, c=3.5)", host.evaluateOne(provide("provideAddedField", id)).toString())
    assertEquals("RemovedFieldPayload(a=11, b=rf)", host.evaluateOne(provide("provideRemovedField", id)).toString())
    assertEquals("RenamedFieldPayload(newName=new)", host.evaluateOne(provide("provideRenamedField", id)).toString())
    assertEquals("RetypedFieldPayload(v=ninety-nine)", host.evaluateOne(provide("provideRetypedField", id)).toString())
    assertEquals("NullabilityPayload(v=null)", host.evaluateOne(provide("provideNullability", id)).toString())
    assertEquals("HierarchyPayload(a=11, b=hp)", host.evaluateOne(provide("provideHierarchy", id)).toString())
    assertEquals("DroppedBasePayload(keep=keep)", host.evaluateOne(provide("provideDroppedBase", id)).toString())
    assertEquals("InterfacePayload(v=11)", host.evaluateOne(provide("provideInterface", id)).toString())
    assertEquals("GrowthEnumHolder(value=C)", host.evaluateOne(provide("provideGrowthEnumHolder", id)).toString())
    assertEquals("ShiftedEnumHolder(value=X)", host.evaluateOne(provide("provideShiftedEnumHolder", id)).toString())
    assertEquals("WrappedHolder(w=WrappedValue(v=42))", host.evaluateOne(provide("provideWrappedHolder", id)).toString())
    assertEquals("SealedHolder(s=LeafA(v=11))", host.evaluateOne(provide("provideSealedLeafA", id)).toString())
    assertEquals("SealedHolder(s=LeafB(w=lb))", host.evaluateOne(provide("provideSealedLeafB", id)).toString())
    assertEquals("OnlyInNew(x=11)", host.evaluateOne(provide("provideOnlyInNew", id)).toString())
  }

  /**
   * Host -> guest with no skew. Enum payloads are absent here: the host refuses to send an enum at all,
   * even to a matching guest, so those rows live in [hostToGuest_enumRefused].
   */
  @Test fun control_hostToGuest() = withNewGuest { host, _ ->
    assertEquals("11|ctl", host.read("readBackControl", ControlPayload(a = 11, b = "ctl")))
    assertEquals("11|af|3.5", host.read("readBackAddedField", AddedFieldPayload(a = 11, b = "af", c = 3.5)))
    assertEquals("11|rf", host.read("readBackRemovedField", RemovedFieldPayload(a = 11, b = "rf")))
    assertEquals("new", host.read("readBackRenamedField", RenamedFieldPayload(newName = "new")))
    assertEquals("ninety-nine", host.read("readBackRetypedField", RetypedFieldPayload(v = "ninety-nine")))
    assertEquals("null", host.read("readBackNullability", NullabilityPayload(v = null)))
    assertEquals("11|hp", host.read("readBackHierarchy", HierarchyPayload(a = 11, b = "hp")))
    assertEquals("keep", host.read("readBackDroppedBase", DroppedBasePayload(keep = "keep")))
    assertEquals("11", host.read("readBackInterface", InterfacePayload(v = 11)))
    assertEquals("WrappedValue(v=42)", host.read("readBackWrapped", WrappedHolder(w = WrappedValue(v = 42))))
    assertEquals("LeafA(v=11)", host.read("readBackSealed", SealedHolder(s = SealedShape.LeafA(v = 11))))
    assertEquals("LeafB(w=lb)", host.read("readBackSealed", SealedHolder(s = SealedShape.LeafB(w = "lb"))))
    assertEquals("11", host.read("readBackOnlyInNew", OnlyInNew(x = 11)))
  }

  // ------------------------------------------------------------------------------------------
  // Guest -> host: new host decoding the old guest's objects.
  // ------------------------------------------------------------------------------------------

  /**
   * A class the old guest sends that this host never declares. The decode is loud on both hosts:
   * the JVM entry point passes `throwOnUnsupportedType = true` (ContextJni.cpp) and Kotlin/Native
   * throws the same message, so the unknown object surfaces as a catchable mismatch naming the class.
   */
  @Test fun guestToHost_classDeletedOnHost() = withOldGuest { host, id ->
    assertObserved(
      "deleted-class/OnlyInOld",
      "throws ZiplineApiMismatchException: host bridge: no converter registered for " +
        "JS class 'OnlyInOld'; annotate the class with @WithJS2HostBridge to send it to the host",
    ) {
      host.evaluateOne(provide("provideOnlyInOld", id))
    }
  }

  /**
   * Field added on the host since the guest was built: the guest's object has no `c` property, so the
   * host converter reads a missing JS property for a non-null Double field and throws rather than
   * silently defaulting to 0.0.
   */
  @Test fun guestToHost_fieldAbsentOnGuest() = withOldGuest { host, id ->
    assertObserved(
      "field-absent/AddedFieldPayload.c",
      "throws ZiplineApiMismatchException: host bridge: app.cash.zipline.bridge.test.AddedFieldPayload.c " +
        "expects a number but the guest sent undefined",
    ) {
      host.evaluateOne(provide("provideAddedField", id))
    }
  }

  /** Field removed on the host: the guest's extra `c` is simply never read. Compatible. */
  @Test fun guestToHost_extraFieldIgnored() = withOldGuest { host, id ->
    assertEquals("RemovedFieldPayload(a=11, b=rf)", host.evaluateOne(provide("provideRemovedField", id)).toString())
  }

  /**
   * Field renamed with no alias: the host reads `newName`, the old guest's object only has `oldName`,
   * so the property is absent and the non-null String read throws naming the field and the JS kind.
   */
  @Test fun guestToHost_fieldRenamed() = withOldGuest { host, id ->
    assertObserved(
      "renamed/RenamedFieldPayload.newName",
      "throws ZiplineApiMismatchException: host bridge: app.cash.zipline.bridge.test.RenamedFieldPayload.newName " +
        "expects a string but the guest sent undefined",
    ) {
      host.evaluateOne(provide("provideRenamedField", id))
    }
  }

  /**
   * Property retyped Int -> String: the old guest sends a number where this host's converter expects a
   * string, so the tag check fires and names the kind seen.
   */
  @Test fun guestToHost_fieldRetyped() = withOldGuest { host, id ->
    assertObserved(
      "retyped/RetypedFieldPayload.v",
      "throws ZiplineApiMismatchException: host bridge: app.cash.zipline.bridge.test.RetypedFieldPayload.v " +
        "expects a string but the guest sent a number",
    ) {
      host.evaluateOne(provide("provideRetypedField", id))
    }
  }

  /** Old guest sends a non-null String into a field that is nullable now: compatible. */
  @Test fun guestToHost_nullabilityWidened() = withOldGuest { host, id ->
    assertEquals("NullabilityPayload(v=nonnull)", host.evaluateOne(provide("provideNullability", id)).toString())
  }

  /**
   * Supertype inserted on the host is NOT wire-invisible on the JVM host: its converter extracts
   * inherited properties too, so it asks the old guest's object for `baseValue`, which it never
   * sent, and the read throws naming the inherited field. The Kotlin/Native host never extracts
   * inherited body fields at all (its `extractFields` default excludes them), so it never asks and
   * decodes the object silently — a pre-existing host asymmetry in *which* fields are read, not in
   * the loud/quiet invariant. The JVM row is the loud one; the native row records the divergence.
   */
  @Test fun guestToHost_superclassInserted() = withOldGuest { host, id ->
    assertObserved(
      "superclass-inserted/HierarchyPayload.baseValue",
      "throws ZiplineApiMismatchException: host bridge: app.cash.zipline.bridge.test.HierarchyPayload.baseValue " +
        "expects a number but the guest sent undefined",
      nativeExpected = "HierarchyPayload(a=11, b=hp)",
    ) {
      host.evaluateOne(provide("provideHierarchy", id))
    }
  }

  /** Supertype removed on the host: the base's field was never on the wire either way. */
  @Test fun guestToHost_superclassRemoved() = withOldGuest { host, id ->
    assertEquals("DroppedBasePayload(keep=keep)", host.evaluateOne(provide("provideDroppedBase", id)).toString())
  }

  /** Interface gained on the host: wire-invisible. */
  @Test fun guestToHost_interfaceGained() = withOldGuest { host, id ->
    assertEquals("InterfacePayload(v=11)", host.evaluateOne(provide("provideInterface", id)).toString())
  }

  /** Enum entry appended on the host: the old guest's A/B are still in range. */
  @Test fun guestToHost_enumAppendedInRange() = withOldGuest { host, id ->
    assertEquals("GrowthEnumHolder(value=B)", host.evaluateOne(provide("provideGrowthEnumHolder", id)).toString())
  }

  /**
   * Enum entry inserted mid-chain: the old guest's X is ordinal 0, which this host maps to W.
   * No error — the wrong value crosses.
   */
  @Test fun guestToHost_enumShifted() = withOldGuest { host, id ->
    assertEquals("ShiftedEnumHolder(value=W)", host.evaluateOne(provide("provideShiftedEnumHolder", id)).toString())
  }

  /** Sealed leaf the host still has: decodes. */
  @Test fun guestToHost_sealedLeafStillPresent() = withOldGuest { host, id ->
    assertEquals("SealedHolder(s=LeafA(v=11))", host.evaluateOne(provide("provideSealedLeafA", id)).toString())
  }

  // ------------------------------------------------------------------------------------------
  // Host -> guest: new host writing objects the old guest reads.
  // ------------------------------------------------------------------------------------------

  /** Host sends a class the old guest never declared: its prototype was never registered. */
  @Test fun hostToGuest_classMissingOnGuest() = withOldGuest { host, id ->
    host.installReader(id, "readBackOnlyInNew")
    assertObserved(
      "host2js/OnlyInNew",
      "throws IllegalStateException: host2js: no registered prototype for " +
        "app.cash.zipline.bridge.test.OnlyInNew; the guest module did not call __bridgeRegister for this class",
    ) {
      host.read("readBackOnlyInNew", OnlyInNew(x = 11))
    }
  }

  /** Host sends the sealed leaf the old guest lacks; the holder itself is registered, the leaf is not. */
  @Test fun hostToGuest_sealedLeafMissingOnGuest() = withOldGuest { host, _ ->
    assertObserved(
      "host2js/SealedShape.LeafB",
      "throws IllegalStateException: host2js: no registered prototype for " +
        "app.cash.zipline.bridge.test.SealedShape.LeafB; the guest module did not call __bridgeRegister for this class",
    ) {
      host.read("readBackSealed", SealedHolder(s = SealedShape.LeafB(w = "lb")))
    }
  }

  /** Host writes a field the old guest's shape lacks: the guest ignores it. Compatible. */
  @Test fun hostToGuest_extraFieldIgnored() = withOldGuest { host, _ ->
    assertEquals("11|af", host.read("readBackAddedField", AddedFieldPayload(a = 11, b = "af", c = 3.5)))
  }

  /**
   * Host omits a field the old guest still declares: the guest reads an absent JS property, which
   * Kotlin/JS interpolation renders as `undefined` rather than folding into null.
   */
  @Test fun hostToGuest_fieldMissingOnGuest() = withOldGuest { host, _ ->
    assertEquals("11|rf|undefined", host.read("readBackRemovedField", RemovedFieldPayload(a = 11, b = "rf")))
  }

  /**
   * Renamed field, host side: the guest reads its old name from an object written with the new one.
   * Kotlin/JS compiles the single-property template `"$" + "{p.oldName}"` to a bare property return,
   * so the raw `undefined` reaches the host and decodes to null - unlike the interpolated case in
   * [hostToGuest_fieldMissingOnGuest], where stringification bakes "undefined" into the string.
   */
  @Test fun hostToGuest_fieldRenamed() = withOldGuest { host, _ ->
    assertNull(host.read("readBackRenamedField", RenamedFieldPayload(newName = "new")))
  }

  /** Retyped field, host side: the guest's Int-typed property receives a JS string. */
  @Test fun hostToGuest_fieldRetyped() = withOldGuest { host, _ ->
    assertObserved("host2js-retyped/RetypedFieldPayload.v", "ninety-nine") {
      host.read("readBackRetypedField", RetypedFieldPayload(v = "ninety-nine"))
    }
  }

  /** Nullable host value into the guest's non-null property. */
  @Test fun hostToGuest_nullIntoNonNullable() = withOldGuest { host, _ ->
    assertObserved("host2js-null/NullabilityPayload.v", "null") {
      host.read("readBackNullability", NullabilityPayload(v = null))
    }
  }

  /**
   * Host -> guest with an enum payload: refused by design, in both directions and with or without
   * skew. The JVM host's C generator and the Kotlin/Native host both throw rather than hand the
   * guest a lookalike object, so no enum ordinal ever crosses host -> guest and enum entry skew is
   * unreachable in this direction.
   */
  @Test fun hostToGuest_enumRefused() = withOldGuest { host, _ ->
    assertObserved(
      "host2js-enum/GrowthEnum.C",
      "throws IllegalStateException: host2js: app.cash.zipline.bridge.test.GrowthEnum is an enum; " +
        "the guest would only see a lookalike, not an enum instance",
    ) {
      host.read("readBackGrowthEnum", GrowthEnumHolder(value = GrowthEnum.C))
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
  @Test fun hostToGuest_shapeChangesInvisible() = withOldGuest { host, _ ->
    assertEquals("11|hp", host.read("readBackHierarchy", HierarchyPayload(a = 11, b = "hp")))
    assertEquals("keep", host.read("readBackDroppedBase", DroppedBasePayload(keep = "keep")))
    assertEquals("11", host.read("readBackInterface", InterfacePayload(v = 11)))
  }
}
