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
/** If val is a Kotlin/JS Long ({low_1, high_1}), return a boxed java.lang.Long, else NULL. */
jobject bridgeTryUnwrapLong(JNIEnv *env, JSContext *ctx, JSValue val);

/**
 * Create a new JS instance whose prototype is the EXISTING retained guest prototype for [fq]
 * (registered by the guest's module-load __bridgeRegister). The prototype is never created or
 * cloned; the caller owns the returned value. Returns JS_UNDEFINED when the prototype has not
 * been registered — callers must treat that as a crash, never a fallback.
 */
JSValue bridgeNewJsObject(JSContext *ctx, const char *fq);

/**
 * Convert any Java object to its JS counterpart: boxed primitives, String, List (via the
 * guest's newArrayList factory), Map (via the guest's newLinkedHashMap factory), arrays, and
 * @WithHost2JSBridge objects (via virtual convertToJs(J)J dispatch). On failure a Java
 * exception is left pending and JS_NULL is returned; the caller MUST check ExceptionCheck
 * before using the result.
 */
JSValue bridgeAnyToJs(JNIEnv *env, JSContext *ctx, jobject obj);
#ifdef __cplusplus
}
#endif

#endif
