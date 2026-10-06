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
static inline void jsiLogBridgeError(const std::string &message) {
#ifdef __ANDROID__
  __android_log_print(ANDROID_LOG_ERROR, "BRIDGE", "%s", message.c_str());
#else
  fprintf(stderr, "BRIDGE: %s\n", message.c_str());
#endif
}

/** The JS class name of [v] as reported by `constructor.name`, or "" when it has none. */
static inline std::string jsiClassOf(facebook::jsi::Runtime &rt, const facebook::jsi::Value &v) {
  if (!v.isObject()) return "";
  facebook::jsi::Value ctor = v.asObject(rt).getProperty(rt, "constructor");
  if (!ctor.isObject()) return "";
  facebook::jsi::Value name = ctor.asObject(rt).getProperty(rt, "name");
  return name.isString() ? name.asString(rt).utf8(rt) : "";
}

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
static inline int jsi_value_tag(jsi::Runtime &rt, const jsi::Value &v) {
  if (v.isNumber()) {
    double d = v.asNumber();
    if (std::trunc(d) == d && d >= -2147483648.0 && d <= 2147483647.0)
      return JS_TAG_INT;
    return JS_TAG_FLOAT64;
  }
  if (v.isBool())   return JS_TAG_BOOL;
  if (v.isNull())   return JS_TAG_NULL;
  if (v.isUndefined()) return JS_TAG_UNDEFINED;
  if (v.isString()) return JS_TAG_STRING;
  if (v.isObject()) return JS_TAG_OBJECT;
  return -1;
}

// Primitive value accessors (no runtime required).
static inline jint jsi_value_get_int(const jsi::Value &v) {
  return static_cast<jint>(v.asNumber());
}

static inline jdouble jsi_value_get_float64(const jsi::Value &v) {
  return static_cast<jdouble>(v.asNumber());
}

static inline jboolean jsi_value_get_bool(const jsi::Value &v) {
  return static_cast<jboolean>(v.asBool());
}

// Reconstruct 64-bit pointer from two 32-bit halves stored as JS doubles.
// ARM64 pointers need 64 bits; JS double mantissa is only 53 bits,
// so we split into bridge_dispatch_low / bridge_dispatch_high.
static inline intptr_t jsi_get_bridge_dispatch(jsi::Runtime &rt, const jsi::Value &objVal) {
  if (!objVal.isObject()) return 0;
  jsi::Object obj = objVal.asObject(rt);
  jsi::Value lowVal = obj.getProperty(rt, "bridge_dispatch_low");
  jsi::Value highVal = obj.getProperty(rt, "bridge_dispatch_high");
  if (lowVal.isUndefined() || highVal.isUndefined()) return 0;
  int32_t low  = (int32_t)lowVal.asNumber();
  int32_t high = (int32_t)highVal.asNumber();
  return ((intptr_t)high << 32) | ((intptr_t)(uint32_t)low);
}

/** Converters handed to the collection decoder: one for elements, or one each for map keys/values. */
typedef jobject (*JsiBridgeConverterFn)(JNIEnv *env, facebook::jsi::Runtime &rt, const facebook::jsi::Value &v);

// Defined after the collection decoder; declared here so the decoder can take it as a converter.
static inline jobject jsi_value_to_boxed(
    JNIEnv *env, facebook::jsi::Runtime &rt, const facebook::jsi::Value &v, bool loud);

/** The loud boxed decoder as a converter function pointer (the default argument has no address). */
static inline jobject jsiValueToBoxedLoud(
    JNIEnv *env, facebook::jsi::Runtime &rt, const facebook::jsi::Value &v) {
  return jsi_value_to_boxed(env, rt, v, true);
}

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
static inline jsi::Value jsiValueOps(jsi::Runtime &rt) {
  jsi::Value ops = rt.global().getProperty(rt, VALUE_OPS_GLOBAL);
  if (!ops.isObject()) return jsi::Value::undefined();
  return ops;  // jsi::Value is move-only; a returned local is moved.
}

/** Calls `ops.<name>(argument)`; returns undefined when the op is missing or threw. */
static inline jsi::Value jsiCallValueOp(
    jsi::Runtime &rt, const jsi::Value &ops, const char *name, const jsi::Value &argument) {
  jsi::Value fn = ops.asObject(rt).getProperty(rt, name);
  if (!fn.isObject() || !fn.asObject(rt).isFunction(rt)) return jsi::Value::undefined();
  try {
    return fn.asObject(rt).asFunction(rt).call(rt, argument);
  } catch (const jsi::JSError &) {
    return jsi::Value::undefined();
  }
}

/** Throws when the guest's value ops are missing: a decode needs them and cannot guess. */
static inline void jsiRequireValueOps(JNIEnv *env, jsi::Runtime &rt, const char *what) {
  if (jsiValueOps(rt).isObject()) return;
  std::string message = std::string("host bridge: the guest did not publish its value ops ") +
                        what + " needs (globalThis." + VALUE_OPS_GLOBAL +
                        " is not an object); the guest module was not compiled with the bridge "
                        "plugin's module-load hook";
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}

/** The boxed `kotlin.Long` in [v] as a Java long, asked of the guest. 0 when [v] is not one. */
static inline jlong jsiBridgeLongValue(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v) {
  jsiRequireValueOps(env, rt, "a boxed Long");
  if (env->ExceptionCheck()) return 0;
  jsi::Value ops = jsiValueOps(rt);
  jsi::Value low = jsiCallValueOp(rt, ops, "longLow", v);
  if (!low.isNumber()) return 0;
  jsi::Value high = jsiCallValueOp(rt, ops, "longHigh", v);
  return (static_cast<jlong>(static_cast<int32_t>(high.asNumber())) << 32) |
         (static_cast<jlong>(static_cast<uint32_t>(static_cast<int32_t>(low.asNumber()))));
}

