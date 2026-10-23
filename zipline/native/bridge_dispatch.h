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

#include <jsi/jsi.h>
#include <jni.h>

#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace jsi = facebook::jsi;

/** Reports a bridge failure that would otherwise be invisible (a dropped or null-decoded value). */
void jsiLogBridgeError(const std::string &message);

/** The JS class name of [v] as reported by `constructor.name`, or "" when it has none. */
std::string jsiClassOf(facebook::jsi::Runtime &rt, const facebook::jsi::Value &v);

#ifdef __cplusplus
extern "C" {
#endif

typedef struct JniBridgeDispatch {
    jobject (*toJavaObject)(JNIEnv *env, facebook::jsi::Runtime& rt, const facebook::jsi::Value& jsObj);
} JniBridgeDispatch;

/** Register a JNI init function (caches class/method refs on JVM thread). */
void addBridgeInit(void (*fn)(JNIEnv* env));

/** Register a bridge FQN → converter mapping. */
void addBridgeEntry(const char* fq, jobject (*fn)(JNIEnv *env, facebook::jsi::Runtime& rt, const facebook::jsi::Value& jsObj));

/** Run all registered JNI init functions. */
void init_all(JNIEnv* env);

/** Install __bridgeRegister on global. */
void register_all(facebook::jsi::Runtime& rt);

#ifdef __cplusplus
}
#endif

// -- JSI value helpers (explicit runtime; no macros, no hidden context) --

// JS tag enum (ordered to match common checks)
enum {
  JS_TAG_INT = 0, JS_TAG_BOOL = 1, JS_TAG_NULL = 2, JS_TAG_UNDEFINED = 3,
  JS_TAG_STRING = 4, JS_TAG_OBJECT = 5, JS_TAG_FLOAT64 = 7
};

// Tag emulation: maps jsi::Value to a numeric tag
int jsi_value_tag(jsi::Runtime &rt, const jsi::Value &v);

// Primitive value accessors (no runtime required).
jint jsi_value_get_int(const jsi::Value &v);

jdouble jsi_value_get_float64(const jsi::Value &v);

jboolean jsi_value_get_bool(const jsi::Value &v);

// Reconstruct 64-bit pointer from two 32-bit halves stored as JS doubles.
// ARM64 pointers need 64 bits; JS double mantissa is only 53 bits,
// so we split into bridge_dispatch_low / bridge_dispatch_high.
intptr_t jsi_get_bridge_dispatch(jsi::Runtime &rt, const jsi::Value &objVal);

/** Converters handed to the collection decoder: one for elements, or one each for map keys/values. */
typedef jobject (*JsiBridgeConverterFn)(JNIEnv *env, facebook::jsi::Runtime &rt, const facebook::jsi::Value &v);

// Defined after the collection decoder; declared here so the decoder can take it as a converter.
jobject jsi_value_to_boxed(
    JNIEnv *env, facebook::jsi::Runtime &rt, const facebook::jsi::Value &v, bool loud = true);

/** The loud boxed decoder as a converter function pointer (the default argument has no address). */
jobject jsiValueToBoxedLoud(
    JNIEnv *env, facebook::jsi::Runtime &rt, const facebook::jsi::Value &v);

// ---------------------------------------------------------------------------
// Guest value ops.
//
// A host-side decoder often has to convert a value whose static type it does not know
// (property values cross as Any?). For collections, Longs and enums it cannot look for a
// well-known shape: Kotlin/JS mangles the member names of the stdlib and production builds drop
// them altogether, so probing names from the host works in development and fails silently in
// production. So the guest answers the type question with the compiler's own `is` check and
// drives the access; the host only calls.
//
// The ops object is installed on globalThis by `app.cash.zipline.publishValueOps()`, which the
// bridge plugin calls from its module-load hook (see JsGenerator.injectModuleLoadBridgeRegistration).
// ---------------------------------------------------------------------------

static const char *VALUE_OPS_GLOBAL = "__zipline_bridgeValueOps";

/** The guest's collection/Long/enum accessors, or undefined when the guest never published them. */
jsi::Value jsiValueOps(jsi::Runtime &rt);

/** Calls `ops.<name>(argument)`; returns undefined when the op is missing or threw. */
jsi::Value jsiCallValueOp(
    jsi::Runtime &rt, const jsi::Value &ops, const char *name, const jsi::Value &argument);

/** Throws when the guest's value ops are missing: a decode needs them and cannot guess. */
void jsiRequireValueOps(JNIEnv *env, jsi::Runtime &rt, const char *what);

/** The boxed `kotlin.Long` in [v] as a Java long, asked of the guest. 0 when [v] is not one. */
jlong jsiBridgeLongValue(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v);

