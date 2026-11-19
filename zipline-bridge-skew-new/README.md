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
| Superclass inserted on host | old guest → new host | ❌ / ✅ | `ZiplineApiMismatchException: ...HierarchyPayload.baseValue expects a number but the guest sent undefined` | `HierarchyPayload(a=11, b=hp)` — native never extracts inherited body fields | divergence #1 |
| Superclass removed on guest | new guest → old host | ❌ / ✅ | `ZiplineApiMismatchException: ...DroppedBasePayload.inherited expects a number but the guest sent undefined` | `DroppedBasePayload(keep=keep)` — native never asks | divergence #1 |
| Superclass removed on host | old guest → new host | ✅ | `DroppedBasePayload(keep=keep)` | same | — |
| 8 defaulted params added past the 32nd (`WideDefaults.p32..p39`) | old guest → new host | ✅ | `WideDefaults(p0=0, …, p39=39)` — the flags for those params live in the second mask word, so this row is what pins the packing against the JVM descriptor | same — native reaches the same values through `bridgeCreate`, so the row needs no `nativeExpected` any more | T2 |
| 3 defaulted parameters added on host (`SubsetPayload.b/.c/.twin`) | old guest → new host | ✅ | `SubsetPayload(a=11, b=b, c=2.5, d=9, twin=13)` — the **host** is the side evaluating defaults here, including `twin = c.toInt() + a`, chained from one default and one received value; a decoder that read placeholders would report `twin=2` | same | T2 |
| All five present, old host shape | new guest → old host | ✅ | `SubsetPayload(a=11, d=9)` — the guest's three extra values are dropped, not misaligned | same | — |
| Defaults reaching **private** members (`PrivateDefaultsPayload.tag`, `.magic`) | old host → new guest | ✅ | `7\|pc\|4242` — `tag` is a private companion `const` and `magic` the result of a private top-level function of the receiver's file; a factory generated beside the class rather than inside it would not compile against them | same | — |
| Same, host decoding | old guest → new host | ✅ | `PrivateDefaultsPayload(a=11, tag=pc, magic=4242)` — here the **host** evaluates them: JVM through its masked constructor, Kotlin/Native through `bridgeCreate`, one string either way | same | — |
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

Since the create-factory change the host's object is an *input*, not the payload: the guest reads the
host's own properties off it and runs its real constructor, so its initializer blocks, computed
properties and default expressions happen, and a parameter the host does not have is caught by the
constructor that requires it instead of arriving as `undefined`. There is no fallback to the old stamped
path: a class the guest builds no factory for is refused, naming the factory.

A defaulted parameter can only be *absent* through a pair of declarations. A host converter emits
exactly `extractFields(hostClass)` — the fields its own class has — so no single-module test can make a
host omit one of its own fields; `SubsetPayload` here exists twice, once per tree, with different
declarations. `SubsetPayload` additionally carries `@WithJS2HostBridge`, because its rows run in both
directions: host→guest pins the *guest* evaluating defaults, guest→host pins the *host* doing it. That is also why these rows need no "what stamping would have done" control: every
expectation below is a value nobody sends — a default, a computed property, superclass state, or a
refusal — so a stamped object cannot produce it.

| Change kind | Direction | Verdict | JVM observed | Kotlin/Native observed | Follow-up |
|---|---|---|---|---|---|
| Parameter added on guest, **with a default** (`DefaultedFieldPayload.added`) | old host → new guest | ✅ | `11\|df\|99` — the guest's default, from a host that has never heard of the parameter | same | T2 |
| Same shapes, old guest | new host → old guest | ✅ | `11\|df`, `7\|9`, `3`, `4`, `8` — every shape above has a mirror row: the extra parameter is ignored and the fields the older shape declares still arrive right, so an added parameter is additive rather than corrupting | same | — |
| 8 parameters the guest has never heard of (`WideDefaults`, 40 → 32) | new host → old guest | ✅ | the guest's own thirty-two fields, all correct | same | — |
| Several parameters absent, one defaulted **from another** (`SubsetPayload`) | old host → new guest | ✅ | `7\|b\|2.5\|9\|9` — `b`, `c` defaulted and `twin = c.toInt() + a` evaluated against the default `c` and the *sent* `a`; a factory that read placeholders would print `2` | same | T2 |
| 8 defaulted parameters past the first flag word, host → guest (`WideDefaults.p32..p39`) | old host → new guest | ✅ | all forty printed, `p32=32 … p39=39` — those eight are bits in the **second** mask word, filled in by the guest while its host sends a 32-field object | same | T2 |
| Constructor computes a body property (`ValidatedPayload.area`) | old host → new guest | ✅ | `4\|12` — `area` is no host's field; only the guest's initializer produces it | same | — |
| Constructor validates (`require(w > 0)`) | old host → new guest | ❌ caught | `IllegalStateException: w must be positive, got -2` — legal for the sender's shape, refused by the receiver's | same | — |
| Base-class state (`BaseStatePayload : BaseStateHost`) | old host → new guest | ✅ | `8\|5\|10` — `base` defaulted and `doubled` computed in a superclass the host's shape does not have | same | T4 |