/** Boxed `kotlin.Long`, or nullptr when [v] is not one. */
static inline jobject jsiBridgeTryUnwrapLong(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v) {
  if (!v.isObject()) return nullptr;
  jsi::Value ops = jsiValueOps(rt);
  if (!ops.isObject()) return nullptr;
  jsi::Value low = jsiCallValueOp(rt, ops, "longLow", v);
  if (!low.isNumber()) return nullptr;
  jsi::Value high = jsiCallValueOp(rt, ops, "longHigh", v);
  jlong value = (static_cast<jlong>(static_cast<int32_t>(high.asNumber())) << 32) |
                (static_cast<jlong>(static_cast<uint32_t>(static_cast<int32_t>(low.asNumber()))));
  jclass c = env->FindClass("java/lang/Long");
  jmethodID m = env->GetMethodID(c, "<init>", "(J)V");
  jobject boxed = env->NewObject(c, m, value);
  env->DeleteLocalRef(c);
  if (env->ExceptionCheck()) return nullptr;
  return boxed;
}

/** Ordinal of the enum instance in [v], or -1 when it is not an enum. */
static inline jint jsiBridgeEnumOrdinal(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v) {
  jsiRequireValueOps(env, rt, "an enum");
  if (env->ExceptionCheck()) return -1;
  jsi::Value ops = jsiValueOps(rt);
  jsi::Value ordinal = jsiCallValueOp(rt, ops, "enumOrdinal", v);
  if (ordinal.isNumber() && ordinal.asNumber() >= 0) {
    return static_cast<jint>(ordinal.asNumber());
  }
  // No fallback on purpose. An object the host built for a host->JS conversion is not an enum
  // instance: its ordinal would be a host-chosen property the guest never reads, and the guest's
  // own identity comparisons against the real constants would fail. Reading it back here would
  // only make a round trip look correct while the guest side stays broken, so report "not an
  // enum" and let the caller fail loudly.
  return -1;
}

/** How the guest classified [v], or NONE when it is not a collection. Mirrors BridgeValueOps. */
enum {
  BRIDGE_COLLECTION_NONE = 0,
  BRIDGE_COLLECTION_MAP = 1,
  BRIDGE_COLLECTION_SET = 2,
  BRIDGE_COLLECTION_LIST = 3
};

static inline int jsiBridgeCollectionKind(jsi::Runtime &rt, const jsi::Value &v) {
  if (!v.isObject()) return BRIDGE_COLLECTION_NONE;
  jsi::Value ops = jsiValueOps(rt);
  if (!ops.isObject()) return BRIDGE_COLLECTION_NONE;
  jsi::Value kind = jsiCallValueOp(rt, ops, "kind", v);
  return kind.isNumber() ? static_cast<int>(kind.asNumber()) : BRIDGE_COLLECTION_NONE;
}

/**
 * JS built-ins (plain objects, arrays, dates, ...) and Kotlin's collection/Long/Unit wrappers
 * decode without a bridge converter; anything else that arrives as a class instance must have one.
 */
static inline bool jsiIsUnbridgedDecodeExemptClass(const std::string &name) {
  static const char *kExempt[] = {
    "Object", "Array", "Function", "Date", "RegExp", "Error", "Promise", "Symbol",
    "Number", "String", "Boolean", "BigInt", "JSON", "Math", "Reflect", "Proxy",
    "ArrayBuffer", "DataView", "Int8Array", "Uint8Array", "Uint8ClampedArray", "Int16Array",
    "Uint16Array", "Int32Array", "Uint32Array", "Float32Array", "Float64Array",
    "BigInt64Array", "BigUint64Array", "Map", "Set", "WeakMap", "WeakSet",
    // kotlin.Unit: the result of a Unit-returning guest function (e.g. the direct-event sink).
    "Unit",
  };
  for (const char *exempt : kExempt) {
    if (name == exempt) return true;
  }
  return false;
}

/**
 * Throw IllegalStateException naming the JS class of [v] when it looks like a Kotlin class
 * instance that no bridge converter was registered for. Returning null here used to surface as a
 * confusing cast/NPE far from the cause (e.g. a List<TopBarIcon> arriving as [null] because
 * TopBarIcon was not annotated), so name the class instead.
 */
static inline void jsiThrowUnbridgedJsObject(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v) {
  if (!v.isObject()) return;
  jsi::Object obj = v.asObject(rt);
  // A function value is not a data payload (zipline has no function bridge); leave it alone.
  if (obj.isFunction(rt)) return;
  jsi::Value ctor = obj.getProperty(rt, "constructor");
  if (!ctor.isObject() || !ctor.asObject(rt).isFunction(rt)) return;
  jsi::Value ctorName = ctor.asObject(rt).getProperty(rt, "name");
  if (!ctorName.isString()) return;
  std::string className = ctorName.asString(rt).utf8(rt);
  if (className.empty() || jsiIsUnbridgedDecodeExemptClass(className)) return;

  std::string message = "host bridge: no converter registered for JS class '" + className +
                        "'; annotate the class with @WithJS2HostBridge to send it to the host";
  env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}

// JNI caches for the collection decoder. Function-local statics inside this inline function give
// each shared library its own copy, which is correct: jmethodIDs are process-wide, and the class
// references we keep are global refs of our own.
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

static inline bool jsiCollectionRefs(JNIEnv *env, JsiCollectionRefs &refs) {
  if (refs.arrayListClass != nullptr) return true;
  jclass al = env->FindClass("java/util/ArrayList");
  if (al == nullptr) return false;
  refs.arrayListClass = static_cast<jclass>(env->NewGlobalRef(al));
  refs.arrayListInit = env->GetMethodID(al, "<init>", "()V");
  refs.arrayListAdd = env->GetMethodID(al, "add", "(Ljava/lang/Object;)Z");
  env->DeleteLocalRef(al);

  jclass lhm = env->FindClass("java/util/LinkedHashMap");
  if (lhm == nullptr) return false;
  refs.linkedHashMapClass = static_cast<jclass>(env->NewGlobalRef(lhm));
  refs.linkedHashMapInit = env->GetMethodID(lhm, "<init>", "()V");
  refs.mapPut = env->GetMethodID(
      lhm, "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");
  env->DeleteLocalRef(lhm);

  jclass lhs = env->FindClass("java/util/LinkedHashSet");
  if (lhs == nullptr) return false;
  refs.linkedHashSetClass = static_cast<jclass>(env->NewGlobalRef(lhs));
  refs.linkedHashSetInit = env->GetMethodID(lhs, "<init>", "()V");
  refs.setAdd = env->GetMethodID(lhs, "add", "(Ljava/lang/Object;)Z");
  env->DeleteLocalRef(lhs);

  if (env->ExceptionCheck()) return false;
  return refs.arrayListClass != nullptr && refs.linkedHashMapClass != nullptr &&
         refs.linkedHashSetClass != nullptr;
}

