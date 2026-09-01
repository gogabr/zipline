/*
 * Handwritten JNI hooks for Host2JsBridgeEndToEndTest (jvmTest).
 *
 * Drives the host2js bridge through the public bridge_dispatch.h API:
 *   roundTrip: bridgeAnyToJs (virtual convertToJs(J)J dispatch + primitives/collections/Long)
 *              -> JS -> bridgeForAny back to a Java object.
 *   setJsGlobalNative: bridgeAnyToJs -> globalThis.__host2js_test (the JSON.stringify step
 *              runs via QuickJs.evaluateForBridge, because the execution context is
 *              created with JS_NewContextNoEval and cannot eval scripts itself).
 *
 * The QuickJs JSContext pointer is obtained via the private Kotlin members `context` (field)
 * and `getJsContext(J)J` (method); JNI finds private members (test-only, documented).
 */
#include <jni.h>
#include "bridge_dispatch.h"
#include "quickjs/quickjs.h"

static jlong jsContextOf(JNIEnv* env, jobject quickJs) {
  jclass quickJsClass = (*env)->GetObjectClass(env, quickJs);
  jfieldID contextField = (*env)->GetFieldID(env, quickJsClass, "context", "J");
  if ((*env)->ExceptionCheck(env)) return 0;
  jlong context = (*env)->GetLongField(env, quickJs, contextField);
  jmethodID getJsContext = (*env)->GetMethodID(env, quickJsClass, "getJsContext", "(J)J");
  if ((*env)->ExceptionCheck(env)) return 0;
  jlong ctx = (*env)->CallLongMethod(env, quickJs, getJsContext, context);
  (*env)->DeleteLocalRef(env, quickJsClass);
  return ctx;
}

JNIEXPORT jobject JNICALL Java_app_cash_zipline_bridge_test_TestHost2Js_roundTripNative(
    JNIEnv* env, jobject thiz, jobject quickJs, jobject obj) {
  jlong ctxPtr = jsContextOf(env, quickJs);
  if ((*env)->ExceptionCheck(env)) return NULL;
  JSContext* ctx = (JSContext*)ctxPtr;
  JSValue v = bridgeAnyToJs(env, ctx, obj);
  if ((*env)->ExceptionCheck(env)) {
    // Pending exception (unbridgeable value or missing runtime factory) propagates to the
    // Kotlin caller and fails the test loudly.
    return NULL;
  }
  jobject back = bridgeForAny(env, ctx, v);
  JS_FreeValue(ctx, v);
  return back;
}

JNIEXPORT void JNICALL Java_app_cash_zipline_bridge_test_TestHost2Js_setJsGlobalNative(
    JNIEnv* env, jobject thiz, jobject quickJs, jobject obj) {
  jlong ctxPtr = jsContextOf(env, quickJs);
  if ((*env)->ExceptionCheck(env)) return;
  JSContext* ctx = (JSContext*)ctxPtr;
  JSValue v = bridgeAnyToJs(env, ctx, obj);
  if ((*env)->ExceptionCheck(env)) return;

  JSValue global = JS_GetGlobalObject(ctx);
  JS_SetPropertyStr(ctx, global, "__host2js_test", v);
  JS_FreeValue(ctx, global);
}
