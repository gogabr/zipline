# Bridge version-skew matrix

Pins what happens when the guest (live JS) and the host (JVM / Kotlin/Native) are compiled from
*different* revisions of the same Kotlin source. The direct bridge keys classes by dotted FQN only and
carries no field names on the wire, so every row below is the consequence of one side reading the
other's object through its own compiled field set.

Two modules form the harness:

- `zipline-bridge-skew-old` — the frozen *old* snapshot. Its host loads the *new* guest bundle:
  the **old-host / new-guest** direction.
- `zipline-bridge-skew-new` — the *new* snapshot. Its host loads the *old* guest bundle: the
  **new-host / old-guest** direction.

Each module also runs its own bundle as a same-version control, which proves the harness itself.
The two trees share one fixture FQN (`app.cash.zipline.bridge.test.*`) but never both put it on the
same JVM classpath: a module's test classpath holds only its own compiled classes plus the other
tree's base64-embedded guest data.

## Running

```sh
./gradlew :zipline-bridge-skew-new:jvmTest :zipline-bridge-skew-old:jvmTest
./gradlew :zipline-bridge-skew-new:macosArm64Test :zipline-bridge-skew-old:macosArm64Test
```

Re-pin a silent row with `-Dskew.capture=true`: rows print `SKEW <label> -> <observed>` instead of
asserting. Always `rm -rf <module>/build` before trusting a red run — Kotlin/JS incremental
compilation can emit a bundle without the plugin's IR injections.

## Verdict legend

| Mark | Meaning |
|---|---|
| ✅ | Compatible: values cross correctly. |
| ✅ lossy | Compatible; one side's extra field is silently dropped. |
| ⚠️ | Compatible shape, but a value is silently wrong/absent (default, `undefined`). |
| ❌ | Incompatible: a caught `ZiplineApiMismatchException` with a usable message. |
| ❌ silent wrong | Incompatible with **no** error: the wrong value crosses. |
| ⛔ blocked | Not expressible with the current generator. |

The bridge invariant: **load time is silent, use time is loud, the process never aborts.** A
missing class, field, or mistyped read surfaces as a catchable
`app.cash.zipline.ZiplineApiMismatchException` on every host. The old `💀 abort` verdict (a
`jsi::JSError` reaching `std::terminate`, exit 134) is gone: the generated C reads body fields
through checked accessors that throw the mismatch instead of letting a Hermes error escape into JNI.

## Guest → host

The host's generated converter reads the guest's JS object. "Direction" names the guest bundle and
the host tree. JVM observed values are from `jvmTest`; the Native column is `macosArm64Test`.