/**
 * Decode a guest collection into a Java collection. The guest identifies the kind and drives the
 * iteration through its value ops because Kotlin/JS mangles the stdlib member names (and drops
 * them in production builds), so a host-side walk works in development and yields an empty
 * collection in production.
 */
static inline jobject jsiCollectionToJava(
    JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v, int kind,
    JsiBridgeConverterFn keyConverter, JsiBridgeConverterFn valueConverter) {
  // One cache per shared library, filled once: the global references have process lifetime.
  static JsiCollectionRefs refs = {};
  if (!jsiCollectionRefs(env, refs)) {
    jsiLogBridgeError("host bridge: cannot resolve the JDK collection classes to decode a "
                      "collection into");
    return nullptr;
  }

  jobject result;
  switch (kind) {
    case BRIDGE_COLLECTION_MAP:
      result = env->NewObject(refs.linkedHashMapClass, refs.linkedHashMapInit);
      break;
    case BRIDGE_COLLECTION_SET:
      result = env->NewObject(refs.linkedHashSetClass, refs.linkedHashSetInit);
      break;
    default:
      result = env->NewObject(refs.arrayListClass, refs.arrayListInit);
      break;
  }
  if (env->ExceptionCheck()) {
    jsiLogBridgeError("host bridge: allocating a Java collection failed");
    if (result != nullptr) env->DeleteLocalRef(result);
    return nullptr;
  }

  // A Kotlin/JS ArrayList IS a JS array, so lists usually need no guest accessors at all.
  if (kind == BRIDGE_COLLECTION_LIST && v.isObject() && v.asObject(rt).isArray(rt)) {
    jsi::Array array = v.asObject(rt).asArray(rt);
    size_t length = array.length(rt);
    for (size_t i = 0; i < length && !env->ExceptionCheck(); i++) {
      jsi::Value element = array.getValueAtIndex(rt, i);
      jobject jElement = valueConverter(env, rt, element);
      if (jElement != nullptr) {
        env->CallBooleanMethod(result, refs.arrayListAdd, jElement);
        env->DeleteLocalRef(jElement);
      }
    }
    return result;
  }

  jsi::Value ops = jsiValueOps(rt);
  if (!ops.isObject()) {
    jsiLogBridgeError("host bridge: the guest value ops are missing, so a '" + jsiClassOf(rt, v) +
                      "' collection cannot be iterated");
    jsiRequireValueOps(env, rt, "a collection");
    return result;
  }
  jsi::Value iterator = jsiCallValueOp(rt, ops, "iterator", v);
  if (!iterator.isObject()) {
    jsiLogBridgeError("host bridge: the guest's iterator op returned no iterator for a '" +
                      jsiClassOf(rt, v) + "' collection");
  }
  while (!env->ExceptionCheck()) {
    jsi::Value hasNext = jsiCallValueOp(rt, ops, "hasNext", iterator);
    if (!hasNext.isBool() || !hasNext.asBool()) break;

    jsi::Value element = jsiCallValueOp(rt, ops, "next", iterator);
    if (!element.isObject() && !element.isString() && !element.isNumber() && !element.isBool()) break;
    if (kind == BRIDGE_COLLECTION_MAP) {
      jsi::Value rawKey = jsiCallValueOp(rt, ops, "key", element);
      jsi::Value rawValue = jsiCallValueOp(rt, ops, "value", element);
      jobject jKey = keyConverter(env, rt, rawKey);
      jobject jValue = valueConverter(env, rt, rawValue);
      if (!env->ExceptionCheck() && jKey != nullptr) {
        env->CallObjectMethod(result, refs.mapPut, jKey, jValue);
      }
      if (jValue != nullptr) env->DeleteLocalRef(jValue);
      if (jKey != nullptr) env->DeleteLocalRef(jKey);
    } else {
      jobject jElement = valueConverter(env, rt, element);
      if (!env->ExceptionCheck() && jElement != nullptr) {
        env->CallBooleanMethod(
            result, kind == BRIDGE_COLLECTION_SET ? refs.setAdd : refs.arrayListAdd, jElement);
      }
      if (jElement != nullptr) env->DeleteLocalRef(jElement);
    }
    if (env->ExceptionCheck()) break;
  }
  return result;
}