/** Boxed `kotlin.Long`, or nullptr when [v] is not one. */
jobject jsiBridgeTryUnwrapLong(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v);

/** Ordinal of the enum instance in [v], or -1 when it is not an enum. */
jint jsiBridgeEnumOrdinal(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v);

/** How the guest classified [v], or NONE when it is not a collection. Mirrors BridgeValueOps. */
enum {
  BRIDGE_COLLECTION_NONE = 0,
  BRIDGE_COLLECTION_MAP = 1,
  BRIDGE_COLLECTION_SET = 2,
  BRIDGE_COLLECTION_LIST = 3,
  BRIDGE_COLLECTION_PAIR = 4,
  BRIDGE_COLLECTION_TRIPLE = 5,
  BRIDGE_COLLECTION_ENTRY = 6
};

int jsiBridgeCollectionKind(jsi::Runtime &rt, const jsi::Value &v);

/**
 * JS built-ins (plain objects, arrays, dates, ...) and Kotlin's collection/Long/Unit wrappers
 * decode without a bridge converter; anything else that arrives as a class instance must have one.
 */
bool jsiIsUnbridgedDecodeExemptClass(const std::string &name);

/**
 * Throw IllegalStateException naming the JS class of [v] when it looks like a Kotlin class
 * instance that no bridge converter was registered for. Returning null here used to surface as a
 * confusing cast/NPE far from the cause (e.g. a List<TopBarIcon> arriving as [null] because
 * TopBarIcon was not annotated), so name the class instead.
 */
void jsiThrowUnbridgedJsObject(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v);

// JNI caches for the collection decoder. Their definitions live in bridge_dispatch.cpp, so one
// translation unit owns each cache; jmethodIDs are process-wide, and each cache keeps its own
// global refs.

// JNI caches for the collection decoder. These helpers live in bridge_dispatch.cpp, so one
// translation unit owns the caches; jmethodIDs are process-wide, and each cache keeps its own
// global refs.
typedef struct JsiCollectionRefs {
  jclass arrayListClass;
  jmethodID arrayListInit;
  jmethodID arrayListAdd;
  jclass linkedHashMapClass;
  jmethodID linkedHashMapInit;
  jmethodID mapPut;
  jclass linkedHashSetClass;
  jmethodID linkedHashSetInit;
  jmethodID setAdd;
} JsiCollectionRefs;

/** Cached JNI handles for the container decoder. */
typedef struct JsiContainerRefs {
  jclass pairClass;
  jmethodID pairCtor;
  jclass tripleClass;
  jmethodID tripleCtor;
  jclass entryClass;
  jmethodID entryCtor;
} JsiContainerRefs;

/** Cached JNI handles for the host-to-JS container conversion. */
typedef struct JsiHost2JsContainerRefs {
  jclass pairClass;
  jmethodID pairFirst;
  jmethodID pairSecond;
  jclass tripleClass;
  jmethodID tripleFirst;
  jmethodID tripleSecond;
  jmethodID tripleThird;
  jclass entryClass;
  jmethodID entryKey;
  jmethodID entryValue;
} JsiHost2JsContainerRefs;

bool jsiCollectionRefs(JNIEnv *env, JsiCollectionRefs &refs);

/**
 * Decode a guest collection into a Java collection. The guest identifies the kind and drives the
 * iteration through its value ops because Kotlin/JS mangles the stdlib member names (and drops
 * them in production builds), so a host-side walk works in development and yields an empty
 * collection in production.
 */
jobject jsiCollectionToJava(
    JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v, int kind,
    JsiBridgeConverterFn keyConverter, JsiBridgeConverterFn valueConverter);

// -- Pair / Triple / Map.Entry decoding -------------------------------------

/** Cached JNI handles for the container decoder (one cache per translation unit). */

bool jsiContainerRefs(JNIEnv *env, JsiContainerRefs &refs);

/**
 * Calls `ops.<name>` and returns its result. Unlike jsiCallValueOp, a missing op or a failed call
 * is an error: a Kotlin `Any?` component arrives as JS null, never undefined, so undefined here
 * means the guest ops are incompatible, not that the component is absent.
 */
jsi::Value jsiCallValueOpRequired(
    JNIEnv *env, jsi::Runtime &rt, const jsi::Value &ops, const char *name,
    const jsi::Value &argument);

/** Decode a guest Pair, Triple or Map.Entry into its Java counterpart. */
jobject jsiBridgeContainerToJava(
    JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v, int kind,
    JsiBridgeConverterFn firstConverter, JsiBridgeConverterFn secondConverter,
    JsiBridgeConverterFn thirdConverter);

/** Typed decoders used by generated bridges: the field's declared element types pick the readers. */
jobject jsiBridgePairToJava(
    JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v,
    JsiBridgeConverterFn first, JsiBridgeConverterFn second);