| Change kind | Direction | Verdict | JVM observed | Kotlin/Native observed | Follow-up |
|---|---|---|---|---|---|
| Class added on guest (`OnlyInNew`) | new guest → old host | ❌ | `ZiplineApiMismatchException: host bridge: no converter registered for JS class 'OnlyInNew'; annotate the class with @WithJS2HostBridge to send it to the host` | same | — |
| Class deleted on host (`OnlyInOld`) | old guest → new host | ❌ | same loud throw for `OnlyInOld` | same | — |
| Field added on guest (`AddedFieldPayload.c`) | new guest → old host | ✅ lossy | `AddedFieldPayload(a=11, b=af)` — `c` dropped | same | — |
| Field added on host (`AddedFieldPayload.c`) | old guest → new host | ❌ | `ZiplineApiMismatchException: host bridge: ...AddedFieldPayload.c expects a number but the guest sent undefined` | same | — |
| Field removed on guest (`RemovedFieldPayload.c`) | new guest → old host | ❌ | `ZiplineApiMismatchException: host bridge: ...RemovedFieldPayload.c expects a number but the guest sent undefined` | same | — |
| Field removed on host (`RemovedFieldPayload.c`) | old guest → new host | ✅ | `RemovedFieldPayload(a=11, b=rf)` | same | — |
| Field renamed, no alias | both | ❌ | `ZiplineApiMismatchException: host bridge: ...RenamedFieldPayload.<name> expects a string but the guest sent undefined` | same | — |
| Property retyped Int→String | new guest → old host | ❌ | `ZiplineApiMismatchException: host bridge: ...RetypedFieldPayload.v expects a string but the guest sent a number` | same | — |
| Property retyped Int→String | old guest → new host | ❌ | `ZiplineApiMismatchException: host bridge: ...RetypedFieldPayload.v expects a number but the guest sent a string` | same | — |
| `null` into non-null String | old guest → new host | ❌ | `ZiplineApiMismatchException: host bridge: ...NullabilityPayload.v expects a string but the guest sent null` | same | — |
| Nullability widened (non-null → nullable) | old guest → new host | ✅ | `NullabilityPayload(v=nonnull)` | same | — |
| Superclass added on guest | new guest → old host | ✅ | `HierarchyPayload(a=11, b=hp)` — old host extracts own fields only | same | — |
| Superclass inserted on host | old guest → new host | ✅ | `HierarchyPayload(a=11, b=hp)` — the inherited `baseValue` is defaulted, so the JVM host tolerates its absence (masked ctor) | `HierarchyPayload(a=11, b=hp)` — native never extracts inherited body fields | — |
| Superclass removed on guest | new guest → old host | ❌ / ✅ | `ZiplineApiMismatchException: ...DroppedBasePayload.inherited expects a number but the guest sent undefined` | `DroppedBasePayload(keep=keep)` — native never asks | divergence #1 |
| Superclass removed on host | old guest → new host | ✅ | `DroppedBasePayload(keep=keep)` | same | — |
| Interface added on guest | new guest → old host | ✅ | `InterfacePayload(v=11)` — wire-invisible | same | — |
| Interface gained on host | old guest → new host | ✅ | `InterfacePayload(v=11)` | same | — |
| Enum appended on guest (`GrowthEnum.C`) | new guest → old host | ❌ | `ZiplineApiMismatchException: host bridge: ordinal 2 is out of range for ...GrowthEnum` | same | — |
| Enum appended on host | old guest → new host | ✅ | `GrowthEnumHolder(value=B)` | same | — |
| Enum shifted on guest (`W` inserted) | new guest → old host | ❌ silent wrong | `ShiftedEnumHolder(value=Y)` — the guest sent `X` | same | T1 |
| Enum shifted on host (`W` inserted) | old guest → new host | ❌ silent wrong | `ShiftedEnumHolder(value=W)` — the guest sent `X` | same | T1 |
| Sealed leaf added on guest (`LeafB`) | new guest → old host | ❌ | `ZiplineApiMismatchException: host bridge: no converter registered for JS class 'LeafB'; annotate the class with @WithJS2HostBridge to send it to the host` | same | — |
| Sealed leaf present in both | both | ✅ | `SealedHolder(s=LeafA(v=11))` | same | — |
| Value class, Int-backed | both | ✅ | `WrappedHolder(w=WrappedValue(v=42))` | same | — |
| Value class, reference-backed | both | ⛔ blocked | host bridge fails to compile: `cannot cast from type 'jdouble' to pointer type 'jstring'` | n/a | T5 |

## Host → guest

The host builds an object, converts it to JS, and the guest's `readBack*` reports what its own shape
sees. The guest's prototype registry is the gate on the receiving side.

| Change kind | Direction | Verdict | JVM observed | Kotlin/Native observed | Follow-up |
|---|---|---|---|---|---|
| Class deleted on guest (`OnlyInOld`) | old host → new guest | ❌ caught | `IllegalStateException: host2js: no registered prototype for app.cash.zipline.bridge.test.OnlyInOld; the guest module did not call __bridgeRegister for this class` | same message, wrapped as `JsException: callGuestFunction: cannot convert argument 0 ...` (folded) | T1 |
| Class missing on guest (`OnlyInNew`) | new host → old guest | ❌ caught | same for `OnlyInNew` | same wrapped | T1 |
| Field added on host | new host → old guest | ✅ | `11\|af` — guest ignores `c` | same | — |
| Field added on guest | old host → new guest | ⚠️ | `11\|af\|undefined` — guest reads an absent JS property | same | — |
| Field removed on guest | old host → new guest | ✅ | `11\|rf` — the host's extra `c` is ignored by the guest's shape | same | — |
| Field removed on host | new host → old guest | ⚠️ | `11\|rf\|undefined` — the old guest still declares `c` and reads an absent JS property | same | — |
| Field renamed, no alias | both | ⚠️ | `null` — Kotlin/JS compiles the single-property template to a bare property return, so raw `undefined` crosses back as null | same | T3 |
| Property retyped Int→String | old host → new guest | ⚠️ divergent | `99` | `99.0` — native renders the JS double with its fractional part | — |
| `null` into guest non-null | new host → old guest | ✅ | `null` | same | — |
| Enum payload | both | ❌ refused by design | `IllegalStateException: host2js: app.cash.zipline.bridge.test.GrowthEnum is an enum; the guest would only see a lookalike, not an enum instance` | same (wrapped, folded) | — |
| Hierarchy / interface change | both | ✅ | values cross unchanged | same | — |
| Defaulted ctor param missing on host (`DefaultedCtorPayload.b`) | old host → new guest | ✅ | `11\|dflt` — the host probes the guest's `create_<fqn>` factory and runs the guest's real ctor | same | — |
| Defaulted body `var` missing on host (`DefaultedBodyVar.extra`) | old host → new guest | ✅ | `11\|bx` — the factory's ctor initializes `extra`, then the host overlays its own `a` | same | — |
| Non-constant default missing on host (`DefaultedNonConstPayload.b = "nc-$a"`) | old host → new guest | ✅ | `11\|nc-11` — the guest's real ctor computes the default; no host could supply it | same | — |
| Guest's own `new` of the non-constant-default class | guest-side | ✅ | `nc-11` — the real ctor computes `b` from `a` | same | — |