The private-member pair is also the reason the generated factory is a companion member: private
top-level declarations are visible to a companion object (same file) but to no generated file-scope
function, and the plugin tests obtain `bridgeCreate` by looking it up on the nested `Companion`, so a
factory that moved would fail there rather than silently widen the class's surface.

`WideDefaults` carries `@WithHost2JSBridge` for the wide host→guest row. Without it the JVM host still
*compiles* — it reaches `convertToJs` through the runtime rather than through a member the plugin
declared — and fails at run time with `NoSuchMethodError: L…WideDefaults;.convertToJs(J)J`.

**Mutable body properties are assigned after the receiver's constructor has run, and only when they
arrived.** They are the fields a constructor cannot receive: `extractFields` returns them, the
converters send them, and `bridgeCreate`'s signature is the constructor's, so nothing assigns them
unless the receiver does it itself. This is the shape the create-factory narrowed without anyone
noticing — stamping defined every field straight onto the object, so `extra` crossed for free, while a
constructor-only factory dropped it in silence. Nothing in the suite could have caught it: no bridged
class here had a mutable body field until it was the subject of a row.

| | direction | expectation |
| --- | --- | --- |
| `host2js/mutable-body/present` | new host → new guest | `7\|from-host` — the host's value, on top of a constructor that ran |
| `host2js/mutable-body/absent` | old host (no such property) → new guest | `7\|guest-default` — absence never writes over the receiver's own value |
| `host2js/mutable-body/ignored` | new host → old guest (no such property) | `7` — an extra field is still just an extra field |

`control_hostToGuest_mutableBodyPresent` in `Host2JsBridgeEndToEndTest` covers the same rule with four
field types (`String`, `List<String>`, a `@JvmInline` wrapper, `Int`), because the absence-tolerant
reads do not answer uniformly: null for the reference-typed ones, zero for the numeric one. The same property arriving *from* a
guest is the host's own version of the same decision, and it used to be a divergence: with the old
guest sending only `a`, the JVM host answered `MutableBodyPayload(a=11, extra=guest-default)` and the
Kotlin/Native host refused the payload outright —
`host bridge: app.cash.zipline.bridge.test.MutableBodyPayload.extra expects a string but the guest
sent undefined`. Its tolerance was keyed to the flag word (`hasDefault` *and* a constructor parameter)
rather than to the declaration. Both hosts now answer the same way, which is what the row pins:

| | direction | expectation |
| --- | --- | --- |
| `js2host/mutable-body/absent` | old guest (no such property) → new host | `MutableBodyPayload(a=11, extra=guest-default)` on both hosts |

**`Byte`, `Short` and `Char` cross as numbers, and only one host could decode them.** They had no
entry in any fixture, which is how this stayed hidden: the JNI side has had
`SetByteField`/`SetShortField`/`SetCharField` (and the matching getters) from the start, while the
Kotlin/Native decoder's scalar arms stop at `Int`, `Long`, `Float`, `Double`, `Boolean` — so a narrow
field fell through to the generic decoder, whose answer is `Any?`, and the *generated code did not
compile*: a bridged class could declare a `Byte` field all along, and the native host would fail at
build time rather than at a boundary. `Char` needs one more thing, because Kotlin/JS has `Char` as an
inline class: `bridgeGet(...) as Char` on a wire number answers garbage — measured, a host's `'z'`
arrived as `'\u0000'` and a `Char?`'s `'X'` as `null`, with no failure anywhere — so the guest builds
it through `bridgeChar` instead of casting it, exactly as it builds value-class wrappers.

| | direction | expectation |
| --- | --- | --- |
| `host2js/narrow-defaults/absent` | old host (`b` only) → new guest | `11\|9\|z\|4` — a `Short`, a `Char` and a `UInt` from their defaults, over the same flag word an `Int` uses |
| `host2js/narrow-defaults/ignored` | new host → old guest (`b` only) | `7` |
| `js2host/narrow-defaults/absent` | old guest → new host | `NarrowDefaultsPayload(b=11, s=9, c=z, u=4)`, both hosts |

A defaulted narrow parameter was refused before any of this could be tested: the guest factory builds
a placeholder for every defaulted parameter and had none for a type that is neither an `Int` nor a
reference, so it said so. The placeholders are `IrConstImpl.byte/short/char` — a real zero of the right
type, because the expression is evaluated even though the flag word guarantees it is never read.