// Box a JS value into a Java object (Boolean/Integer/Double/String/Long/collections), or
// dispatch a bridged object. Returns NULL for null/undefined/unhandled. When [loud] is set, a
// class instance that has no converter throws instead of decoding to null.
static inline jobject jsi_value_to_boxed(
    JNIEnv *env, jsi::Runtime &rt, const jsi::Value &v, bool loud = true) {
  if (v.isBool()) {
    jclass c = env->FindClass("java/lang/Boolean");
    jmethodID m = env->GetMethodID(c, "<init>", "(Z)V");
    jobject boxed = env->NewObject(c, m, (jboolean)v.asBool());
    env->DeleteLocalRef(c);
    if (env->ExceptionCheck()) return nullptr;
    return boxed;
  }
  if (v.isNumber()) {
    double d = v.asNumber();
    if (d == (double)(int32_t)d) {
      jclass c = env->FindClass("java/lang/Integer");
      jmethodID m = env->GetMethodID(c, "<init>", "(I)V");
      jobject boxed = env->NewObject(c, m, (jint)d);
      env->DeleteLocalRef(c);
      if (env->ExceptionCheck()) return nullptr;
      return boxed;
    }
    jclass c = env->FindClass("java/lang/Double");
    jmethodID m = env->GetMethodID(c, "<init>", "(D)V");
    jobject boxed = env->NewObject(c, m, (jdouble)d);
    env->DeleteLocalRef(c);
    if (env->ExceptionCheck()) return nullptr;
    return boxed;
  }
  if (v.isString()) {
    jstring s = env->NewStringUTF(v.asString(rt).utf8(rt).c_str());
    if (env->ExceptionCheck()) return nullptr;
    return s;
  }
  if (v.isObject()) {
    jsi::Object obj = v.asObject(rt);
    // JS array → java.util.ArrayList, converting each element recursively.
    if (obj.isArray(rt)) {
      jsi::Array arr = obj.asArray(rt);
      size_t n = arr.length(rt);
      jclass alc = env->FindClass("java/util/ArrayList");
      jmethodID alc_init = env->GetMethodID(alc, "<init>", "()V");
      jmethodID alc_add = env->GetMethodID(alc, "add", "(Ljava/lang/Object;)Z");
      jobject list = env->NewObject(alc, alc_init);
      env->DeleteLocalRef(alc);
      if (env->ExceptionCheck()) return nullptr;
      for (size_t i = 0; i < n && !env->ExceptionCheck(); i++) {
        jsi::Value elem = arr.getValueAtIndex(rt, i);
        jobject je = jsi_value_to_boxed(env, rt, elem, loud);
        if (env->ExceptionCheck()) break;
        env->CallBooleanMethod(list, alc_add, je);
        if (je) env->DeleteLocalRef(je);
      }
      if (env->ExceptionCheck()) {
        env->DeleteLocalRef(list);
        return nullptr;
      }
      return list;
    }
    // Kotlin/JS collection (map/set/list): the guest identifies it and drives the iteration.
    int kind = jsiBridgeCollectionKind(rt, v);
    if (kind != BRIDGE_COLLECTION_NONE) {
      jobject collection =
          jsiCollectionToJava(env, rt, v, kind, jsiValueToBoxedLoud, jsiValueToBoxedLoud);
      if (collection == nullptr && !env->ExceptionCheck()) {
        jsiLogBridgeError(
            "host bridge: decoded a '" + jsiClassOf(rt, v) +
            "' collection as null (no exception was raised)");
      }
      return collection;
    }
    if (!jsiValueOps(rt).isObject()) {
      jsiLogBridgeError(
          "host bridge: globalThis." + std::string(VALUE_OPS_GLOBAL) +
          " is not an object, so a value of JS class '" + jsiClassOf(rt, v) +
          "' cannot be classified; the guest module that produced it was not compiled with the "
          "bridge plugin's module-load hook");
    }
    intptr_t ptr = jsi_get_bridge_dispatch(rt, v);
    if (ptr != 0) {
      JniBridgeDispatch *disp = (JniBridgeDispatch*)ptr;
      jobject result = disp->toJavaObject(env, rt, v);
      if (env->ExceptionCheck()) return nullptr;
      if (result != nullptr) return result;
    }
    // Kotlin/JS boxed Long: the guest reports its 32-bit halves (mangled fields).
    jobject boxedLong = jsiBridgeTryUnwrapLong(env, rt, v);
    if (boxedLong != nullptr) return boxedLong;
    if (env->ExceptionCheck()) return nullptr;
    if (loud) jsiThrowUnbridgedJsObject(env, rt, v);
  }
  return nullptr;
}

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
static inline jsi::Value jsiHost2JsPrototype(jsi::Runtime &rt, const char *fq) {
  jsi::Value protos = rt.global().getProperty(rt, HOST2JS_PROTOTYPES_GLOBAL);
  if (!protos.isObject()) return jsi::Value::undefined();
  return protos.asObject(rt).getProperty(rt, fq);
}

/**
 * A new JS instance of [fq] whose prototype is the guest's own. Throws when the guest never
 * registered the class: `undefined` is a valid JS value, so a caller that skipped the check would
 * silently ship a payload field of undefined.
 */
static inline jsi::Value jsiHost2JsNewObject(JNIEnv *env, jsi::Runtime &rt, const char *fq) {
  jsi::Value proto = jsiHost2JsPrototype(rt, fq);
  if (!proto.isObject()) {
    std::string message = std::string("host2js: no registered prototype for ") + fq +
                          "; the guest module did not call __bridgeRegister for this class";
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
    return jsi::Value::undefined();
  }
  return jsi::Value(rt, jsi::Object::create(rt, proto));
}

/** Define an own data property on [target]; a [Set] would fire a prototype accessor instead. */
static inline void jsiHost2JsDefineProperty(
    jsi::Runtime &rt, const jsi::Value &target, const char *name, jsi::Value value) {
  jsi::Object descriptor(rt);
  descriptor.setProperty(rt, "value", std::move(value));
  descriptor.setProperty(rt, "writable", jsi::Value(true));
  descriptor.setProperty(rt, "enumerable", jsi::Value(true));
  descriptor.setProperty(rt, "configurable", jsi::Value(true));
  jsi::Function defineProperty = rt.global()
                                   .getPropertyAsObject(rt, "Object")
                                   .getPropertyAsFunction(rt, "defineProperty");
  // Object.defineProperty reads its target from argument 0 and ignores `this`, so the target goes
  // in the argument list. The pointer/count overload is called explicitly: the variadic template
  // cannot convert the argument array.
  jsi::Value args[3] = {
    jsi::Value(rt, target),
    jsi::String::createFromUtf8(rt, name),
    jsi::Value(rt, descriptor),
  };
  defineProperty.call(rt, static_cast<const jsi::Value *>(args), static_cast<size_t>(3));
}

