package app.cash.zipline.service.skew

import app.cash.zipline.Zipline

private val zipline by lazy { Zipline.get() }

/**
 * The JS guest for the service version-skew contract.
 *
 * Binds both shapes of each fixture under distinct wire names so the host can take the opposite
 * shape and exercise either skew direction:
 *  - bare name (`methods`) holds the V2 (post-change) shape.
 *  - `*Old` name (`methodsOld`) holds the V1 (pre-change) shape.
 *
 * The host-as-caller direction needs no JS entry point: the host takes a proxy and calls it across
 * the engine boundary. The guest-as-caller direction is driven by the `callHost*` functions below,
 * which take a host-bound service by name and invoke it. Their parameters are JS-primitive only
 * because `@JsExport` cannot carry the serializable fixture types; the payload is rebuilt inside.
 */

@JsExport
fun prepareSkewBridges() {
  zipline.bind<LiveService>(
    "live",
    object : LiveService {
    override fun ping() = "pong"
  },
  )

  // V2 shapes under bare names.
  zipline.bind<MethodsV2>(
    "methods",
    object : MethodsV2 {
    override fun shared() = "shared"
    override fun added() = "added"
  },
  )
  zipline.bind<ParamsV2>(
    "params",
    object : ParamsV2 {
    override fun f(p: SkewPayload, extra: String) = "f:${p.a}:$extra"
    override fun g(p: SkewPayload, extra: String) = "g:${p.a}:$extra"
    override fun h(a: String) = "h:$a"
    override fun e(p: SkewPayload, extra: String) = "e:${p.a}:$extra"
  },
  )
  zipline.bind<RenameV2>(
    "rename",
    object : RenameV2 {
    override fun k(b: SkewPayload) = "k:${b.a}"
  },
  )
  zipline.bind<RetypeV2>(
    "retype",
    object : RetypeV2 {
    override fun m(p: String) = "m:$p"
    override fun n(p: SkewBase) = "n:${(p as SkewPayload).a}"
    override fun o() = "o"
    override fun q(a: String?) = "q:${a ?: "null"}"
    override fun r(): String? = "r"
  },
  )
  zipline.bind<SuspendV2>(
    "susp",
    object : SuspendV2 {
    override suspend fun s(a: String) = "s:$a"
  },
  )
  zipline.bind<PropValService>(
    "prop",
    object : PropValService {
    override val prop: String get() = "v"
  },
  )
  zipline.bind<ThrowingService>(
    "boom",
    object : ThrowingService {
    override fun go() = throw IllegalStateException("boom")
  },
  )
  zipline.bind<LongService>(
    "long",
    object : LongService {
    override fun big(x: Long) = x + 1L
  },
  )
  zipline.bind<DefaultsV2>(
    "defaults",
    object : DefaultsV2 {
    override fun d(p: SkewPayload, extra: String) = "d:${p.a}:$extra"
  },
  )

  // V1 shapes under *Old names.
  zipline.bind<MethodsV1>(
    "methodsOld",
    object : MethodsV1 {
    override fun shared() = "shared"
  },
  )
  zipline.bind<ParamsV1>(
    "paramsOld",
    object : ParamsV1 {
    override fun f(p: SkewPayload) = "f:${p.a}"
    override fun h(a: String) = "h:$a"
    override fun e(p: SkewPayload) = "e:${p.a}"
  },
  )
  zipline.bind<RenameV1>(
    "renameOld",
    object : RenameV1 {
    override fun k(a: SkewPayload) = "k:${a.a}"
  },
  )
  zipline.bind<RetypeV1>(
    "retypeOld",
    object : RetypeV1 {
    override fun m(p: SkewPayload) = "m:${p.a}"
    override fun n(p: SkewPayload) = "n:${p.a}"
    override fun o() = SkewPayload("o")
    override fun q(a: String) = "q:$a"
    override fun r(): String = "r"
  },
  )
  zipline.bind<SuspendV1>(
    "suspOld",
    object : SuspendV1 {
    override fun s(a: String) = "s:$a"
  },
  )
  zipline.bind<PropVarService>(
    "propOld",
    object : PropVarService {
    override var prop: String = "v"
  },
  )
  zipline.bind<DefaultsV1>(
    "defaultsOld",
    object : DefaultsV1 {
    override fun d(p: SkewPayload, extra: String) = "d:${p.a}:$extra"
  },
  )
}

// Guest-as-caller: take a host-bound service by [name] and call it. Primitive-only signatures;
// the payload is rebuilt inside so @JsExport stays valid.

@JsExport
fun callHostMethodsShared(name: String): String = zipline.take<MethodsV2>(name).shared()

@JsExport
fun callHostMethodsAdded(name: String): String = zipline.take<MethodsV2>(name).added()

