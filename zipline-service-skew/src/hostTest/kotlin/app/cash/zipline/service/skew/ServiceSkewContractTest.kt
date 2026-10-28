package app.cash.zipline.service.skew

import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * CLV-121: the service version-skew contract across a **real engine boundary**.
 *
 * Every row runs a real compiled JS guest against a real JVM or Kotlin/Native host. Nothing here
 * asserts wire bytes: each row pins the *observable outcome* — a value, or a thrown throwable plus
 * message. Swapping the transport (JSON wire for a JNI / Kotlin/Native data transfer) that flips a
 * verdict changes that outcome, so the pinned contract fails loudly rather than silently agreeing.
 *
 * The review artifact is [README.md](../README.md), which tabulates every row with its verdict. The
 * test names mirror that table: `hostToGuest_*` is the host calling a guest-bound service,
 * `guestToHost_*` is the guest calling a host-bound service.
 *
 * Each fixture ships its pre-change (V1) and post-change (V2) shape in one compilation. The callee
 * binds one shape under a wire name and the caller `take`s the other — that pairing *is* the skew.
 *
 * Preferences pinned here: accept as much skew as possible; an unused service or method never breaks
 * normal flow; incompatibility surfaces as a clear catchable exception, never a crash.
 *
 * Rows go through [assertObserved] / [assertThrowsContaining], which print the observed value or
 * throwable under `-Dskew.capture=true` instead of asserting, so every pinned verdict was read off
 * the real engine rather than guessed.
 */
class ServiceSkewContractTest {
  private val dispatcher = Dispatchers.Default

  private val mismatch = "no such method (incompatible API versions?)"

  // ------------------------------------------------------------------------------------------
  // Control: same version on both sides. Proves the harness itself.
  // ------------------------------------------------------------------------------------------

