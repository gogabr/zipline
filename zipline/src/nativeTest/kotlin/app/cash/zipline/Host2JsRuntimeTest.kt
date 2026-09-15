package app.cash.zipline

import app.cash.zipline.quickjs.*
import kotlinx.cinterop.*
import kotlin.test.*

/**
 * Runtime tests for the host2js helpers (anyToJs, kotlinLongToJs, setJsProperty) against a real
 * QuickJS context. kotlinLongToJs needs the guest's `newLong` factory, so the test registers a
 * minimal JS factory (the same shape the generated __BridgeRuntimeFactories.newLong produces)
 * before exercising it.
 */
@OptIn(ExperimentalForeignApi::class)
class Host2JsRuntimeTest {
  private val quickJs = QuickJs.create()
  private val ctx get() = quickJs.jsContext
  private val evalCtx get() = quickJs.contextForCompiling

  @AfterTest
  fun tearDown() {
    quickJs.close()
  }

  /**
   * Evaluate a script in the runtime context, mirroring QuickJs.execute: the script is compiled
   * in the compiling context and executed via JS_EvalFunction in the runtime context. The result
   * is an owned JS value in the same context the host2js helpers use (a function created by
   * plain JS_Eval in the compiling context is a different context's object and cannot be called
   * with JS_Call from here).
   */
  private fun evalInRuntime(script: String): CValue<JSValue> {
    val bytecode = quickJs.compile(script, "test.js")
    @Suppress("UNCHECKED_CAST") // ByteVar and UByteVar have the same bit layout.
    val bytecodeRef = bytecode.refTo(0) as CValuesRef<UByteVar>
    val obj = JS_ReadObject(
      ctx,
      bytecodeRef,
      bytecode.size.convert(),
      JS_READ_OBJ_BYTECODE or JS_READ_OBJ_REFERENCE or JS_EVAL_FLAG_STRICT,
    )
    if (JS_IsException(obj) != 0) {
      throw QuickJsException("JS eval failed: $script")
    }
    val result = JS_EvalFunction(ctx, obj)
    if (JS_IsException(result) != 0) {
      JS_FreeValue(ctx, result)
      throw QuickJsException("JS eval failed: $script")
    }
    return result
  }

  private fun registerNewLongFactory() {
    val factory = evalInRuntime("((low, high) => ({low_1: low, high_1: high}))")
    quickJs.bridgeNewLong?.let { JS_FreeValue(ctx, it) }
    quickJs.bridgeNewLong = factory
  }

  private fun free(value: CValue<JSValue>) {
    JS_FreeValue(ctx, value)
  }

  @Test
  fun `kotlinLongToJs round-trips positive and negative longs`() {
    registerNewLongFactory()
    for (expected in longArrayOf(0L, 1L, -1L, 42L, -42L, Int.MAX_VALUE.toLong(), Int.MIN_VALUE.toLong(),
      0x123456789ABCDEFL, -0x123456789ABCDEFL, Long.MAX_VALUE, Long.MIN_VALUE)) {
      val jsLong = kotlinLongToJs(ctx, expected)
      try {
        assertEquals(expected, JsNumberToLong(ctx, jsLong), "round-trip of $expected")
      } finally {
        free(jsLong)
      }
    }
  }

  @Test
  fun `kotlinLongToJs creates an object with low and high halves`() {
    registerNewLongFactory()
    val jsLong = kotlinLongToJs(ctx, 0x123456789ABCDEFL)
    try {
      // 0x123456789ABCDEF = low 0x89ABCDEF (-1985229329 as signed int), high 0x01234567.
      assertEquals(-1985229329.0, bridgeForAny(ctx, JS_GetPropertyStr(ctx, jsLong, "low_1")))
      assertEquals(19088743.0, bridgeForAny(ctx, JS_GetPropertyStr(ctx, jsLong, "high_1")))
    } finally {
      free(jsLong)
    }
  }

  @Test
  fun `anyToJs round-trips primitives through bridgeForAny`() {
    assertEquals(42.0, bridgeForAny(ctx, anyToJs(ctx, 42)))
    assertEquals(1.5, bridgeForAny(ctx, anyToJs(ctx, 1.5)))
    assertEquals("hello", bridgeForAny(ctx, anyToJs(ctx, "hello")))
    assertEquals(true, bridgeForAny(ctx, anyToJs(ctx, true)))
    assertEquals(null, bridgeForAny(ctx, anyToJs(ctx, null)))
  }

  @Test
  fun `anyToJs long uses the newLong factory`() {
    registerNewLongFactory()
    val jsLong = anyToJs(ctx, 123456789L)
    try {
      assertEquals(123456789L, JsNumberToLong(ctx, jsLong))
    } finally {
      free(jsLong)
    }
  }

  @Test
  fun `setJsProperty defines an own data property`() {
    val obj = JS_NewObject(ctx)
    try {
      setJsProperty(ctx, obj, "answer", anyToJs(ctx, 42))
      val readBack = JS_GetPropertyStr(ctx, obj, "answer")
      try {
        assertEquals(42.0, bridgeForAny(ctx, readBack))
      } finally {
        free(readBack)
      }
    } finally {
      free(obj)
    }
  }
}
