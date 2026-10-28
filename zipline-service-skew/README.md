# Service version-skew matrix

Pins what happens when the guest (live JS) and the host (JVM / Kotlin/Native) are compiled from
*different* revisions of the same Kotlin service interfaces. The JSON service path keys each method by
a hash of its **signature string** (parameter types + return type; names and defaults are absent), so
every row below is the consequence of one side calling a function id the other side does or does not
have.

One module, `zipline-service-skew`, holds both revisions of every fixture in a single compilation
(`SkewFixtures.kt`). The callee binds one shape under a wire name and the caller `take`s the other —
that pairing *is* the skew. The same source compiles to the JS guest and the host, so a given shape's
signature string is identical on both sides of the boundary.

## Running

```sh
./gradlew :zipline-service-skew:compileDevelopmentExecutableKotlinJs   # rebuild the guest after a fixture change
./gradlew :zipline-service-skew:jvmTest :zipline-service-skew:macosArm64Test
```

Re-pin a row with `-Dskew.capture=true` on the JVM: rows print `SKEW <label> -> <observed>` instead of
asserting. Leave `skewCaptureMode()` returning `false` when done.

## Verdict legend

| Mark | Meaning |
|---|---|
| ✅ | Compatible: the call completes and the value crosses correctly. |
| ❌ caught | Incompatible: a caught exception with a usable message. Never a crash. |
| ⛔ parked | Not expressible from this direction yet (see *Suspend*). |

Test names carry the direction: `control_*` is same-version, `hostToGuest_*` is the host calling a
guest-bound service, `guestToHost_*` is the guest calling a host-bound service.

## Control

Same version on both sides; proves the harness itself.

| Direction | Row | Verdict | Observed |
|---|---|---|---|
| host → guest | `control_hostToGuest` | ✅ | `pong` |
| guest → host | `control_guestToHost` | ✅ | `shared` |

## Host → guest

The host `take`s a proxy of one shape and calls the guest bound as the other. The host's compiler
resolves the function id from its own shape; the guest's registry is the gate.

