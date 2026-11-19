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
   * Supertype inserted on the host: the base's `baseValue` carries a default, so the absent
   * inherited field is now tolerated on BOTH hosts — the JVM host reads it as defaulted and the
   * Kotlin/Native host never extracted it anyway. The old loud-JVM/quiet-native divergence
   * collapses: both decode the object with the host's own default.
   */
  @Test fun guestToHost_superclassInserted() = withOldGuest { host, id ->
    assertEquals("HierarchyPayload(a=11, b=hp)", host.evaluateOne(provide("provideHierarchy", id)).toString())
  }

  /** Supertype removed on the host: the base's field was never on the wire either way. */
  @Test fun guestToHost_superclassRemoved() = withOldGuest { host, id ->
    assertEquals("DroppedBasePayload(keep=keep)", host.evaluateOne(provide("provideDroppedBase", id)).toString())
  }

  /**
   * Eight defaulted parameters added past the 32nd: the flags for them do not fit in the first mask
   * word, so this row fails if a bridge assumes one word or shifts by the raw parameter index. Both
   * hosts take all eight defaults and keep the 32 values the guest sent.
   *
   * The Kotlin/Native host reaches those defaults through `bridgeCreate`, the companion factory the
   * plugin generates for it: the absent parameters are flagged from what arrived on the wire, and the
   * factory evaluates the host's own default expressions. On the JVM the same flags go to the JVM's
   * synthetic masked constructor, which is why this row works there without a factory at all.
   */
  @Test fun guestToHost_wideArityDefaults() = withOldGuest { host, id ->
    assertEquals(
      "WideDefaults(" + (0 until 40).joinToString(", ") { "p$it=$it" } + ")",
      host.evaluateOne(provide("provideWideDefaults", id)).toString().replace(Regex("""\s+"""), " "),
    )
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
   * Host omits a field the guest's constructor requires. It used to arrive as an absent JS property
   * that Kotlin/JS interpolation rendered as `undefined`, so the payload crossed and the wrong value
   * was simply used. Now the guest builds the payload through its own factory, which is the first place
   * that knows the parameter is required — so it says so, with the field's name, before anything can
   * read the hole.
   */
  @Test fun hostToGuest_fieldMissingOnGuest() = withOldGuest { host, _ ->
    assertObserved(
      "host2js-field-missing/RemovedFieldPayload.c",
      "throws IllegalStateException: host2js: app.cash.zipline.bridge.test.RemovedFieldPayload.c " +
        "was not sent by the host",
    ) {
      host.read("readBackRemovedField", RemovedFieldPayload(a = 11, b = "rf"))
    }
  }

  /**
   * Renamed field, host side: the guest reads its old name from an object written with the new one.
   * Kotlin/JS compiles the single-property template `"$" + "{p.oldName}"` to a bare property return,
   * so the raw `undefined` reaches the host and decodes to null - unlike the interpolated case in
   * [hostToGuest_fieldMissingOnGuest], where stringification bakes "undefined" into the string.
   */
  @Test fun hostToGuest_fieldRenamed() = withOldGuest { host, _ ->
    assertObserved(
      "host2js-field-renamed/RenamedFieldPayload.oldName",
      "throws IllegalStateException: host2js: app.cash.zipline.bridge.test.RenamedFieldPayload.oldName " +
        "was not sent by the host",
    ) {
      host.read("readBackRenamedField", RenamedFieldPayload(newName = "new"))
    }
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

  // ------------------------------------------------------------------------------------------
  // Defaulted constructor parameters, seen from the other side: this host declares the extra
  // parameters and the old guest does not know them. The interesting property is not that the old
  // guest ignores them — it is that the fields it does declare still arrive intact, so an extra
  // parameter on the host is additive rather than corrupting.
  // ------------------------------------------------------------------------------------------

  @Test fun hostToGuest_addedDefaultedFieldIgnoredByOldGuest() = withOldGuest { host, _ ->
    assertObserved("host2js/defaulted-field/ignored", "11|df") {
      host.read("readBackDefaultedField", DefaultedFieldPayload(a = 11, b = "df", added = 99))
    }
  }

  @Test fun hostToGuest_subsetFieldsIgnoredByOldGuest() = withOldGuest { host, _ ->
    assertObserved("host2js/subset/ignored", "7|9") {
      host.read("readBackSubset", SubsetPayload(a = 7, c = 3.5, d = 9, twin = 55))
    }
  }

  @Test fun hostToGuest_defaultedValueClassIgnoredByOldGuest() = withOldGuest { host, _ ->
    assertObserved("host2js/wrapped-default/ignored", "3") {
      host.read("readBackWrappedDefault", WrappedDefaultPayload(n = 3, w = WrappedValue(v = 42)))
    }
  }

  /**
   * The old guest declares no initializer, so nothing judges the value on arrival. The complementary
   * half — a host shipping a value that its own shape tolerates but the guest refuses — cannot be
   * written from this tree: here `ValidatedPayload` validates on the host, so the bad value never gets
   * built. That row lives in `zipline-bridge-skew-old`.
   */
  @Test fun hostToGuest_validationIsAbsentOnOldGuest() = withOldGuest { host, _ ->
    assertObserved("host2js/validated/not-checked-by-old-guest", "4") {
      host.read("readBackValidated", ValidatedPayload(w = 4))
    }
  }

  @Test fun hostToGuest_baseClassIgnoredByOldGuest() = withOldGuest { host, _ ->
    assertObserved("host2js/base-state/ignored", "8") {
      host.read("readBackBaseState", BaseStatePayload(own = 8))
    }
  }

  /** The reverse of the wide row: 40 fields sent to a 32-field guest, whose 32 must still be right. */
  @Test fun hostToGuest_wideArityExtraFieldsIgnoredByOldGuest() = withOldGuest { host, _ ->
    assertObserved(
      "host2js/wide-arity/old-guest-32",
      "WideDefaults(" + (0 until 32).joinToString(", ") { "p$it=$it" } + ")",
    ) {
      host.read("readBackWideDefaults", WideDefaults()).toString().replace(Regex("""\s+"""), " ")
    }
  }

  /** No skew: every defaulted parameter is sent by this host and read by this guest. */
  @Test fun control_hostToGuest_defaultedParams() = withNewGuest { host, _ ->
    assertEquals("11|df|99", host.read("readBackDefaultedField", DefaultedFieldPayload(a = 11, b = "df", added = 99)))
    assertEquals("7|b|2.5|9|9", host.read("readBackSubset", SubsetPayload(a = 7, d = 9)))
    assertEquals("3|42", host.read("readBackWrappedDefault", WrappedDefaultPayload(n = 3, w = WrappedValue(v = 42))))
    assertEquals("4|12", host.read("readBackValidated", ValidatedPayload(w = 4)))
    assertEquals("8|5|10", host.read("readBackBaseState", BaseStatePayload(own = 8)))
  }

  // ------------------------------------------------------------------------------------------
  // And the same defaulting, in the other direction: the host is the one that builds the object,
  // so it is the host's decoder that has to evaluate a chained default over a field that arrived.
  // The old guest's `SubsetPayload` has only `a` and `d`, which is what makes `b`, `c` and `twin`
  // absent on this side.
  // ------------------------------------------------------------------------------------------

  /** `a` and `d` arrive; `b`, `c` and `twin = c.toInt() + a` are this host's to work out. */
  @Test fun guestToHost_subsetDefaultsIncludingChained() = withOldGuest { host, id ->
    assertObserved(
      "js2host/subset/defaults-and-chained",
      "SubsetPayload(a=11, b=b, c=2.5, d=9, twin=13)",
    ) { host.evaluateOne(provide("provideSubset", id)) }
  }

  /** No skew: the new guest sends all five, so no default is consulted on either side. */
  @Test fun control_guestToHost_subset() = withNewGuest { host, id ->
    assertEquals(
      "SubsetPayload(a=11, b=bb, c=3.5, d=9, twin=55)",
      host.evaluateOne(provide("provideSubset", id)).toString(),
    )
  }

  /**
   * The Kotlin/Native half of the visibility claim: the old guest sends one parameter and the HOST
   * decodes three, so `tag` and `magic` are evaluated by the host's own generated factory — over a
   * private companion value and a private top-level function. The JVM host runs its masked
   * constructor and the native host runs `bridgeCreate`; both have to see what the class sees.
   */
  @Test fun guestToHost_privateDefaultsEvaluatedByHost() = withOldGuest { host, id ->
    assertObserved(
      "js2host/private-defaults/absent",
      "PrivateDefaultsPayload(a=11, tag=pc, magic=4242)",
    ) { host.evaluateOne(provide("providePrivateDefaults", id)) }
  }

  /** No skew: this guest sends all three, so nothing is left to decide. */
  @Test fun control_guestToHost_privateDefaults() = withNewGuest { host, id ->
    assertEquals(
      "PrivateDefaultsPayload(a=11, tag=sent, magic=1)",
      host.evaluateOne(provide("providePrivateDefaults", id)).toString(),
    )
  }

  @Test fun hostToGuest_privateDefaultsIgnoredByOldGuest() = withOldGuest { host, _ ->
    assertObserved("host2js/private-defaults/ignored", "11") {
      host.read("readBackPrivateDefaults", PrivateDefaultsPayload(a = 11, tag = "sent", magic = 1))
    }
  }

  // ------------------------------------------------------------------------------------------
  // Mutable body properties, which are the fields a constructor cannot receive: they are sent, and
  // the receiver has to assign them itself once its own constructor has run. Absence is the half
  // that needs two trees, and this tree supplies the presence half — a host that owns the property.
  // ------------------------------------------------------------------------------------------

  /** `extra` crosses and lands on top of a constructor that ran: `a` arrived, `extra` was assigned. */
  @Test fun control_hostToGuest_mutableBodyPresent() = withNewGuest { host, _ ->
    assertEquals(
      "7|from-host",
      host.read("readBackMutableBody", MutableBodyPayload(a = 7).apply { extra = "from-host" }),
    )
  }

  /**
   * The same rule, on the host: this host declares `extra`, the old guest never sends it, and the
   * host's own initializer stands. Both directions of a mutable body field are therefore presence
   * gated and never write over a receiver's own value with nothing — which is the parity the guest
   * side needed, and why the fix went to the guest factory rather than to a special case.
   */
  @Test fun guestToHost_mutableBodyAbsentKeepsTheHostsOwnValue() = withOldGuest { host, id ->
    assertObserved("js2host/mutable-body/absent", "MutableBodyPayload(a=11, extra=guest-default)") {
      host.evaluateOne(provide("provideMutableBody", id))
    }
  }

  /**
   * `b` arrives; `s`, `c` and `u` are this host's to evaluate over the flag word. `u` is the one
   * that needed a placeholder of its own: an unsigned type has no IR constant, because it is an
   * inline class, so the generated call has to build `UInt(0)` -- an expression that must compile
   * even though the flag word guarantees it is never read.
   */
  @Test fun guestToHost_narrowScalarDefaults() = withOldGuest { host, id ->
    assertObserved("js2host/narrow-defaults/absent", "NarrowDefaultsPayload(b=11, s=9, c=z, u=4)") {
      host.evaluateOne(provide("provideNarrowDefaults", id))
    }
  }

  /** No skew: the guest sends all four, so nothing is consulted on either side. */
  @Test fun control_guestToHost_narrowScalars() = withNewGuest { host, id ->
    assertEquals(
      "NarrowDefaultsPayload(b=11, s=5, c=q, u=6)",
      host.evaluateOne(provide("provideNarrowDefaults", id)).toString(),
    )
  }

  /** The old guest declares only `b`, so the two extra narrow scalars are ignored as any extra is. */
  @Test fun hostToGuest_narrowScalarsIgnoredByOldGuest() = withOldGuest { host, _ ->
    assertObserved("host2js/narrow-defaults/ignored", "7") {
      host.read("readBackNarrowDefaults", NarrowDefaultsPayload(b = 7, s = 5, c = 'q', u = 6u))
    }
  }

  /** No skew: all four cross, each narrowed back to the type the property declares. */
  @Test fun control_hostToGuest_narrowScalars() = withNewGuest { host, _ ->
    assertEquals(
      "7|5|q|6",
      host.read("readBackNarrowDefaults", NarrowDefaultsPayload(b = 7, s = 5, c = 'q', u = 6u)),
    )
  }

  /** The old guest declares no `extra`, so the value is ignored — and ignored quietly, as an extra field. */
  @Test fun hostToGuest_mutableBodyIgnoredByOldGuest() = withOldGuest { host, _ ->
    assertObserved("host2js/mutable-body/ignored", "7") {
      host.read("readBackMutableBody", MutableBodyPayload(a = 7).apply { extra = "from-host" })
    }
  }
}