The host→guest refusals (`no registered prototype`, `is an enum`) stay `IllegalStateException`, not
`ZiplineApiMismatchException`: they are programmer/setup errors (the guest never registered the class,
or the host tried to send a type the bridge refuses by design), not version skew. The guest→host
reads above are the skew path and use the mismatch type.

## JVM vs Kotlin/Native divergence

Two behavior splits the matrix pins with `assertObserved(..., nativeExpected = ...)`. These are real
host-implementation differences, not test artifacts:

1. **Non-constructor `val` body fields.** The gate is `extractFields`'s `includeValBodyFields`
   flag, not inheritance: the JVM C generator passes `true`, the native generator leaves the
   default `false`, so a `val` that is not a constructor parameter (own or inherited — the
   fixture's `baseValue` happens to be inherited) is read and populated on JVM but never asked
   for on native, which silently keeps the host's own default. The flag exists because the two
   backends write fields with different primitives: JNI `SetIntField`/`SetObjectField` pokes the
   backing field directly and can set a `val` after construction, while the native generator's
   `_obj.name = value` is ordinary Kotlin and won't compile against a `val`. `var` body fields
   pass on both. Left as-is: native's constructor-only model matches Kotlin semantics, and the
   silent default only bites when the host's default differs from what the guest meant to send.
2. **Retyped Int→String host→guest.** The guest stringifies the JS number: `99` on JVM, `99.0` on
   Kotlin/Native.

Message dialect is also folded: JVM C-generated messages are lowercase `host2js:` with a bare class
name; Kotlin/Native's are uppercase `HOST2JS:` and quote it. `normalizeBridgeMessage` folds the
`JsException` wrapper on the host→guest path because it is a harness-path difference, not a
bridge-semantics one.

## No abort

The old `💀 abort` rows (a guest→host read of an absent or mistyped String/number throwing a
`jsi::JSError` inside Hermes with no JNI landing pad, reaching `std::terminate` at exit 134) are
gone. The generated C now reads every body field through a checked accessor guarded by
`if (_fld_<name> == NULL)` and a tag check, throwing `ZiplineApiMismatchException` before any
Hermes error can escape. No scenario in the matrix kills the process; every incompatible read is a
catchable Kotlin exception on both hosts. The fork harness (`SkewAbortForkTest`) that pinned those
exit codes is deleted.

## Frozen-snapshot convention

`zipline-bridge-skew-old` is a frozen snapshot of the pre-change fixtures; `zipline-bridge-skew-new`
carries the post-change fixtures. A future change extends the *new* side, then the current new side is
copied over the old side to re-freeze it. Do not edit the old tree's fixtures to make a row pass —
that erases the skew the harness exists to test.

One fixture rule the harness discovered the hard way: a base class whose properties cross the wire
**must** carry `@WithJS2HostBridge`/`@WithHost2JSBridge`. Kotlin/JS mangles an unannotated class's
backing field to `name_1`, while the generated C converter asks for `getProperty(rt, "name")` and
reads `undefined`. The `jsName()` heuristic in `FieldExtractor.kt` assumes public properties keep
plain names; that holds only for bridge-annotated classes.

## Follow-up tasks

The improvement tasks derived from this table live in the CLV-121 follow-up notes: T1 loud/catchable
failure for a missing or mistyped field, T2 default-parameter addition as a compatible change, T3
port `@HostName` to trunk, T4 inherited properties crossing the wire, T5 value classes with a
reference underlying type.