| Change kind | Row | Verdict | Observed |
|---|---|---|---|
| Service deleted, called | `hostToGuest_serviceDeleted_calledThrowsClearMessage` | ❌ caught | `ZiplineApiMismatchException: no such service (service closed?)` |
| Service deleted, never called | `hostToGuest_serviceDeleted_neverCalled_isInert` | ✅ | `take` is lazy; the live path is unaffected |
| Service added, never called | `hostToGuest_serviceAdded_neverCalled_isInert` | ✅ | extra guest services are inert |
| Method added, unused by old caller | `hostToGuest_methodAdded_unusedIsFine` | ✅ | `shared` still works |
| Method deleted, called | `hostToGuest_methodDeleted_calledThrowsClearMessage` | ❌ caught | `no such method (incompatible API versions?)` |
| Method deleted, unused by new caller | `hostToGuest_methodDeleted_unusedIsFine` | ✅ | `shared` still works |
| Method removed in new version, old caller | `hostToGuest_methodRemovedInNewVersion_oldCallerThrowsClearMessage` | ❌ caught | `no such method` |
| Required param added | `hostToGuest_paramAddedRequired_mismatch` | ❌ caught | `no such method` |
| Method added with a default (`g`) | `hostToGuest_methodAddedWithDefault_mismatch` | ❌ caught | `g` is V2-only; the default is irrelevant |
| **Defaulted param added to an existing method (`e`), new caller** | `hostToGuest_defaultedParamAddedToExistingMethod_newCaller_mismatch` | ❌ caught | the interface-evolution gotcha — see below |
| **Defaulted param added to an existing method (`e`), old caller** | `hostToGuest_defaultedParamAddedToExistingMethod_oldCaller_mismatch` | ❌ caught | mirror of the row above |
| Param deleted | `hostToGuest_paramDeleted_mismatch` | ❌ caught | `no such method` |
| Default added to an existing param | `hostToGuest_defaultAddedToExistingParam_compatible` | ✅ | `h:z` — old caller supplies the arg explicitly |
| Default removed from a param | `hostToGuest_defaultRemovedFromParam_compatible` | ✅ | `h:d` — the caller's default crosses to a callee that has none |
| Param renamed | `hostToGuest_paramRenamed_compatible` | ✅ | `k:y` — param names are wire-invisible |
| Method default differs, old caller | `hostToGuest_methodDefaultDiffers_oldCallerWins` | ✅ | `d:x:V1` — the caller's default wins |
| Method default differs, new caller | `hostToGuest_methodDefaultDiffers_newCallerWins` | ✅ | `d:x:V2` — the caller's default wins |
| Param retyped to an unrelated type | `hostToGuest_paramRetypedUnrelated_mismatch` | ❌ caught | `no such method` |
| Param widened to a supertype | `hostToGuest_paramWidenedToSupertype_mismatch` | ❌ caught | the signature string changes even though the DTO could decode |
| Return type changed | `hostToGuest_returnTypeChanged_mismatch` | ❌ caught | `no such method` |
| Param nullability changed | `hostToGuest_paramNullabilityChanged_mismatch` | ❌ caught | `no such method` |
| Return nullability changed | `hostToGuest_returnNullabilityChanged_mismatch` | ❌ caught | `no such method` |
| Non-suspend caller / suspend callee | `hostToGuest_nonSuspendCaller_suspendCallee_mismatch` | ❌ caught | `no such method` |
| Suspend caller / non-suspend callee | `hostToGuest_suspendCaller_nonSuspendCallee_mismatch` | ❌ caught | `no such method` |
| `val` vs `var` property | `hostToGuest_propertyValVsVar_getterMatchesSetterMismatch` | ✅ / ❌ caught | getter matches; setter mismatches |
| Callee throws | `hostToGuest_calleeThrows_messagePreservedNoCrash` | ❌ caught | `ZiplineException: boom`; the engine still serves the next call |
| `Long` across the 2^53 boundary | `hostToGuest_longBoundaryCrossesExactly` | ✅ | exact on both sides (number below, string above) |

## Guest → host

The host `bind`s one shape; the guest `take`s the other through a `@JsExport` entry point and calls
across the boundary. This is the production-critical direction for Compose Live: a freshly shipped JS
bundle calling a stale host. Every direction-A shape above that the JS boundary can marshal has a twin
here.

| Change kind | Row | Verdict | Observed |
|---|---|---|---|
| Absent host service | `guestToHost_serviceAbsent_throwsClearMessage` | ❌ caught | `JsException: no such service (service closed?)` |
| Method added, old host lacks it | `guestToHost_methodAdded_mismatch` | ❌ caught | `JsException: no such method` |
| Method removed in new version, old caller | `guestToHost_methodRemovedInNewVersion_oldCaller_mismatch` | ❌ caught | `no such method` |
| Method still present, old caller | `guestToHost_methodStillPresent_oldCaller_compatible` | ✅ | `shared` |
| Required param added | `guestToHost_paramAddedRequired_mismatch` | ❌ caught | `no such method` |
| Method added with a default (`g`) | `guestToHost_methodAddedWithDefault_mismatch` | ❌ caught | `g` is V2-only |
| **Defaulted param added to an existing method (`e`), new caller** | `guestToHost_defaultedParamAddedToExistingMethod_newCaller_mismatch` | ❌ caught | the evolution gotcha from the guest side |
| **Defaulted param added to an existing method (`e`), old caller** | `guestToHost_defaultedParamAddedToExistingMethod_oldCaller_mismatch` | ❌ caught | mirror |
| Param deleted | `guestToHost_paramDeleted_mismatch` | ❌ caught | `no such method` |
| Default added to an existing param | `guestToHost_defaultAddedToExistingParam_compatible` | ✅ | `h:z` |
| Default removed from a param | `guestToHost_defaultRemovedFromParam_compatible` | ✅ | `h:d` — the guest's default crosses |
| Defaulted param supplied explicitly by the new caller | `guestToHost_defaultedParamSuppliedExplicitly_compatible` | ✅ | `h:z` — the caller's default is unused; the explicit arg crosses |
| Param renamed | `guestToHost_paramRenamed_compatible` | ✅ | `k:y` |
| Method default differs, new caller | `guestToHost_methodDefaultDiffers_newCallerWins` | ✅ | `d:x:V2` |
| Method default differs, old caller | `guestToHost_methodDefaultDiffers_oldCallerWins` | ✅ | `d:x:V1` |
| Param retyped to an unrelated type | `guestToHost_paramRetypedUnrelated_mismatch` | ❌ caught | `no such method` |
| Param widened to a supertype | `guestToHost_paramWidenedToSupertype_mismatch` | ❌ caught | `no such method` |
| Return type changed | `guestToHost_returnTypeChanged_mismatch` | ❌ caught | `no such method` |
| Param nullability changed | `guestToHost_paramNullabilityChanged_mismatch` | ❌ caught | `no such method` |
| Return nullability changed | `guestToHost_returnNullabilityChanged_mismatch` | ❌ caught | `no such method` |
| `val` vs `var` property | `guestToHost_propertyValVsVar_getterMatchesSetterMismatch` | ✅ / ❌ caught | getter matches; setter mismatches |
| `Long` across the 2^53 boundary | `guestToHost_longBoundaryCrossesExactly` | ✅ | exact on both sides |
| Host throws during a guest call | `guestToHost_hostThrows_messagePreserved_nativeEnginePoisoned` | ❌ caught | message preserved; see *Native engine poisoning* |