/**
 * Define [alias] on [target] as an own accessor forwarding to [source], so guest code that
 * predates a rename keeps reading the payload field it knows. An accessor rather than a copied
 * value, so a guest write through either name lands in the same property; the sibling
 * jsiHost2JsDefineProperty above stays a data property because the host writes each field once.
 */
static inline void jsiHost2JsDefineFieldAlias(
    jsi::Runtime &rt, const jsi::Value &target, const char *alias, const char *source) {
  std::string sourceName(source);
  jsi::Function getter = jsi::Function::createFromHostFunction(
      rt, jsi::PropNameID::forUtf8(rt, "get"), 0,
      [sourceName](jsi::Runtime &runtime, const jsi::Value &thisVal,
                   const jsi::Value *, size_t) -> jsi::Value {
        if (!thisVal.isObject()) return jsi::Value::undefined();
        return thisVal.asObject(runtime).getProperty(runtime, sourceName.c_str());
      });
  jsi::Function setter = jsi::Function::createFromHostFunction(
      rt, jsi::PropNameID::forUtf8(rt, "set"), 1,
      [sourceName](jsi::Runtime &runtime, const jsi::Value &thisVal,
                   const jsi::Value *args, size_t count) -> jsi::Value {
        if (thisVal.isObject() && count > 0) {
          thisVal.asObject(runtime).setProperty(runtime, sourceName.c_str(), args[0]);
        }
        return jsi::Value::undefined();
      });
  jsi::Object descriptor(rt);
  descriptor.setProperty(rt, "get", getter);
  descriptor.setProperty(rt, "set", setter);
  descriptor.setProperty(rt, "enumerable", jsi::Value(true));
  descriptor.setProperty(rt, "configurable", jsi::Value(true));
  // jsi has no defineProperty: the same Object.defineProperty route jsiHost2JsDefineProperty takes.
  jsi::Function defineProperty = rt.global()
                                   .getPropertyAsObject(rt, "Object")
                                   .getPropertyAsFunction(rt, "defineProperty");
  jsi::Value args[3] = {
    jsi::Value(rt, target),
    jsi::String::createFromUtf8(rt, alias),
    jsi::Value(rt, descriptor),
  };
  defineProperty.call(rt, static_cast<const jsi::Value *>(args), static_cast<size_t>(3));
}

/** A value from the guest's runtime factories (`newLong`/`newArrayList`/`newLinkedHashMap`). */
static inline jsi::Value jsiHost2JsRuntimeFactory(JNIEnv *env, jsi::Runtime &rt, const char *name) {
  jsi::Value factories = rt.global().getProperty(rt, HOST2JS_FACTORIES_GLOBAL);
  jsi::Value fn = factories.isObject() ? factories.asObject(rt).getProperty(rt, name)
                                       : jsi::Value::undefined();
  if (!fn.isObject() || !fn.asObject(rt).isFunction(rt)) {
    std::string message = std::string("host2js: no registered ") + name +
                          " runtime factory; the guest module did not call "
                          "__bridgeRegisterRuntime";
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
    return jsi::Value::undefined();
  }
  return fn;
}

/** A `kotlin.Long` built by the guest, so the guest sees a real Kotlin/JS Long. */
static inline jsi::Value jsiHost2JsLongToJs(JNIEnv *env, jsi::Runtime &rt, jlong value) {
  jsi::Value factory = jsiHost2JsRuntimeFactory(env, rt, "newLong");
  if (env->ExceptionCheck()) return jsi::Value::undefined();
  jsi::Value args[2] = {
    jsi::Value(static_cast<double>(static_cast<int32_t>(value & 0xFFFFFFFF))),
    jsi::Value(static_cast<double>(static_cast<int32_t>(value >> 32))),
  };
  return factory.asObject(rt).asFunction(rt).call(
      rt, static_cast<const jsi::Value *>(args), static_cast<size_t>(2));
}

static inline jsi::Value jsiHost2JsAnyToJs(JNIEnv *env, jsi::Runtime &rt, jobject obj);

/** A Java map key as the guest stored it: boxed primitives and String keep their JS shape. */
static inline jsi::Value jsiHost2JsKeyToJs(JNIEnv *env, jsi::Runtime &rt, jobject key) {
  if (key == nullptr) return jsi::Value::null();
  jclass cls = env->GetObjectClass(key);
  auto isInstanceOf = [&](const char *name) {
    jclass c = env->FindClass(name);
    if (!c) {
      env->ExceptionClear();
      return false;
    }
    bool result = env->IsInstanceOf(key, c) == JNI_TRUE;
    env->DeleteLocalRef(c);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return result;
  };
  auto number = [&](const char *name) -> jsi::Value {
    jclass c = env->FindClass(name);
    jmethodID m = env->GetMethodID(c, "doubleValue", "()D");
    jdouble d = env->CallDoubleMethod(key, m);
    env->DeleteLocalRef(c);
    if (env->ExceptionCheck()) return jsi::Value::undefined();
    return jsi::Value(d);
  };

  jsi::Value result;
  if (isInstanceOf("java/lang/String")) {
    result = jsi::String::createFromUtf8(rt, env->GetStringUTFChars(static_cast<jstring>(key), nullptr));
  } else if (isInstanceOf("java/lang/Integer") || isInstanceOf("java/lang/Long") ||
             isInstanceOf("java/lang/Short") || isInstanceOf("java/lang/Byte") ||
             isInstanceOf("java/lang/Float") || isInstanceOf("java/lang/Double")) {
    result = number("java/lang/Number");
  } else if (isInstanceOf("java/lang/Boolean")) {
    jclass c = env->FindClass("java/lang/Boolean");
    jmethodID m = env->GetMethodID(c, "booleanValue", "()Z");
    jboolean b = env->CallBooleanMethod(key, m);
    env->DeleteLocalRef(c);
    result = jsi::Value(b == JNI_TRUE);
  } else if (isInstanceOf("java/lang/Character")) {
    jclass c = env->FindClass("java/lang/Character");
    jmethodID m = env->GetMethodID(c, "charValue", "()C");
    jchar ch = env->CallCharMethod(key, m);
    env->DeleteLocalRef(c);
    result = jsi::Value(static_cast<double>(ch));
  } else if (isInstanceOf("java/lang/Number")) {
    result = number("java/lang/Number");
  } else {
    // Anything else is stringified, matching Kotlin/JS property-key coercion.
    jmethodID toString = env->GetMethodID(cls, "toString", "()Ljava/lang/String;");
    jstring s = static_cast<jstring>(env->CallObjectMethod(key, toString));
    if (env->ExceptionCheck() || s == nullptr) {
      env->DeleteLocalRef(cls);
      return jsi::Value::undefined();
    }
    const char *chars = env->GetStringUTFChars(s, nullptr);
    result = jsi::String::createFromUtf8(rt, chars);
    env->ReleaseStringUTFChars(s, chars);
    env->DeleteLocalRef(s);
  }
  env->DeleteLocalRef(cls);
  return result;
}