@JsExport
fun callHostMethodsSharedV1(name: String): String = zipline.take<MethodsV1>(name).shared()

@JsExport
fun callHostMethodsRemovedInV2(name: String): String = zipline.take<MethodsV1b>(name).removedInV2()

@JsExport
fun callHostBoomGo(name: String): String = zipline.take<ThrowingService>(name).go()

@JsExport
fun callHostParamsH(name: String, a: String): String = zipline.take<ParamsV2>(name).h(a)

@JsExport
fun callHostRenameK(name: String, a: String, b: Int): String = zipline.take<RenameV2>(name).k(SkewPayload(a, b))

// Omits `extra` at the call site: the guest's compiler materializes the DefaultsV2 default.
@JsExport
fun callHostDefaultsD(name: String, a: String): String = zipline.take<DefaultsV2>(name).d(SkewPayload(a))

// New guest (V2 `e`, extra defaulted) calling an old host bound as V1: the realistic production
// skew of a fresh JS bundle against a stale host.
@JsExport
fun callHostParamsE(name: String, a: String): String = zipline.take<ParamsV2>(name).e(SkewPayload(a))

// Direction-B twins for every host-as-caller shape except suspend (parked). Each export takes
// JS-primitive arguments and constructs the serializable DTO *inside* the body, so the @JsExport
// signature stays valid while the guest still drives the real service call across the boundary.
// DTO returns are reduced to a primitive (`.a`) before returning for the same reason.

// V2.f(p, extra) against a host that only has V1.f(p): required-param-add mismatch.
@JsExport
fun callHostParamsF(name: String, a: String, extra: String): String = zipline.take<ParamsV2>(name).f(SkewPayload(a), extra)

// V1b.del(p) against a host that dropped it in V2: method-delete mismatch.
@JsExport
fun callHostParamsDel(name: String, a: String): String = zipline.take<ParamsV1b>(name).del(SkewPayload(a))

// V1.h(a) against a V2 host whose h gained a default: compatible, caller's explicit arg wins.
@JsExport
fun callHostParamsHV1(name: String, a: String): String = zipline.take<ParamsV1>(name).h(a)

// V2.h() omitting the defaulted arg against a V1 host with no default: caller's "d" crosses.
@JsExport
fun callHostParamsHDefault(name: String): String = zipline.take<ParamsV2>(name).h()

// V1.m(SkewPayload) against a V2 host whose m takes String: unrelated-retype mismatch.
@JsExport
fun callHostRetypeM(name: String, a: String, b: Int): String = zipline.take<RetypeV1>(name).m(SkewPayload(a, b))

// V1.n(SkewPayload) against a V2 host whose n takes the SkewBase supertype: widening mismatch.
@JsExport
fun callHostRetypeN(name: String, a: String, b: Int): String = zipline.take<RetypeV1>(name).n(SkewPayload(a, b))

// V1.o(): SkewPayload against a V2 host whose o returns String: return-type mismatch.
@JsExport
fun callHostRetypeO(name: String): String = zipline.take<RetypeV1>(name).o().a

// V1.q(String) against a V2 host whose q takes String?: nullability mismatch.
@JsExport
fun callHostRetypeQ(name: String, a: String): String = zipline.take<RetypeV1>(name).q(a)

// V1.r(): String against a V2 host whose r returns String?: return-nullability mismatch.
@JsExport
fun callHostRetypeR(name: String): String = zipline.take<RetypeV1>(name).r()

// Guest takes the var shape against a host bound as val: getter matches, setter mismatches.
@JsExport
fun callHostPropGet(name: String): String = zipline.take<PropVarService>(name).prop

@JsExport
fun callHostPropSet(name: String, v: String): String {
  zipline.take<PropVarService>(name).prop = v
  return "set"
}

// Long crossing guest->host: the value is built inside from a string so @JsExport stays primitive.
@JsExport
fun callHostLongBig(name: String, x: String): String = zipline.take<LongService>(name).big(x.toLong()).toString()

// V2.g(p, extra="d") against a host that has no `g` at all: method-add mismatch from the guest side.
@JsExport
fun callHostParamsG(name: String, a: String): String = zipline.take<ParamsV2>(name).g(SkewPayload(a))

// Old guest (V1 1-arg e) calling a new host bound as V2 (2-arg e): old-caller direction.
@JsExport
fun callHostParamsEV1(name: String, a: String): String = zipline.take<ParamsV1>(name).e(SkewPayload(a))

// Old guest (V1 default "V1") calling a new host bound as V2 (default "V2"): caller's "V1" wins.
@JsExport
fun callHostDefaultsDV1(name: String, a: String): String = zipline.take<DefaultsV1>(name).d(SkewPayload(a))