## The interface-evolution gotcha

Adding a parameter with a default to a method that already exists is **source**-compatible for Kotlin
callers but **not wire**-compatible. The added parameter changes the arity, the signature string, and
therefore the function id (`zipline-kotlin-plugin/.../ir.kt`: the signature is built from parameter
*types*). Omitting the argument at the call site materializes the caller's default on the wire; the old
callee still has no function with that id and throws `no such method`.

This is the case people expect defaults to rescue. They do not. The `e` fixtures pin it in both
directions and both caller versions. Making default-parameter addition a compatible change is a
follow-up (CLV-121 T2).

## Whose default wins

A service-method default is resolved by the **caller's** compiler at the call site (the `$default`
synthetic) and sent on the wire as a materialized argument. The callee's own default is never consulted.
`DefaultsV1`/`DefaultsV2` carry *different* defaults (`"V1"` vs `"V2"`) under an identical signature,
so the returned string reveals whose default won — always the caller's.

## Suspend

`hostToGuest_nonSuspendCaller_suspendCallee_mismatch` and
`hostToGuest_suspendCaller_nonSuspendCallee_mismatch` are covered host-as-caller only. The
guest-as-caller twin is ⛔ parked: `@JsExport` cannot synchronously return a suspend result, so those
rows need promise/callback plumbing across the boundary. The decision on how suspend crosses is open.

## Native engine poisoning

A host-side throw during a guest→host call surfaces its message on both hosts, but only the JVM engine
stays usable afterwards. On Kotlin/Native the inbound callback (`outboundChannelCallCallback` in
`zipline/src/nativeMain/.../JsEngine.kt`) lets the host's Kotlin exception propagate across the
`staticCFunction` boundary into C++ instead of converting it into an error envelope. The exception is
still catchable at the call site, but the engine is poisoned: every subsequent call re-throws the same
`JsException`. The JVM's JNI path converts the throw properly and keeps serving. That asymmetry is a
defect in the Kotlin/Native inbound path, not a contract we want;
`guestToHost_hostThrows_messagePreserved_nativeEnginePoisoned` pins it so the day it is fixed the row
fails and gets updated.

## Why both directions are mirrored per shape

The two directions are **not** guaranteed to share a transport or mechanism: one may move to a direct
bridge while the other stays JSON, as the Compose bridges diverged. So the skew decision is not shared
code across directions, and each direction carries its own per-shape matrix rather than a collapsed
outcome-class sample. Guest→host is the production-critical direction for Compose Live and carries the
fuller matrix.