**The unsigned scalars were a different shape of the same gap, and each host lost it differently.**
`UInt` and `ULong` worked by luck: the native generator has wrapper arms for their underlying `Int`
and `Long`, and `inlineWrapperChain` built the wrapper around whatever the arm read. `UByte` and
`UShort` have no such arm, so their field fell to the narrow-scalar arm above, which produced a
`Byte` for a `UByte` property — not a runtime failure but a build failure:

    app_cash_zipline_bridge_test_BridgedUnsignedProbe_bridge_native.kt:66:49
      Argument type mismatch: actual type is 'Byte', but 'UByte' was expected.

A value that is not a field of a bridged class takes the host's *dynamic* encoder instead, and there
neither host had a case for them: the Kotlin/Native host refused with `HOST2JS: no bridge for
'kotlin.UInt'`, and the JNI host fell through to the `convertToJs` dispatch for annotated classes and
answered `NoSuchMethodError: Lkotlin/UInt;.convertToJs(J)J` — true, and useless as a diagnosis. Both
now put the same thing on the wire, which the shared end-to-end row pins for both hosts at once:

    -1|300|90|-294967296|-56|-5536|-446744073709551616

That is the rule "the bits travel, the receiver reinterprets them": `UByte(200u)` crosses as the byte
`-56`, not as `200`, because that is what the box holds and what the generated route for a `UInt`
field already did. Zero-extending on one host only would have made the two hosts disagree on the wire
for one payload, which is worse than either choice on its own. `ULong` is the exception, because 64
bits do not fit a JS number: both hosts hand the guest a real Kotlin/JS `Long` carrying the same bits
(the JNI host's `java/lang/Long` arm above still answers a lossy double, which is older behaviour this
change leaves alone).

A defaulted `UInt` parameter needs a placeholder too, and an unsigned type has no constant form at
all — it is an inline class — so the generated call builds `UInt(0)` over the wrapper's own underlying
type, read from the wrapper's constructor rather than named again here.

**A `String` crossed the JNI bridge as Java's *modified* UTF-8 into a consumer expecting UTF-8.**
The two encodings differ in exactly two places, and both were silent. A NUL ended the value:
host→guest and guest→host both answered `"a"` for `"a\u0000b"`. Anything above U+FFFF was worse,
because modified UTF-8 spends six bytes on a surrogate pair where UTF-8 wants four — an emoji sent by
a host arrived at the guest as an **empty string**, and the only reason anyone noticed is that the
guest then called `last()` on it and threw `JsException: Char sequence is empty`. Nothing on either
side failed, and nothing was recoverable.

`zipline/native/JniUtf8.h` already existed for exactly this, with a comment naming the bug: it
converts through UTF-16, which both sides agree on, and the JSON path (`ContextJni::toJavaString`)
uses it. The bridge path never got it — four sites in `bridge_dispatch.h` and two in the C the plugin
generates were still on `GetStringUTFChars`/`NewStringUTF`. The raw `GetStringUTFChars` on the map-key
path also pinned a character buffer it never released.

| row | direction | expectation |
| --- | --- | --- |
| `supplementaryCharacterSurvivesTheBridge` | host → guest | `2\|56832\|tail` — **both hosts**, one assertion in the shared suite |
| `multiByteCharactersSurviveTheBridge` | host → guest | `3\|20013\|tail` — code points below the surrogate range, which both encodings already agreed on |
| `supplementaryCharacterSurvivesARoundTrip` | host → guest → host | `len=2, last=56832`, **both hosts** |
| `nulStringReachesTheGuest` | host → guest | `3\|98\|tail` — **both hosts** |
| `nulStringFromTheGuestReachesTheHost` | guest → host | `len=3, last=121, tail=guest-tail` — **both hosts** |
| `nulStringSurvivesARoundTrip` | host → guest → host | `len=3, last=98` — **both hosts** |
| `unpairedSurrogateBecomesAReplacementCharacter` | host → guest | `1\|65533\|tail` — **both hosts**, the same U+FFFD |

