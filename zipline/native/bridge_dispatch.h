/*
 * Copyright (C) 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#ifndef ZIPLINE_BRIDGE_DISPATCH_H
#define ZIPLINE_BRIDGE_DISPATCH_H

#include "quickjs/quickjs.h"
#include <jni.h>
#include <string.h>


#ifdef __cplusplus
extern "C" {
#endif

typedef jobject (*BridgeConverterFn)(JNIEnv *env, JSContext *ctx, JSValue jsObj);

/**
 * How the guest classified a Kotlin/JS collection, from the value-ops `kind` call. The codes are the
 * guest's own (`BridgeValueOps` on the JS side). Plain C, not an `enum class`: the generated bridge
 * sources are compiled as C (clang -std=gnu99) and name these constants too.
 */
typedef enum CollectionKind {
  COLLECTION_KIND_NONE = 0,
  COLLECTION_KIND_MAP = 1,
  COLLECTION_KIND_SET = 2,
  COLLECTION_KIND_LIST = 3,
} CollectionKind;







/** Pack a bridge converter pointer into a JSValue (as float64, bit-preserving). */
static inline JSValue bridgeConverterToJSValue(JSContext* ctx, BridgeConverterFn fn) {
    union {
        double d;
        BridgeConverterFn fn;
    } u;
    u.fn = fn;
    return JS_NewFloat64(ctx, u.d);
}
/** Unpack a JSValue (float64) back to a bridge converter. Returns NULL if undefined. */
static inline BridgeConverterFn bridgeConverterFromJSValue(JSValue v) {
    if (JS_IsUndefined(v)) return NULL;
    union {
        double d;
        BridgeConverterFn fn;
    } u;
    u.d = JS_VALUE_GET_FLOAT64(v);
    return u.fn;
}
/** Register a JNI init function (caches class/method refs on JVM thread). */
void addBridgeInit(void (*fn)(JNIEnv* env));

/** Register a bridge FQN → converter mapping. */

void addBridgeEntry(const char* fq, BridgeConverterFn fn);
void init_all(JNIEnv* env);

/** Install __bridgeRegister on global and run register_all. */
void register_all(JSContext* ctx);
/** Convert any JS value to a Java object. Returns NULL for null/undefined/unrecognized. */
jobject bridgeForAny(JNIEnv *env, JSContext *ctx, JSValue val);

/**
 * Create a new JS instance whose prototype is the EXISTING retained guest prototype for [fq]
 * (registered by the guest's module-load __bridgeRegister). The prototype is never created or
 * cloned; the caller owns the returned value.
 *
 * Fails loudly: throws IllegalStateException (left pending on [env]) when the runtime has no
 * Context, when no prototype is registered for [fq], or when QuickJS cannot allocate the
 * instance. JS_UNDEFINED is returned only on those paths — never as a value a caller may
 * proceed with, since `undefined` is indistinguishable from real data downstream.
 */
JSValue bridgeNewJsObject(JNIEnv *env, JSContext *ctx, const char *fq);

/**
 * Convert any Java object to its JS counterpart: boxed primitives, String, List (via the
 * guest's newArrayList factory), Map (via the guest's newLinkedHashMap factory), arrays, and
 * @WithHost2JSBridge objects (via virtual convertToJs(J)J dispatch). On failure a Java
 * exception is left pending and JS_NULL is returned; the caller MUST check ExceptionCheck
 * before using the result.
 */
JSValue bridgeAnyToJs(JNIEnv *env, JSContext *ctx, jobject obj);

/**
 * Convert a Java long to a real Kotlin/JS kotlin.Long instance via the guest's retained
 * newLong factory. On failure (factory not registered) an IllegalStateException is thrown and
 * JS_NULL is returned with the exception pending; the caller MUST check ExceptionCheck.
 */
JSValue bridgeLongToJs(JNIEnv *env, JSContext *ctx, jlong value);
#ifdef __cplusplus
}
#endif

/**
 * Decode a Kotlin/JS collection into a JVM collection ([kind]: which one it is, see CollectionKind),
 * driving the iteration through the guest's collection accessors (see
 * app.cash.zipline.BridgeCollectionOps — Kotlin/JS mangles the stdlib member names, so the host
 * cannot walk a Kotlin/JS collection itself) and converting keys/elements with [keyConverter] and
 * [valueConverter] (use bridgeForAny when the element types are unknown, as for property values).
 * Returns a java.util.LinkedHashMap, LinkedHashSet or ArrayList.
 */
#ifdef __cplusplus
extern "C"
#endif
jlong bridgeJsLongValue(JNIEnv *env, JSContext *ctx, JSValue val);

#ifdef __cplusplus
extern "C"
#endif
jint bridgeJsEnumOrdinal(JNIEnv *env, JSContext *ctx, JSValue val);

#ifdef __cplusplus
extern "C"
#endif
jobject bridgeTryUnwrapLong(JNIEnv *env, JSContext *ctx, JSValue val);

#ifdef __cplusplus
extern "C"
#endif
jobject bridgeCollectionToJava(JNIEnv *env, JSContext *ctx, JSValue val, CollectionKind kind,
                               BridgeConverterFn keyConverter, BridgeConverterFn valueConverter);

#endif