/** A JS array of the elements of a Java collection, each converted host -> JS. */
static inline jsi::Value jsiHost2JsToList(JNIEnv *env, jsi::Runtime &rt, jobject iterable) {
  jclass iterableClass = env->FindClass("java/lang/Iterable");
  jmethodID iteratorMethod = env->GetMethodID(iterableClass, "iterator", "()Ljava/util/Iterator;");
  jobject iterator = env->CallObjectMethod(iterable, iteratorMethod);
  env->DeleteLocalRef(iterableClass);
  if (env->ExceptionCheck() || iterator == nullptr) return jsi::Value::undefined();

  jclass iteratorClass = env->FindClass("java/util/Iterator");
  jmethodID hasNext = env->GetMethodID(iteratorClass, "hasNext", "()Z");
  jmethodID next = env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");
  env->DeleteLocalRef(iteratorClass);

  std::vector<jsi::Value> elements;
  while (!env->ExceptionCheck()) {
    if (env->CallBooleanMethod(iterator, hasNext) != JNI_TRUE) break;
    jobject element = env->CallObjectMethod(iterator, next);
    if (env->ExceptionCheck()) {
      if (element) env->DeleteLocalRef(element);
      break;
    }
    elements.emplace_back(jsiHost2JsAnyToJs(env, rt, element));
    if (element) env->DeleteLocalRef(element);
    if (env->ExceptionCheck()) break;
  }
  env->DeleteLocalRef(iterator);
  if (env->ExceptionCheck()) return jsi::Value::undefined();

  jsi::Array array(rt, elements.size());
  for (size_t i = 0; i < elements.size(); i++) {
    array.setValueAtIndex(rt, i, elements[i]);
  }
  return jsi::Value(rt, array);
}

/** True when [obj] is a Java array. [objClass] is its class, a local ref the caller owns. */
static inline bool jsiIsJavaArray(JNIEnv *env, jobject obj, jclass objClass) {
  (void)obj;
  jclass classClass = env->FindClass("java/lang/Class");
  if (classClass == nullptr) {
    env->ExceptionClear();
    return false;
  }
  jmethodID isArray = env->GetMethodID(classClass, "isArray", "()Z");
  env->DeleteLocalRef(classClass);
  if (isArray == nullptr) {
    env->ExceptionClear();
    return false;
  }
  jboolean result = env->CallBooleanMethod(objClass, isArray);
  if (env->ExceptionCheck()) {
    env->ExceptionClear();
    return false;
  }
  return result == JNI_TRUE;
}

/**
 * The elements of a Java array as a JS array. The component type decides how they are read: a
 * primitive component through its typed accessor, anything else - including the nested arrays of a
 * Kotlin Array<Array<T>> - through this same converter.
 */
static inline jsi::Value jsiArrayToJs(JNIEnv *env, jsi::Runtime &rt, jobject array) {
  jclass classClass = env->FindClass("java/lang/Class");
  if (classClass == nullptr) {
    env->ExceptionClear();
    return jsi::Value::undefined();
  }
  jmethodID getComponentType =
      env->GetMethodID(classClass, "getComponentType", "()Ljava/lang/Class;");
  jmethodID getName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
  jclass arrayClass = env->GetObjectClass(array);
  jobject component = env->CallObjectMethod(arrayClass, getComponentType);
  env->DeleteLocalRef(arrayClass);
  if (env->ExceptionCheck() || component == nullptr) {
    env->DeleteLocalRef(classClass);
    if (component != nullptr) env->DeleteLocalRef(component);
    return jsi::Value::undefined();
  }
  jstring componentName = static_cast<jstring>(env->CallObjectMethod(component, getName));
  env->DeleteLocalRef(component);
  env->DeleteLocalRef(classClass);
  if (env->ExceptionCheck()) return jsi::Value::undefined();
  std::string name;
  if (componentName != nullptr) {
    const char *chars = env->GetStringUTFChars(componentName, nullptr);
    name = chars;
    env->ReleaseStringUTFChars(componentName, chars);
    env->DeleteLocalRef(componentName);
  }

  jsize length = env->GetArrayLength(static_cast<jarray>(array));
  jsi::Array result(rt, static_cast<size_t>(length));
  for (jsize i = 0; i < length && !env->ExceptionCheck(); i++) {
    jsi::Value element;
    if (name == "int") {
      jint *e = env->GetIntArrayElements(static_cast<jintArray>(array), nullptr);
      element = jsi::Value(static_cast<double>(e[i]));
      env->ReleaseIntArrayElements(static_cast<jintArray>(array), e, JNI_ABORT);
    } else if (name == "long") {
      jlong *e = env->GetLongArrayElements(static_cast<jlongArray>(array), nullptr);
      jlong v = e[i];
      env->ReleaseLongArrayElements(static_cast<jlongArray>(array), e, JNI_ABORT);
      element = jsiHost2JsLongToJs(env, rt, v);
    } else if (name == "double") {
      jdouble *e = env->GetDoubleArrayElements(static_cast<jdoubleArray>(array), nullptr);
      element = jsi::Value(e[i]);
      env->ReleaseDoubleArrayElements(static_cast<jdoubleArray>(array), e, JNI_ABORT);
    } else if (name == "float") {
      jfloat *e = env->GetFloatArrayElements(static_cast<jfloatArray>(array), nullptr);
      element = jsi::Value(static_cast<double>(e[i]));
      env->ReleaseFloatArrayElements(static_cast<jfloatArray>(array), e, JNI_ABORT);
    } else if (name == "boolean") {
      jboolean *e = env->GetBooleanArrayElements(static_cast<jbooleanArray>(array), nullptr);
      element = jsi::Value(e[i] == JNI_TRUE);
      env->ReleaseBooleanArrayElements(static_cast<jbooleanArray>(array), e, JNI_ABORT);
    } else if (name == "short") {
      jshort *e = env->GetShortArrayElements(static_cast<jshortArray>(array), nullptr);
      element = jsi::Value(static_cast<double>(e[i]));
      env->ReleaseShortArrayElements(static_cast<jshortArray>(array), e, JNI_ABORT);
    } else if (name == "byte") {
      jbyte *e = env->GetByteArrayElements(static_cast<jbyteArray>(array), nullptr);
      element = jsi::Value(static_cast<double>(e[i]));
      env->ReleaseByteArrayElements(static_cast<jbyteArray>(array), e, JNI_ABORT);
    } else if (name == "char") {
      jchar *e = env->GetCharArrayElements(static_cast<jcharArray>(array), nullptr);
      element = jsi::Value(static_cast<double>(e[i]));
      env->ReleaseCharArrayElements(static_cast<jcharArray>(array), e, JNI_ABORT);
    } else {
      jobject e = env->GetObjectArrayElement(static_cast<jobjectArray>(array), i);
      element = jsiHost2JsAnyToJs(env, rt, e);
      if (e != nullptr) env->DeleteLocalRef(e);
    }
    if (env->ExceptionCheck()) break;
    result.setValueAtIndex(rt, static_cast<size_t>(i), element);
  }
  if (env->ExceptionCheck()) return jsi::Value::undefined();
  return jsi::Value(rt, result);
}

