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
| ❌ | Incompatible: a caught exception with a usable message. |
| ❌ silent wrong | Incompatible with **no** error: the wrong value crosses. |
| 💀 abort | The process dies (SIGABRT, exit 134). No Kotlin `catch` can see it. |
| ⛔ blocked | Not expressible with the current generator. |

## Guest → host

The host's generated converter reads the guest's JS object. "Direction" names the guest bundle and the
host tree. JVM observed values are from `jvmTest`; the Native column is `macosArm64Test`.

| Change kind | Direction | Verdict | JVM observed | Kotlin/Native observed | Follow-up |
|---|---|---|---|---|---|
| Class added on guest (`OnlyInNew`) | new guest → old host | ❌ | `null` (top-level quiet decode) | `IllegalStateException: host bridge: no converter registered for JS class 'OnlyInNew'; annotate the class with @WithJS2HostBridge to send it to the host` | T1.2 |
| Class deleted on host (`OnlyInOld`) | old guest → new host | ❌ | `null` | same loud throw for `OnlyInOld` | T1.2 |
| Field added on guest (`AddedFieldPayload.c`) | new guest → old host | ✅ lossy | `AddedFieldPayload(a=11, b=af)` — `c` dropped | same | — |
| Field added on host (`AddedFieldPayload.c`) | old guest → new host | ⚠️ | `AddedFieldPayload(a=11, b=af, c=0.0)` — silent default | same | T1.3 |
| Field removed on guest (`RemovedFieldPayload.c`) | new guest → old host | ⚠️ | `RemovedFieldPayload(a=11, b=rf, c=0.0)` — silent default | same | T1.3 |
| Field removed on host (`RemovedFieldPayload.c`) | old guest → new host | ✅ | `RemovedFieldPayload(a=11, b=rf)` | same | — |
| Field renamed, no alias | both | 💀 abort | exit 134, `Value is undefined, expected a String` | not covered (fork harness is JVM-only) | T1.1, T3 |
| Property retyped Int→String | new guest → old host | 💀 abort | exit 134, `Value is a string, expected a number` | not covered (JVM-only) | T1.1 |
| Property retyped Int→String | old guest → new host | 💀 abort | exit 134, `Value is a number, expected a String` | not covered (JVM-only) | T1.1 |
| `null` into non-null String | new guest → old host | 💀 abort | exit 134, `Value is null, expected a String` | not covered (JVM-only) | T1.1 |
| Nullability widened (non-null → nullable) | old guest → new host | ✅ | `NullabilityPayload(v=nonnull)` | same | — |
| Superclass added on guest | new guest → old host | ✅ | `HierarchyPayload(a=11, b=hp)` — old host extracts own fields only | same | — |
| Superclass inserted on host | old guest → new host | 💀 abort | exit 134, `Value is undefined, expected a number` (host asks for `baseValue` the old guest never sent) | not covered (JVM-only) | T4 |
| Superclass removed on guest | new guest → old host | 💀 abort | exit 134, `Value is undefined, expected a number` (old host still extracts the base's `inherited`) | not covered (JVM-only) | T4 |
| Superclass removed on host | old guest → new host | ✅ | `DroppedBasePayload(keep=keep)` | same | — |
| Interface added on guest | new guest → old host | ✅ | `InterfacePayload(v=11)` — wire-invisible | same | — |
| Interface gained on host | old guest → new host | ✅ | `InterfacePayload(v=11)` | same | — |
| Enum appended on guest (`GrowthEnum.C`) | new guest → old host | ❌ | `NullPointerException: Parameter specified as non-null is null: method ...GrowthEnumHolder.<init>, parameter value` (unknown ordinal decodes to null) | `IllegalStateException: host bridge: ordinal 2 is out of range for GrowthEnum` (range check fires) | T1 |
| Enum appended on host | old guest → new host | ✅ | `GrowthEnumHolder(value=B)` | same | — |
| Enum shifted on guest (`W` inserted) | new guest → old host | ❌ silent wrong | `ShiftedEnumHolder(value=Y)` — the guest sent `X` | same | T1 |
| Enum shifted on host (`W` inserted) | old guest → new host | ❌ silent wrong | `ShiftedEnumHolder(value=W)` — the guest sent `X` | same | T1 |
| Sealed leaf added on guest (`LeafB`) | new guest → old host | ❌ caught | `IllegalStateException: host bridge: no converter registered for JS class 'LeafB'; annotate the class with @WithJS2HostBridge to send it to the host` | same | T1 |
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

## JVM vs Kotlin/Native divergence

Four behavior splits the matrix pins with `assertObserved(..., nativeExpected = ...)`. These are real
host-implementation differences, not test artifacts:

1. **Top-level unknown class.** JVM decodes it to `null` (`ContextJni.cpp` passes
   `throwOnUnsupportedType = false`); Kotlin/Native throws the loud `no converter registered` error.
   A field-level unknown class is loud on both.
2. **Enum ordinal out of range.** JVM decodes the unknown ordinal to `null` and the holder's non-null
   parameter raises an NPE; Kotlin/Native fires the generated range check and throws
   `ordinal N is out of range`.
3. **Retyped Int→String host→guest.** The guest stringifies the JS number: `99` on JVM, `99.0` on
   Kotlin/Native.
4. **Host→JS conversion failure type.** Kotlin/Native's `callGuestFunction` wraps it in `JsException`
   with a `cannot convert argument N of class X to JS:` prefix; the JVM throws `IllegalStateException`
   with the same inner message. `normalizeBridgeMessage` folds the wrapper because it is a
   harness-path difference, not a bridge-semantics one.

Message dialect is also folded: JVM C-generated messages are lowercase `host2js:` with a bare class
name; Kotlin/Native's are uppercase `HOST2JS:` and quote it.

## 💀 abort rows

A guest→host read of an absent or mistyped **String** or **number** property throws a C++
`jsi::JSError` inside Hermes. It has no JNI landing pad, so it reaches `std::terminate` and the
process dies with SIGABRT (exit 134). No Kotlin `catch` can see it, and `forkEvery=1` is per-class,
not per-method, so an in-process assertion would take the whole test class down. `SkewAbortForkTest`
(jvmTest) forks a JVM per scenario and pins the exit code plus the Hermes message:

| Scenario | Hermes message |
|---|---|
| `fieldRenamed` | `Value is undefined, expected a String` |
| `fieldRetyped` (new host) | `Value is a number, expected a String` |
| `fieldRetyped` (old host) | `Value is a string, expected a number` |
| `nullIntoNonNullable` | `Value is null, expected a String` |
| `superclassInserted` / `superclassRemovedOnGuest` | `Value is undefined, expected a number` |

These are the headline evidence for T1: the failure is a crash, not a caught exception.

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
