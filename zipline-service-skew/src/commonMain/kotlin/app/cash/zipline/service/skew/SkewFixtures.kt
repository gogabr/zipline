package app.cash.zipline.service.skew

import app.cash.zipline.ZiplineService
import kotlinx.serialization.Serializable

/**
 * Version-skew fixtures for the real-engine service contract.
 *
 * Each fixture ships both the pre-change (V1) and post-change (V2) shape of a service in one
 * compilation. The JSON service path keys methods by signature hash, not by FQN, so the two shapes
 * coexist without collision: the guest binds one shape under a wire name and the host takes the
 * other, which is the skew. The same fixture source compiles to both the JS guest and the host, so
 * a given shape's signature string is identical on both sides of the boundary.
 */

@Serializable
open class SkewBase

@Serializable
data class SkewPayload(val a: String, val b: Int = 7) : SkewBase()

interface LiveService : ZiplineService {
  fun ping(): String
}

interface GoneService : ZiplineService {
  fun use(): String
}

interface MethodsV1 : ZiplineService {
  fun shared(): String
}

interface MethodsV1b : ZiplineService {
  fun shared(): String
  fun removedInV2(): String
}

interface MethodsV2 : ZiplineService {
  fun shared(): String
  fun added(): String
}

interface ParamsV1 : ZiplineService {
  fun f(p: SkewPayload): String
  fun h(a: String): String

  // Present in both versions. V2 adds a defaulted `extra`, which changes the arity, the signature
  // string, and therefore the function id: the two `e` shapes are NOT wire-compatible.
  fun e(p: SkewPayload): String
}

interface ParamsV1b : ZiplineService {
  fun f(p: SkewPayload): String
  fun h(a: String): String
  fun del(p: SkewPayload): String
}

interface ParamsV2 : ZiplineService {
  fun f(p: SkewPayload, extra: String): String
  fun g(p: SkewPayload, extra: String = "d"): String
  fun h(a: String = "d"): String

  // Same name and receiver as ParamsV1.e, plus a defaulted parameter. The default makes this a
  // source-compatible evolution for Kotlin callers, but the id differs from ParamsV1.e.
  fun e(p: SkewPayload, extra: String = "d"): String
}

interface RenameV1 : ZiplineService {
  fun k(a: SkewPayload): String
}

interface RenameV2 : ZiplineService {
  fun k(b: SkewPayload): String
}

interface RetypeV1 : ZiplineService {
  fun m(p: SkewPayload): String
  fun n(p: SkewPayload): String
  fun o(): SkewPayload
  fun q(a: String): String
  fun r(): String
}

interface RetypeV2 : ZiplineService {
  fun m(p: String): String
  fun n(p: SkewBase): String
  fun o(): String
  fun q(a: String?): String
  fun r(): String?
}

interface SuspendV1 : ZiplineService {
  fun s(a: String): String
}

interface SuspendV2 : ZiplineService {
  suspend fun s(a: String): String
}

interface PropValService : ZiplineService {
  val prop: String
}

interface PropVarService : ZiplineService {
  var prop: String
}

interface ThrowingService : ZiplineService {
  fun go(): String
}

interface LongService : ZiplineService {
  fun big(x: Long): Long
}

/**
 * Service-method default arguments, with a *different* default per version.
 *
 * Defaults are absent from the signature string, so both shapes share one function id and are
 * wire-compatible. The contract this pins: a default is materialized by the **caller's** compiler
 * at the call site and sent on the wire, so the callee's own default is never consulted.
 */
interface DefaultsV1 : ZiplineService {
  fun d(p: SkewPayload, extra: String = "V1"): String
}

interface DefaultsV2 : ZiplineService {
  fun d(p: SkewPayload, extra: String = "V2"): String
}