  /** No skew: the host calls the guest's live service. */
  @Test fun control_hostToGuest() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertObserved("A live.ping", "pong") { zipline.take<LiveService>("live").ping() }
    }
  }

  /** No skew: the guest calls a host-bound service through a `@JsExport` entry point. */
  @Test fun control_guestToHost() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<MethodsV1>(
        "hostMethods",
        object : MethodsV1 {
        override fun shared() = "shared"
      },
      )
      assertObserved("B methods(V2).shared on host V1", "shared") {
        zipline.callGuest("callHostMethodsShared", "hostMethods")
      }
    }
  }

  // ==========================================================================================
  // HOST -> GUEST: the host takes a proxy of one shape and calls the guest bound as the other.
  // The host's compiler resolves the function id from its own shape; the guest's registry gates it.
  // ==========================================================================================

  // ---- service presence ---------------------------------------------------------------------

  /** A deleted service, called: a clear catchable throw naming the service, not the method. */
  @Test fun hostToGuest_serviceDeleted_calledThrowsClearMessage() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      // take is lazy: binding nothing under "gone" must not throw at take time.
      val client = zipline.take<GoneService>("gone")
      assertThrowsContaining("A gone.use", "ZiplineApiMismatchException", "no such service (service closed?)") {
        client.use()
      }
    }
  }

  /** A deleted service that is never called is inert: `take` is lazy and the live path is untouched. */
  @Test fun hostToGuest_serviceDeleted_neverCalled_isInert() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertObserved("A take gone (lazy, uncalled)", "taken") {
        zipline.take<GoneService>("gone")
        "taken"
      }
      assertObserved("A live.ping after taking a gone service", "pong") {
        zipline.take<LiveService>("live").ping()
      }
    }
  }

  /** Services the guest added that the old host never takes are inert. */
  @Test fun hostToGuest_serviceAdded_neverCalled_isInert() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertObserved("A live.ping with extra guest services bound", "pong") {
        zipline.take<LiveService>("live").ping()
      }
    }
  }

  // ---- method presence ----------------------------------------------------------------------

  /** A method the guest added, unused by the old caller: compatible. */
  @Test fun hostToGuest_methodAdded_unusedIsFine() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertObserved("A methods(V1).shared on V2 guest", "shared") {
        zipline.take<MethodsV1>("methods").shared()
      }
    }
  }

  /** A method the guest deleted, called by the new caller: catchable mismatch. */
  @Test fun hostToGuest_methodDeleted_calledThrowsClearMessage() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A methods(V2).added on V1 guest", "ZiplineApiMismatchException", mismatch) {
        zipline.take<MethodsV2>("methodsOld").added()
      }
    }
  }

  /** A method the guest deleted, unused by the new caller: compatible. */
  @Test fun hostToGuest_methodDeleted_unusedIsFine() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertObserved("A methods(V2).shared on V1 guest", "shared") {
        zipline.take<MethodsV2>("methodsOld").shared()
      }
    }
  }

  /** A method dropped in the new version, still called by an old caller: catchable mismatch. */
  @Test fun hostToGuest_methodRemovedInNewVersion_oldCallerThrowsClearMessage() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A methods(V1b).removedInV2 on V2 guest", "ZiplineApiMismatchException", mismatch) {
        zipline.take<MethodsV1b>("methods").removedInV2()
      }
    }
  }

  // ---- parameter shape ----------------------------------------------------------------------

  /** A required parameter added: the signature changes, so the ids differ. */
  @Test fun hostToGuest_paramAddedRequired_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A params(V2).f on V1 guest", "ZiplineApiMismatchException", mismatch) {
        zipline.take<ParamsV2>("paramsOld").f(SkewPayload("x", 1), "extra")
      }
    }
  }

  /** `g` exists only in V2: a method-add, not a param-add to an existing method. */
  @Test fun hostToGuest_methodAddedWithDefault_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A params(V2).g on V1 guest", "ZiplineApiMismatchException", mismatch) {
        zipline.take<ParamsV2>("paramsOld").g(SkewPayload("x", 1))
      }
    }
  }

  /**
   * The interface-evolution gotcha. `e` exists in BOTH versions and V2 adds a defaulted `extra`:
   * source-compatible for Kotlin callers, but the added parameter changes the arity, the signature
   * string, and therefore the function id. Omitting `extra` materializes the caller's "d" on the
   * wire; the V1 guest still has no function with that id. The default does NOT rescue it.
   */
  @Test fun hostToGuest_defaultedParamAddedToExistingMethod_newCaller_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A params(V2).e omit extra on V1 guest", "ZiplineApiMismatchException", mismatch) {
        zipline.take<ParamsV2>("paramsOld").e(SkewPayload("x"))
      }
    }
  }

  /** The mirror: a V1 caller (1-arg `e`) against a V2 guest whose `e` takes 2. Same id mismatch. */
  @Test fun hostToGuest_defaultedParamAddedToExistingMethod_oldCaller_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A params(V1).e on V2 guest", "ZiplineApiMismatchException", mismatch) {
        zipline.take<ParamsV1>("params").e(SkewPayload("x"))
      }
    }
  }

  /** A parameter deleted: the signature changes, so the ids differ. */
  @Test fun hostToGuest_paramDeleted_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A params(V1b).del on V2 guest", "ZiplineApiMismatchException", mismatch) {
        zipline.take<ParamsV1b>("params").del(SkewPayload("x", 1))
      }
    }
  }

  /** A default added to an existing parameter: the old caller supplies the arg explicitly. */
  @Test fun hostToGuest_defaultAddedToExistingParam_compatible() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertObserved("A params(V1).h on V2 guest", "h:z") {
        zipline.take<ParamsV1>("params").h("z")
      }
    }
  }

  /**
   * A default removed from a parameter: V2.h declares a default the V1 guest lacks. Omitting the
   * argument materializes the host's "d" on the wire; the guest never consults a default of its own.
   */
  @Test fun hostToGuest_defaultRemovedFromParam_compatible() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertObserved("A params(V2).h omit a on V1 guest", "h:d") {
        zipline.take<ParamsV2>("paramsOld").h()
      }
    }
  }

  /** Param names are absent from the signature and from the positional wire array: same id. */
  @Test fun hostToGuest_paramRenamed_compatible() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertObserved("A rename(V1).k on V2 guest", "k:y") {
        zipline.take<RenameV1>("rename").k(SkewPayload("y", 2))
      }
    }
  }

  // ---- whose default wins -------------------------------------------------------------------
  //
  // A service-method default is resolved by the CALLER's compiler at the call site (the `$default`
  // synthetic) and sent on the wire as a materialized argument; the callee's own default is never
  // consulted. DefaultsV1/DefaultsV2 carry *different* defaults ("V1" vs "V2") under an identical
  // signature, so the returned string reveals whose default won.

  /** Old caller's default wins: the host takes the V1 shape ("V1") against the V2 guest ("V2"). */
  @Test fun hostToGuest_methodDefaultDiffers_oldCallerWins() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertObserved("A defaults(V1).d omit extra on V2 guest", "d:x:V1") {
        zipline.take<DefaultsV1>("defaults").d(SkewPayload("x"))
      }
    }
  }

  /** New caller's default wins: the guest's "V1" default is dead; the host's "V2" crosses. */
  @Test fun hostToGuest_methodDefaultDiffers_newCallerWins() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertObserved("A defaults(V2).d omit extra on V1 guest", "d:x:V2") {
        zipline.take<DefaultsV2>("defaultsOld").d(SkewPayload("x"))
      }
    }
  }

  // ---- type changes -------------------------------------------------------------------------

  /** A parameter retyped to an unrelated type: the signature changes. */
  @Test fun hostToGuest_paramRetypedUnrelated_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A retype(V1).m on V2 guest", "ZiplineApiMismatchException", mismatch) {
        zipline.take<RetypeV1>("retype").m(SkewPayload("x", 1))
      }
    }
  }

  /** Widening to a supertype still changes the signature string, even though the DTO could decode. */
  @Test fun hostToGuest_paramWidenedToSupertype_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A retype(V1).n on V2 guest", "ZiplineApiMismatchException", mismatch) {
        zipline.take<RetypeV1>("retype").n(SkewPayload("x", 1))
      }
    }
  }

  /** A changed return type changes the signature string. */
  @Test fun hostToGuest_returnTypeChanged_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A retype(V1).o on V2 guest", "ZiplineApiMismatchException", mismatch) {
        zipline.take<RetypeV1>("retype").o()
      }
    }
  }

  /** Nullability is part of the type in the signature string. */
  @Test fun hostToGuest_paramNullabilityChanged_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A retype(V1).q on V2 guest", "ZiplineApiMismatchException", mismatch) {
        zipline.take<RetypeV1>("retype").q("x")
      }
    }
  }

  /** Return nullability is part of the signature string too. */
  @Test fun hostToGuest_returnNullabilityChanged_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A retype(V1).r on V2 guest", "ZiplineApiMismatchException", mismatch) {
        zipline.take<RetypeV1>("retype").r()
      }
    }
  }

  // ---- suspend vs non-suspend ---------------------------------------------------------------

  /** `suspend` prefixes the signature string, so the two `s` shapes never share an id. */
  @Test fun hostToGuest_nonSuspendCaller_suspendCallee_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A susp(V1).s on V2(suspend) guest", "ZiplineApiMismatchException", mismatch) {
        zipline.take<SuspendV1>("susp").s("x")
      }
    }
  }

  /** The mirror: a suspend caller against a non-suspend callee. */
  @Test fun hostToGuest_suspendCaller_nonSuspendCallee_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A susp(V2).s on V1(non-suspend) guest", "ZiplineApiMismatchException", mismatch) {
        zipline.take<SuspendV2>("suspOld").s("x")
      }
    }
  }

  // ---- val vs var ---------------------------------------------------------------------------

  /** A `val`/`var` swap: the getter signatures match, the setter has no counterpart. */
  @Test fun hostToGuest_propertyValVsVar_getterMatchesSetterMismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      val client = zipline.take<PropVarService>("prop") // guest exposes val only
      assertObserved("A prop(VAR).get on val guest", "v") { client.prop }
      assertThrowsContaining("A prop(VAR).set on val guest", "ZiplineApiMismatchException", mismatch) {
        client.prop = "w"
      }
    }
  }

  // ---- failure modes ------------------------------------------------------------------------

  /** A callee throw crosses as a catchable exception with its message; the engine keeps serving. */
  @Test fun hostToGuest_calleeThrows_messagePreservedNoCrash() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("A boom.go", "ZiplineException", "boom") {
        zipline.take<ThrowingService>("boom").go()
      }
      assertObserved("A live.ping after a callee throw", "pong") {
        zipline.take<LiveService>("live").ping()
      }
    }
  }

  /** `Long` crosses exactly on both sides of 2^53: JSON number below, string above. */
  @Test fun hostToGuest_longBoundaryCrossesExactly() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      val client = zipline.take<LongService>("long")
      assertObserved("A long.big(2^53-2)", "9007199254740991") { client.big(9007199254740990L) }
      val big = 1L shl 53
      assertObserved("A long.big(2^53)", (big + 1L).toString()) { client.big(big) }
    }
  }

  // ==========================================================================================
  // GUEST -> HOST: the host binds one shape; the guest takes the other through a `@JsExport` entry
  // point and calls across the boundary. This is the production-critical direction for Compose Live:
  // a freshly shipped JS bundle calling a stale host.
  //
  // The two directions are NOT guaranteed to share a transport or mechanism (one may move to a
  // direct bridge while the other stays JSON, as the Compose bridges diverged), so the skew decision
  // is not shared code across directions: every host-as-caller shape gets its own twin here rather
  // than a collapsed outcome-class sample.
  //
  // Each `@JsExport` takes JS-primitive arguments and builds the serializable DTO *inside* the body,
  // so the export signature stays valid while the guest still drives the real service call.
  // ==========================================================================================

  // ---- service presence ---------------------------------------------------------------------

  /** A host service the guest takes that was never bound: a clear catchable throw. */
  @Test fun guestToHost_serviceAbsent_throwsClearMessage() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      assertThrowsContaining("B methods(V2).shared on absent host service", "JsException", "no such service (service closed?)") {
        zipline.callGuest("callHostMethodsShared", "ghost")
      }
    }
  }

  // ---- method presence ----------------------------------------------------------------------

  /** The new guest calls a method the old host lacks: catchable mismatch, wrapped as JsException. */
  @Test fun guestToHost_methodAdded_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<MethodsV1>(
        "hostMethods",
        object : MethodsV1 {
        override fun shared() = "shared"
      },
      )
      assertThrowsContaining("B methods(V2).added on host V1", "JsException", mismatch) {
        zipline.callGuest("callHostMethodsAdded", "hostMethods")
      }
    }
  }

  /** The old guest calls a method the new host dropped: catchable mismatch. */
  @Test fun guestToHost_methodRemovedInNewVersion_oldCaller_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<MethodsV2>(
        "hostMethods",
        object : MethodsV2 {
        override fun shared() = "shared"
        override fun added() = "added"
      },
      )
      assertThrowsContaining("B methods(V1b).removedInV2 on host V2", "JsException", mismatch) {
        zipline.callGuest("callHostMethodsRemovedInV2", "hostMethods")
      }
    }
  }

  /** The old guest calls a method the new host still has: compatible. */
  @Test fun guestToHost_methodStillPresent_oldCaller_compatible() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<MethodsV2>(
        "hostMethods",
        object : MethodsV2 {
        override fun shared() = "shared"
        override fun added() = "added"
      },
      )
      assertObserved("B methods(V1).shared on host V2", "shared") {
        zipline.callGuest("callHostMethodsSharedV1", "hostMethods")
      }
    }
  }

  // ---- parameter shape ----------------------------------------------------------------------

  /** A required parameter added: the guest's V2 call has no id on the V1 host. */
  @Test fun guestToHost_paramAddedRequired_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<ParamsV1>(
        "hostParams",
        object : ParamsV1 {
        override fun f(p: SkewPayload) = "f:${p.a}"
        override fun h(a: String) = "h:$a"
        override fun e(p: SkewPayload) = "e:${p.a}"
      },
      )
      assertThrowsContaining("B params(V2).f on host V1", "JsException", mismatch) {
        zipline.callGuest("callHostParamsF", "hostParams", "x", "extra")
      }
    }
  }

  /** `g` exists only in V2: a method-add from the guest side too. */
  @Test fun guestToHost_methodAddedWithDefault_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<ParamsV1>(
        "hostParams",
        object : ParamsV1 {
        override fun f(p: SkewPayload) = "f:${p.a}"
        override fun h(a: String) = "h:$a"
        override fun e(p: SkewPayload) = "e:${p.a}"
      },
      )
      assertThrowsContaining("B params(V2).g on host V1", "JsException", mismatch) {
        zipline.callGuest("callHostParamsG", "hostParams", "x")
      }
    }
  }

  /**
   * The interface-evolution gotcha from the guest side: the new guest calls `e` (V2 shape, `extra`
   * defaulted) against a host bound as V1. Same id mismatch as the host-as-caller row.
   */
  @Test fun guestToHost_defaultedParamAddedToExistingMethod_newCaller_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<ParamsV1>(
        "hostParams",
        object : ParamsV1 {
        override fun f(p: SkewPayload) = "f:${p.a}"
        override fun h(a: String) = "h:$a"
        override fun e(p: SkewPayload) = "e:${p.a}"
      },
      )
      assertThrowsContaining("B params(V2).e omit extra on host V1", "JsException", mismatch) {
        zipline.callGuest("callHostParamsE", "hostParams", "x")
      }
    }
  }

  /**
   * The explicit-arg mirror of the row above: the new guest supplies `a` explicitly against a V1
   * host whose `h` has no default. The caller's default is simply unused; the arg crosses fine.
   */
  @Test fun guestToHost_defaultedParamSuppliedExplicitly_compatible() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<ParamsV1>(
        "hostParams",
        object : ParamsV1 {
        override fun f(p: SkewPayload) = "f:${p.a}"
        override fun h(a: String) = "h:$a"
        override fun e(p: SkewPayload) = "e:${p.a}"
      },
      )
      assertObserved("B params(V2).h explicit a on host V1", "h:z") {
        zipline.callGuest("callHostParamsH", "hostParams", "z")
      }
    }
  }

  /** The mirror: the old guest's 1-arg `e` against a new host whose `e` takes 2. */
  @Test fun guestToHost_defaultedParamAddedToExistingMethod_oldCaller_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<ParamsV2>(
        "hostParams",
        object : ParamsV2 {
        override fun f(p: SkewPayload, extra: String) = "f:${p.a}:$extra"
        override fun g(p: SkewPayload, extra: String) = "g:${p.a}:$extra"
        override fun h(a: String) = "h:$a"
        override fun e(p: SkewPayload, extra: String) = "e:${p.a}:$extra"
      },
      )
      assertThrowsContaining("B params(V1).e on host V2", "JsException", mismatch) {
        zipline.callGuest("callHostParamsEV1", "hostParams", "x")
      }
    }
  }

  /** A parameter deleted: the old guest's `del` has no id on the V2 host. */
  @Test fun guestToHost_paramDeleted_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<ParamsV2>(
        "hostParams",
        object : ParamsV2 {
        override fun f(p: SkewPayload, extra: String) = "f:${p.a}:$extra"
        override fun g(p: SkewPayload, extra: String) = "g:${p.a}:$extra"
        override fun h(a: String) = "h:$a"
        override fun e(p: SkewPayload, extra: String) = "e:${p.a}:$extra"
      },
      )
      assertThrowsContaining("B params(V1b).del on host V2", "JsException", mismatch) {
        zipline.callGuest("callHostParamsDel", "hostParams", "x")
      }
    }
  }

  /** A default added to an existing parameter: the old guest supplies the arg explicitly. */
  @Test fun guestToHost_defaultAddedToExistingParam_compatible() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<ParamsV2>(
        "hostParams",
        object : ParamsV2 {
        override fun f(p: SkewPayload, extra: String) = "f:${p.a}:$extra"
        override fun g(p: SkewPayload, extra: String) = "g:${p.a}:$extra"
        override fun h(a: String) = "h:$a"
        override fun e(p: SkewPayload, extra: String) = "e:${p.a}:$extra"
      },
      )
      assertObserved("B params(V1).h on host V2", "h:z") {
        zipline.callGuest("callHostParamsHV1", "hostParams", "z")
      }
    }
  }

  /** A default removed from a parameter: the guest's "d" crosses to a host that has no default. */
  @Test fun guestToHost_defaultRemovedFromParam_compatible() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<ParamsV1>(
        "hostParams",
        object : ParamsV1 {
        override fun f(p: SkewPayload) = "f:${p.a}"
        override fun h(a: String) = "h:$a"
        override fun e(p: SkewPayload) = "e:${p.a}"
      },
      )
      assertObserved("B params(V2).h omit a on host V1", "h:d") {
        zipline.callGuest("callHostParamsHDefault", "hostParams")
      }
    }
  }

  /** Param-name skew is invisible from the guest side as well. */
  @Test fun guestToHost_paramRenamed_compatible() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<RenameV1>(
        "hostRename",
        object : RenameV1 {
        override fun k(a: SkewPayload) = "k:${a.a}"
      },
      )
      assertObserved("B rename(V2).k on host V1 (param-name skew invisible)", "k:y") {
        zipline.callGuest("callHostRenameK", "hostRename", "y", 2)
      }
    }
  }

  // ---- whose default wins -------------------------------------------------------------------

  /** New caller's default wins: the guest's "V2" crosses; the host's "V1" default is dead. */
  @Test fun guestToHost_methodDefaultDiffers_newCallerWins() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<DefaultsV1>(
        "hostDefaults",
        object : DefaultsV1 {
        override fun d(p: SkewPayload, extra: String) = "d:${p.a}:$extra"
      },
      )
      assertObserved("B defaults(V2).d omit extra on host V1", "d:x:V2") {
        zipline.callGuest("callHostDefaultsD", "hostDefaults", "x")
      }
    }
  }

  /** Old caller's default wins: the guest's "V1" crosses; the new host's "V2" default is dead. */
  @Test fun guestToHost_methodDefaultDiffers_oldCallerWins() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<DefaultsV2>(
        "hostDefaults",
        object : DefaultsV2 {
        override fun d(p: SkewPayload, extra: String) = "d:${p.a}:$extra"
      },
      )
      assertObserved("B defaults(V1).d omit extra on host V2", "d:x:V1") {
        zipline.callGuest("callHostDefaultsDV1", "hostDefaults", "x")
      }
    }
  }

  // ---- type changes -------------------------------------------------------------------------

  /** A parameter retyped to an unrelated type, from the guest side. */
  @Test fun guestToHost_paramRetypedUnrelated_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<RetypeV2>(
        "hostRetype",
        object : RetypeV2 {
        override fun m(p: String) = "m:$p"
        override fun n(p: SkewBase) = "n:${(p as SkewPayload).a}"
        override fun o() = "o"
        override fun q(a: String?) = "q:${a ?: "null"}"
        override fun r(): String? = "r"
      },
      )
      assertThrowsContaining("B retype(V1).m on host V2", "JsException", mismatch) {
        zipline.callGuest("callHostRetypeM", "hostRetype", "x", 1)
      }
    }
  }

  /** Widening to a supertype changes the signature string, from the guest side. */
  @Test fun guestToHost_paramWidenedToSupertype_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<RetypeV2>(
        "hostRetype",
        object : RetypeV2 {
        override fun m(p: String) = "m:$p"
        override fun n(p: SkewBase) = "n:${(p as SkewPayload).a}"
        override fun o() = "o"
        override fun q(a: String?) = "q:${a ?: "null"}"
        override fun r(): String? = "r"
      },
      )
      assertThrowsContaining("B retype(V1).n on host V2", "JsException", mismatch) {
        zipline.callGuest("callHostRetypeN", "hostRetype", "x", 1)
      }
    }
  }

  /** A changed return type, from the guest side. */
  @Test fun guestToHost_returnTypeChanged_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<RetypeV2>(
        "hostRetype",
        object : RetypeV2 {
        override fun m(p: String) = "m:$p"
        override fun n(p: SkewBase) = "n:${(p as SkewPayload).a}"
        override fun o() = "o"
        override fun q(a: String?) = "q:${a ?: "null"}"
        override fun r(): String? = "r"
      },
      )
      assertThrowsContaining("B retype(V1).o on host V2", "JsException", mismatch) {
        zipline.callGuest("callHostRetypeO", "hostRetype")
      }
    }
  }

  /** Param nullability, from the guest side. */
  @Test fun guestToHost_paramNullabilityChanged_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<RetypeV2>(
        "hostRetype",
        object : RetypeV2 {
        override fun m(p: String) = "m:$p"
        override fun n(p: SkewBase) = "n:${(p as SkewPayload).a}"
        override fun o() = "o"
        override fun q(a: String?) = "q:${a ?: "null"}"
        override fun r(): String? = "r"
      },
      )
      assertThrowsContaining("B retype(V1).q on host V2", "JsException", mismatch) {
        zipline.callGuest("callHostRetypeQ", "hostRetype", "x")
      }
    }
  }

  /** Return nullability, from the guest side. */
  @Test fun guestToHost_returnNullabilityChanged_mismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<RetypeV2>(
        "hostRetype",
        object : RetypeV2 {
        override fun m(p: String) = "m:$p"
        override fun n(p: SkewBase) = "n:${(p as SkewPayload).a}"
        override fun o() = "o"
        override fun q(a: String?) = "q:${a ?: "null"}"
        override fun r(): String? = "r"
      },
      )
      assertThrowsContaining("B retype(V1).r on host V2", "JsException", mismatch) {
        zipline.callGuest("callHostRetypeR", "hostRetype")
      }
    }
  }

  // ---- val vs var ---------------------------------------------------------------------------

  /** A `val`/`var` swap from the guest side: getter matches, setter mismatches. */
  @Test fun guestToHost_propertyValVsVar_getterMatchesSetterMismatch() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<PropValService>(
        "hostProp",
        object : PropValService {
        override val prop: String get() = "v"
      },
      )
      assertObserved("B prop(VAR).get on host val", "v") {
        zipline.callGuest("callHostPropGet", "hostProp")
      }
      assertThrowsContaining("B prop(VAR).set on host val", "JsException", mismatch) {
        zipline.callGuest("callHostPropSet", "hostProp", "w")
      }
    }
  }

  // ---- failure modes ------------------------------------------------------------------------

  /**
   * A host-side throw during a guest->host call surfaces its message on both hosts, but only the JVM
   * engine stays usable afterwards.
   *
   * On Kotlin/Native the inbound callback (`outboundChannelCallCallback` in
   * `zipline/src/nativeMain/.../JsEngine.kt`) lets the host's Kotlin exception propagate across the
   * `staticCFunction` boundary into C++ instead of being converted into an error envelope. The
   * exception is still catchable at the call site, but the engine is left poisoned: every subsequent
   * call re-throws the same `JsException`. The JVM's JNI path converts the throw properly, so the
   * engine keeps serving. That asymmetry is a defect in the Kotlin/Native inbound path, not a
   * contract we want; it is pinned here so the day it is fixed this row fails and gets updated.
   */
  @Test fun guestToHost_hostThrows_messagePreserved_nativeEnginePoisoned() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<ThrowingService>(
        "hostBoom",
        object : ThrowingService {
        override fun go() = throw IllegalStateException("host-boom")
      },
      )
      // The wrapped throwable's toString() is platform dialect: `java.lang.` vs `kotlin.`.
      val dialect = if (isNativeHost()) "kotlin.IllegalStateException" else "java.lang.IllegalStateException"
      assertThrowsContaining("B host boom via guest", "JsException", "$dialect: host-boom") {
        zipline.callGuest("callHostBoomGo", "hostBoom")
      }
      if (isNativeHost()) {
        assertThrowsContaining(
          "B engine poisoned after host throw (K/N defect)",
          "JsException",
          "$dialect: host-boom",
        ) {
          zipline.take<LiveService>("live").ping()
        }
      } else {
        assertObserved("B live.ping after a host-side throw (JVM recovers)", "pong") {
          zipline.take<LiveService>("live").ping()
        }
      }
    }
  }

  /** `Long` crosses exactly from the guest side too, built inside the export from a string. */
  @Test fun guestToHost_longBoundaryCrossesExactly() = runBlocking(dispatcher) {
    withSkewGuest { zipline ->
      zipline.bind<LongService>(
        "hostLong",
        object : LongService {
        override fun big(x: Long) = x + 1L
      },
      )
      assertObserved("B long.big(2^53-2)", "9007199254740991") {
        zipline.callGuest("callHostLongBig", "hostLong", "9007199254740990")
      }
      assertObserved("B long.big(2^53)", "9007199254740993") {
        zipline.callGuest("callHostLongBig", "hostLong", "9007199254740992")
      }
    }
  }
}