jobject jsiBridgeTripleToJava(
    JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v,
    JsiBridgeConverterFn first, JsiBridgeConverterFn second, JsiBridgeConverterFn third);

jobject jsiBridgeEntryToJava(
    JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v,
    JsiBridgeConverterFn key, JsiBridgeConverterFn value);

// Box a JS value into a Java object (Boolean/Integer/Double/String/Long/collections), or
// dispatch a bridged object. Returns NULL for null/undefined/unhandled. When [loud] is set, a
// class instance that has no converter throws instead of decoding to null.
jobject jsi_value_to_boxed(
    JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v, bool loud);

// A host value with a @WithHost2JSBridge counterpart crosses to the guest as an instance whose
// prototype is the guest class's own prototype, with every field defined as an own data
// property. The guest registers its prototypes and the runtime factories it needs (Long,
// ArrayList, LinkedHashMap) through __bridgeRegister / __bridgeRegisterRuntime at module load;
// the host keeps both in JS globals, so a converter compiled into a separate consumer library
// needs nothing but the runtime and this header.
//
// The per-class convertToJs(J)J member (injected by the bridge plugin's IR extension) is
// dispatched virtually, so an annotated subclass converts through the nearest annotated
// ancestor when it has no member of its own.
// ---------------------------------------------------------------------------

static const char *HOST2JS_PROTOTYPES_GLOBAL = "__zipline_bridgePrototypes";
static const char *HOST2JS_FACTORIES_GLOBAL = "__zipline_bridgeFactories";

/** The guest-registered prototype for [fq], or undefined. */
jsi::Value jsiHost2JsPrototype(jsi::Runtime &rt, const char *fq);

/**
 * A new JS instance of [fq] whose prototype is the guest's own. Throws when the guest never
 * registered the class: `undefined` is a valid JS value, so a caller that skipped the check would
 * silently ship a payload field of undefined.
 */
jsi::Value jsiHost2JsNewObject(JNIEnv *env, jsi::Runtime &rt, const char *fq);

/** Define an own data property on [target]; a [Set] would fire a prototype accessor instead. */
void jsiHost2JsDefineProperty(
    jsi::Runtime &rt, const jsi::Value &target, const char *name, jsi::Value value);

/** A value from the guest's runtime factories (`newLong`/`newArrayList`/`newLinkedHashMap`). */
jsi::Value jsiHost2JsRuntimeFactory(JNIEnv *env, jsi::Runtime &rt, const char *name);

/** A `kotlin.Long` built by the guest, so the guest sees a real Kotlin/JS Long. */
jsi::Value jsiHost2JsLongToJs(JNIEnv *env, jsi::Runtime &rt, jlong value);

jsi::Value jsiHost2JsAnyToJs(JNIEnv *env, jsi::Runtime &rt, jobject obj);

/** The binary name of [cls] (`com.example.Outer$Inner`), or "" when it cannot be read. */
std::string jsiClassName(JNIEnv *env, jclass cls);

/** A Java map key as the guest stored it: boxed primitives and String keep their JS shape. */
jsi::Value jsiHost2JsKeyToJs(JNIEnv *env, jsi::Runtime &rt, jobject key);

/** A JS array of the elements of a Java collection, each converted host -> JS. */
jsi::Value jsiHost2JsToList(JNIEnv *env, jsi::Runtime &rt, jobject iterable);

/** True when [obj] is a Java array. [objClass] is its class, a local ref the caller owns. */
bool jsiIsJavaArray(JNIEnv *env, jobject obj, jclass objClass);

/**
 * The elements of a Java array as a JS array. The component type decides how they are read: a
 * primitive component through its typed accessor, anything else - including the nested arrays of a
 * Kotlin Array<Array<T>> - through this same converter.
 */
jsi::Value jsiArrayToJs(JNIEnv *env, jsi::Runtime &rt, jobject array);

/**
 * The guest counterpart of a host value: scalars and String directly, Long and the collections
 * through the guest's own factories (so the guest sees real Kotlin/JS values), arrays elementwise,
 * and anything else through the class's own convertToJs member.
 */
/** Cached JNI handles for the host-to-JS container conversion (one cache per translation unit). */

bool jsiHost2JsContainerRefs(JNIEnv *env, JsiHost2JsContainerRefs &refs);

/** Build the guest counterpart of a host Pair/Triple/Map.Entry through the guest factory. */
jsi::Value jsiHost2JsContainerToJs(
    JNIEnv *env, jsi::Runtime &rt, jobject obj,
    const jmethodID *getters, size_t count, const char *factoryName);

jsi::Value jsiHost2JsAnyToJs(JNIEnv *env, jsi::Runtime &rt, jobject obj);

#endif