/**
 * The guest counterpart of a host value: scalars and String directly, Long and the collections
 * through the guest's own factories (so the guest sees real Kotlin/JS values), arrays elementwise,
 * and anything else through the class's own convertToJs member.
 */
static inline jsi::Value jsiHost2JsAnyToJs(JNIEnv *env, jsi::Runtime &rt, jobject obj) {
  if (obj == nullptr) return jsi::Value::null();

  jclass objClass = env->GetObjectClass(obj);
  auto isInstanceOf = [&](const char *name) {
    jclass c = env->FindClass(name);
    if (!c) {
      env->ExceptionClear();
      return false;
    }
    bool result = env->IsInstanceOf(obj, c) == JNI_TRUE;
    env->DeleteLocalRef(c);
    if (env->ExceptionCheck()) env->ExceptionClear();
    return result;
  };
  auto scalar = [&](const char *name, const char *method, const char *sig) -> jsi::Value {
    jclass c = env->FindClass(name);
    jmethodID m = env->GetMethodID(c, method, sig);
    env->DeleteLocalRef(c);
    if (env->ExceptionCheck()) return jsi::Value::undefined();
    if (std::string(sig) == "()Z") return jsi::Value(env->CallBooleanMethod(obj, m) == JNI_TRUE);
    if (std::string(sig) == "()D") return jsi::Value(env->CallDoubleMethod(obj, m));
    if (std::string(sig) == "()F") return jsi::Value(static_cast<double>(env->CallFloatMethod(obj, m)));
    if (std::string(sig) == "()C") return jsi::Value(static_cast<double>(env->CallCharMethod(obj, m)));
    if (std::string(sig) == "()J") {
      jlong value = env->CallLongMethod(obj, m);
      if (env->ExceptionCheck()) return jsi::Value::undefined();
      return jsiHost2JsLongToJs(env, rt, value);
    }
    return jsi::Value(static_cast<double>(env->CallIntMethod(obj, m)));
  };

  if (isInstanceOf("java/lang/String")) {
    jstring s = static_cast<jstring>(obj);
    const char *chars = env->GetStringUTFChars(s, nullptr);
    jsi::Value result = jsi::String::createFromUtf8(rt, chars);
    env->ReleaseStringUTFChars(s, chars);
    env->DeleteLocalRef(objClass);
    return result;
  }
  if (isInstanceOf("java/lang/Boolean")) {
    jsi::Value result = scalar("java/lang/Boolean", "booleanValue", "()Z");
    env->DeleteLocalRef(objClass);
    return result;
  }
  if (isInstanceOf("java/lang/Long")) {
    jsi::Value result = scalar("java/lang/Long", "longValue", "()J");
    env->DeleteLocalRef(objClass);
    return result;
  }
  if (isInstanceOf("java/lang/Integer")) {
    jsi::Value result = scalar("java/lang/Integer", "intValue", "()I");
    env->DeleteLocalRef(objClass);
    return result;
  }
  if (isInstanceOf("java/lang/Short")) {
    jsi::Value result = scalar("java/lang/Short", "shortValue", "()S");
    env->DeleteLocalRef(objClass);
    return result;
  }
  if (isInstanceOf("java/lang/Byte")) {
    jsi::Value result = scalar("java/lang/Byte", "byteValue", "()B");
    env->DeleteLocalRef(objClass);
    return result;
  }
  if (isInstanceOf("java/lang/Character")) {
    jsi::Value result = scalar("java/lang/Character", "charValue", "()C");
    env->DeleteLocalRef(objClass);
    return result;
  }
  if (isInstanceOf("java/lang/Float")) {
    jsi::Value result = scalar("java/lang/Float", "floatValue", "()F");
    env->DeleteLocalRef(objClass);
    return result;
  }
  if (isInstanceOf("java/lang/Double")) {
    jsi::Value result = scalar("java/lang/Double", "doubleValue", "()D");
    env->DeleteLocalRef(objClass);
    return result;
  }
  if (isInstanceOf("java/lang/Number")) {
    jsi::Value result = scalar("java/lang/Number", "doubleValue", "()D");
    env->DeleteLocalRef(objClass);
    return result;
  }

  // A Kotlin Map: two parallel arrays the guest zips into its own LinkedHashMap.
  if (isInstanceOf("java/util/Map")) {
    jclass mapClass = env->FindClass("java/util/Map");
    jmethodID entrySetMethod = env->GetMethodID(mapClass, "entrySet", "()Ljava/util/Set;");
    jobject entrySet = env->CallObjectMethod(obj, entrySetMethod);
    env->DeleteLocalRef(mapClass);
    if (env->ExceptionCheck() || entrySet == nullptr) {
      env->DeleteLocalRef(objClass);
      return jsi::Value::undefined();
    }
    jclass setClass = env->FindClass("java/util/Set");
    jmethodID iteratorMethod = env->GetMethodID(setClass, "iterator", "()Ljava/util/Iterator;");
    jobject iterator = env->CallObjectMethod(entrySet, iteratorMethod);
    env->DeleteLocalRef(setClass);
    env->DeleteLocalRef(entrySet);
    if (env->ExceptionCheck() || iterator == nullptr) {
      env->DeleteLocalRef(objClass);
      return jsi::Value::undefined();
    }
    jclass iteratorClass = env->FindClass("java/util/Iterator");
    jmethodID hasNext = env->GetMethodID(iteratorClass, "hasNext", "()Z");
    jmethodID next = env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");
    env->DeleteLocalRef(iteratorClass);
    jclass entryClass = env->FindClass("java/util/Map$Entry");
    jmethodID getKey = env->GetMethodID(entryClass, "getKey", "()Ljava/lang/Object;");
    jmethodID getValue = env->GetMethodID(entryClass, "getValue", "()Ljava/lang/Object;");
    env->DeleteLocalRef(entryClass);

    std::vector<jsi::Value> keys;
    std::vector<jsi::Value> values;
    while (!env->ExceptionCheck()) {
      if (env->CallBooleanMethod(iterator, hasNext) != JNI_TRUE) break;
      jobject entry = env->CallObjectMethod(iterator, next);
      if (env->ExceptionCheck() || entry == nullptr) {
        if (entry) env->DeleteLocalRef(entry);
        break;
      }
      jobject key = env->CallObjectMethod(entry, getKey);
      jobject value = env->CallObjectMethod(entry, getValue);
      if (!env->ExceptionCheck()) {
        keys.emplace_back(jsiHost2JsKeyToJs(env, rt, key));
        values.emplace_back(jsiHost2JsAnyToJs(env, rt, value));
      }
      if (key) env->DeleteLocalRef(key);
      if (value) env->DeleteLocalRef(value);
      env->DeleteLocalRef(entry);
      if (env->ExceptionCheck()) break;
    }
    env->DeleteLocalRef(iterator);
    env->DeleteLocalRef(objClass);
    if (env->ExceptionCheck()) return jsi::Value::undefined();

    jsi::Value factory = jsiHost2JsRuntimeFactory(env, rt, "newLinkedHashMap");
    if (env->ExceptionCheck()) return jsi::Value::undefined();
    jsi::Array keyArray(rt, keys.size());
    jsi::Array valueArray(rt, values.size());
    for (size_t i = 0; i < keys.size(); i++) {
      keyArray.setValueAtIndex(rt, i, keys[i]);
      valueArray.setValueAtIndex(rt, i, values[i]);
    }
    jsi::Value args[2] = { jsi::Value(rt, keyArray), jsi::Value(rt, valueArray) };
    return factory.asObject(rt).asFunction(rt).call(
        rt, static_cast<const jsi::Value *>(args), static_cast<size_t>(2));
  }

  // A Kotlin List/Set: a real guest ArrayList.
  if (isInstanceOf("java/util/List") || isInstanceOf("java/util/Set")) {
    jsi::Value array = jsiHost2JsToList(env, rt, obj);
    env->DeleteLocalRef(objClass);
    if (env->ExceptionCheck()) return array;
    jsi::Value factory = jsiHost2JsRuntimeFactory(env, rt, "newArrayList");
    if (env->ExceptionCheck()) return jsi::Value::undefined();
    return factory.asObject(rt).asFunction(rt).call(rt, array);
  }

  // Arrays. JNI has no IsArray, but java.lang.Class has isArray(); it covers every dimension and
  // every element type, including the nested reference arrays a two-dimensional Kotlin Array<Array<T>>
  // produces. The component type's name picks the element accessor.
  if (jsiIsJavaArray(env, obj, objClass)) {
    jsi::Value result = jsiArrayToJs(env, rt, obj);
    env->DeleteLocalRef(objClass);
    return result;
  }

  // Anything else: virtual dispatch to the annotated class's convertToJs(J)J member, which builds
  // the instance from the guest prototype and fills its fields. The member returns a pointer to a
  // heap jsi::Value this function takes ownership of.
  jmethodID convertToJs = env->GetMethodID(objClass, "convertToJs", "(J)J");
  env->DeleteLocalRef(objClass);
  if (env->ExceptionCheck()) {
    // NoSuchMethodError for an unbridgeable object: leave it pending. It propagates to the JVM
    // and crashes, which is the policy: never a silent null.
    return jsi::Value::undefined();
  }
  jlong result = env->CallLongMethod(obj, convertToJs, static_cast<jlong>(reinterpret_cast<intptr_t>(&rt)));
  if (env->ExceptionCheck() || result == 0) return jsi::Value::undefined();
  jsi::Value *heap = reinterpret_cast<jsi::Value *>(static_cast<intptr_t>(result));
  jsi::Value value = std::move(*heap);
  delete heap;
  return value;
}

#endif