**A NUL needed more than an encoding fix, because the other host's boundary carries no length (5.17).**
The Kotlin/Native host does not cross a JNI boundary at all: it crosses a C one, where a `String` was
converted by cinterop with `toCString` going out and read back through `HermesBridge_getValueString`,
which computes `s.size()` and then `strdup`s `s.c_str()` — keeping only the bytes before the first NUL.
The length was in hand one line earlier and discarded, so the fix is two entry points that carry it:
`HermesBridge_getValueStringUtf8(ctx, handle, &outLength)` and
`HermesBridge_createStringUtf8(ctx, bytes, length)`. The Kotlin side encodes to a byte array and pins
it, because handing a `String` to the C call would let cinterop truncate it again — which is also why
the byte pointer is `unsigned char*`: a `const char*` parameter maps to a Kotlin `String`.
`HermesBridge_getValueString` stays for **names**, since a property name or a class name cannot contain
a NUL, and the header says so rather than leaving it as folklore. Both directions of a NUL now arrive
whole on both hosts, which is why these rows moved from per-host files into the shared suite.

An unpaired surrogate is the third thing no encoding can represent: Hermes hands over WTF-8 and JNI
cannot express it either. Both hosts answer U+FFFD, so the row pins the *policy* — a damaged value
looks the same on every host — instead of pretending nothing happened.

`object` payloads are answered with the guest's **own singleton** — they have no constructor to run, and a
second instance of a class with exactly one is visible to `===` and to a `when` against the singleton.

A `@JvmInline value class` field crosses as the wrapper's **underlying value** — both hosts read its
single backing field — and the guest's factory rebuilds the wrapper around it before the constructor
sees it. Casting the read straight to the wrapper type instead would compile to `raw_1` on a number, i.e.
`undefined` in the field, noticed only on the way back. The row that pins the rebuild is
`hostToGuest_defaultedValueClassField`: the old tree declares only `n`, so `w` cannot cross at all and
the guest has to produce `WrappedValue(7)` out of its own default — `3|7` reported back.

| Change kind | Direction | Verdict | JVM observed | Kotlin/Native observed | Follow-up |
|---|---|---|---|---|---|
| Class deleted on guest (`OnlyInOld`) | old host → new guest | ❌ caught | `IllegalStateException: host2js: no registered prototype for app.cash.zipline.bridge.test.OnlyInOld; the guest module did not call __bridgeRegister for this class` | same message, wrapped as `JsException: callGuestFunction: cannot convert argument 0 ...` (folded) | T1 |
| Class missing on guest (`OnlyInNew`) | new host → old guest | ❌ caught | same for `OnlyInNew` | same wrapped | T1 |
| Field added on host | new host → old guest | ✅ | `11\|af` — guest ignores `c` | same | — |
| Field added on guest (no default) | old host → new guest | ❌ caught | `IllegalStateException: host2js: app.cash.zipline.bridge.test.AddedFieldPayload.c was not sent by the host` | same message (wrapped, folded) | was `11\|af\|undefined` before the guest built its own payloads |
| Field removed on guest | old host → new guest | ✅ | `11\|rf` — the host's extra `c` is ignored by the guest's shape | same | — |
| Field removed on host | new host → old guest | ❌ caught | `IllegalStateException: host2js: app.cash.zipline.bridge.test.RemovedFieldPayload.c was not sent by the host` — the guest's create-factory is the first place that knows the parameter is required | same message (wrapped, folded) | was `11\|rf\|undefined` before the guest built its own payloads |
| Field renamed, no alias | both | ❌ caught | `IllegalStateException: host2js: app.cash.zipline.bridge.test.RenamedFieldPayload.oldName was not sent by the host` | same message (wrapped, folded) | T3; was `null` — raw `undefined` crossing back — before the guest built its own payloads |
| Property retyped Int→String | old host → new guest | ⚠️ divergent | `99` | `99.0` — native renders the JS double with its fractional part | — |
| `null` into guest non-null | new host → old guest | ✅ | `null` | same | — |
| Enum payload | both | ❌ refused by design | `IllegalStateException: host2js: app.cash.zipline.bridge.test.GrowthEnum is an enum; the guest would only see a lookalike, not an enum instance` | same (wrapped, folded) | — |
| Value-class field (`WrappedHolder.w`) | both | ✅ | `WrappedValue(v=42)` — the same text the stamped path produced; the guest's factory rebuilds the wrapper from the value that crossed | same | — |
| Hierarchy / interface change | both | ✅ | values cross unchanged | same | — |

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
   The other half of that sentence is unfixed and open (task 5.6): a `var` body field IS asked for on
   both backends, so one that declares a default and arrives absent still throws on native, while the
   JVM host takes the default. Constructor parameters are tolerant on both; body `var`s are not.
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

What that forbids is *editing a side to change an outcome*. Adding the other half of a pair is the
harness working as designed, and sometimes necessary: a row in which a host lacks a field cannot be
written from one tree, because the converter emits its own class's fields and no others. The
`DefaultedFieldPayload` / `SubsetPayload` / `WrappedDefaultPayload` / `ValidatedPayload` /
`BaseStatePayload` group therefore appears in both trees, in the same commit, declared differently.

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
