#include "ContextJni.h"
#include "JniUtf8.h"
#include "common/intset-builtins.h"
#include <cmath>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#ifdef __ANDROID__
#include <android/log.h>
#endif

#include <hermes/Public/GCConfig.h>
#include <hermes/Public/RuntimeConfig.h>
#include <hermes/hermes.h>
#include <jsi/decorator.h>
#include <jsi/instrumentation.h>
#include <jsi/jsi.h>

#include "hermes-core.h"
#include "CdpJni.h"
#include "ExceptionThrowers.h"
#include "InboundCallChannel.h"
#include "OutboundCallChannelJni.h"
#include "bridge_dispatch.h"


#include <vector>
#include <string>
#include <utility>

namespace jsi = facebook::jsi;

static std::vector<std::pair<std::string, jobject(*)(JNIEnv*,jsi::Runtime&,const jsi::Value&)>> bridgeTable;
static std::vector<void(*)(JNIEnv*)> bridgeInits;

extern "C" __attribute__((used, visibility("default"))) void addBridgeEntry(const char* fq, jobject(*fn)(JNIEnv*,jsi::Runtime&,const jsi::Value&)) {
    bridgeTable.push_back({fq, fn});
}

extern "C" __attribute__((used, visibility("default"))) void addBridgeInit(void(*fn)(JNIEnv*)) {
    bridgeInits.push_back(fn);
}

extern "C" __attribute__((used, visibility("default"))) void init_all(JNIEnv* env) {
    for (auto& fn : bridgeInits) {
        fn(env);
    }
}

extern "C" __attribute__((used, visibility("default"))) void register_all(jsi::Runtime& rt) {
    auto bridgeRegisterFn = jsi::Function::createFromHostFunction(
        rt,
        jsi::PropNameID::forUtf8(rt, "__bridgeRegister"),
        2,
        [](jsi::Runtime& rt, const jsi::Value&, const jsi::Value* args, size_t argc) -> jsi::Value {
            if (argc < 2) return jsi::Value::undefined();
            if (bridgeTable.empty()) return jsi::Value::undefined();
            auto fq = args[0].asString(rt).utf8(rt);
            if (!args[1].isObject()) return jsi::Value::undefined();
            jsi::Object ctor = args[1].asObject(rt);
            for (auto& entry : bridgeTable) {
                if (strcmp(entry.first.c_str(), fq.c_str()) == 0) {
                    JniBridgeDispatch *disp = new JniBridgeDispatch();
                    disp->toJavaObject = entry.second;
                    jsi::Object proto = ctor.getPropertyAsObject(rt, "prototype");
                    // Store pointer as two int32 halves (JS double has 53-bit mantissa;
                    // ARM64 pointers need 64 bits, so split into two 32-bit values).
                    intptr_t ptr = reinterpret_cast<intptr_t>(disp);
                    int32_t low  = static_cast<int32_t>(ptr & 0xFFFFFFFF);
                    int32_t high = static_cast<int32_t>((ptr >> 32) & 0xFFFFFFFF);
                    proto.setProperty(rt, "bridge_dispatch_low",  jsi::Value(static_cast<double>(low)));
                    proto.setProperty(rt, "bridge_dispatch_high", jsi::Value(static_cast<double>(high)));
                    return jsi::Value::undefined();
                }
            }
            throw jsi::JSError(rt, std::string("bridge_register_js: FQN '") + fq + "' not found in bridge_table");
            return jsi::Value::undefined();
        });
    jsi::Object globalObject = rt.global();
    globalObject.setProperty(rt, "__bridgeRegister", std::move(bridgeRegisterFn));
}

namespace jsi = facebook::jsi;
namespace hermes_vm = hermes::vm;

namespace {

// Detach the current thread from the JavaVM when this goes out of scope.
// Used to clean up after getEnv()'s AttachCurrentThread path.
struct JniThreadDetacher {
  JavaVM& javaVm;
  JniThreadDetacher(JavaVM* vm) : javaVm(*vm) {}
  ~JniThreadDetacher() { javaVm.DetachCurrentThread(); }
};

jclass findClassOrNull(JNIEnv* env, const char* name) {
  jclass cls = env->FindClass(name);
  if (cls == nullptr) {
    env->ExceptionClear();
    return nullptr;
  }
  return static_cast<jclass>(env->NewGlobalRef(cls));
}

// JS numbers are doubles; casting an out-of-range or non-finite double to an
// integer type is undefined behavior, so range- and integrality-check first.
bool tryAsInt32(double d, int32_t& out) {
  if (!std::isfinite(d) || std::trunc(d) != d ||
      d < -2147483648.0 || d > 2147483647.0) {
    return false;
  }
  out = static_cast<int32_t>(d);
  return true;
}

bool tryAsInt64(double d, int64_t& out) {
  // Bounds as doubles: only |d| < 2^63 is representable as int64.
  if (!std::isfinite(d) || std::trunc(d) != d ||
      d < -9223372036854775808.0 || d >= 9223372036854775808.0) {
    return false;
  }
  out = static_cast<int64_t>(d);
  return true;
}

}  // namespace

ContextJni::ContextJni(JNIEnv* env, bool forceEagerCompilation)
    : jniVersion(env->GetVersion()),
      // Default runtime config is built in the body; member init-list can't
      // chain the .withX(...) builder calls.
      runtimeConfig(),
      booleanClass(findClassOrNull(env, "java/lang/Boolean")),
      integerClass(findClassOrNull(env, "java/lang/Integer")),
      doubleClass(findClassOrNull(env, "java/lang/Double")),
      longClass(findClassOrNull(env, "java/lang/Long")),
      objectClass(findClassOrNull(env, "java/lang/Object")),
      stringClass(findClassOrNull(env, "java/lang/String")),
      stringUtf8(static_cast<jstring>(env->NewGlobalRef(env->NewStringUTF("UTF-8")))),
      jsExceptionClass(findClassOrNull(env, "app/cash/zipline/JsException")),
      pendingJavaException(nullptr) {
  env->GetJavaVM(&javaVm);
  jsThreadId = std::this_thread::get_id();

  // Helper to look up a static method on a class, gracefully handling
  // a null class. Returns null if either the class or the method is missing.
  auto getStaticMethod = [&](jclass cls, const char* name, const char* sig) -> jmethodID {
    if (cls == nullptr) return nullptr;
    jmethodID m = env->GetStaticMethodID(cls, name, sig);
    if (m == nullptr) env->ExceptionClear();
    return m;
  };
  auto getInstanceMethod = [&](jclass cls, const char* name, const char* sig) -> jmethodID {
    if (cls == nullptr) return nullptr;
    jmethodID m = env->GetMethodID(cls, name, sig);
    if (m == nullptr) env->ExceptionClear();
    return m;
  };

  booleanValueOf = getStaticMethod(booleanClass, "valueOf", "(Z)Ljava/lang/Boolean;");
  integerValueOf = getStaticMethod(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
  doubleValueOf = getStaticMethod(doubleClass, "valueOf", "(D)Ljava/lang/Double;");
  longValueOf = getStaticMethod(longClass, "valueOf", "(J)Ljava/lang/Long;");
  stringGetBytes = getInstanceMethod(stringClass, "getBytes", "(Ljava/lang/String;)[B");
  stringConstructor = getInstanceMethod(stringClass, "<init>", "([BLjava/lang/String;)V");
  jsExceptionConstructor = getInstanceMethod(
      jsExceptionClass, "<init>", "(Ljava/lang/String;Ljava/lang/String;)V");

  // Shared Zipline runtime + GC config (hardened + ES6Proxy, 32 MB initial
  // heap, 3 GB max — see hermes-core.cpp).
  runtimeConfig = HermesCore_makeRuntimeConfig(forceEagerCompilation);
  debugCompilation = forceEagerCompilation;

  auto hermesRuntime = facebook::hermes::makeHermesRuntime(runtimeConfig);
  if (!hermesRuntime) {
    throwJavaException(env, "java/lang/OutOfMemoryError",
                       "Cannot create HermesRuntime");
    throw std::runtime_error("makeHermesRuntime returned null");
  }
  runtime = std::move(hermesRuntime);

  // Register JS intrinsics (IntSet/ScatterSet/ScatterMap/etc.) that back the
  // kotlinx.collections fast paths in Kotlin/JS. These are called from
  // generated Kotlin/JS code via _intsetFind, _scatterSetFind, etc.
  js_register_intrinsics(runtime.get());

  // Install a global `gc()` helper mirroring the QuickJS `JS_AddGlobalThisGc`
  // shim. Hermes has no built-in JS-visible `gc` function in the runtime, so
  // we add one. Calling `runtime->instrumentation().collectGarbage()` is the
  // recommended public API for forcing a GC.
  jsi::Function gcFn = jsi::Function::createFromHostFunction(
      *runtime,
      jsi::PropNameID::forUtf8(*runtime, "gc"),
      0,
      [](jsi::Runtime& rt, const jsi::Value& /*thisVal*/, const jsi::Value*,
         size_t) -> jsi::Value {
        rt.instrumentation().collectGarbage("host_global_gc");
        return jsi::Value::undefined();
      });
  jsi::Object globalObject = runtime->global();
  globalObject.setProperty(*runtime, "gc", gcFn);
}

void ContextJni::deleteBridgeRefs(JNIEnv* env) {
  // Each field is nulled as it is released: these caches are set independently (the JDK
  // collection classes are cached even when redwood is absent), so a partially populated set must
  // not leave a stale handle for a second release.
  if (arrayListClass != nullptr) {
    env->DeleteGlobalRef(arrayListClass);
    arrayListClass = nullptr;
  }
  if (rdmaBridgeClass != nullptr) {
    env->DeleteGlobalRef(rdmaBridgeClass);
    rdmaBridgeClass = nullptr;
  }
  if (rdmaChangeSink != nullptr) {
    env->DeleteGlobalRef(rdmaChangeSink);
    rdmaChangeSink = nullptr;
  }
  if (pairClass != nullptr) {
    env->DeleteGlobalRef(pairClass);
    pairClass = nullptr;
  }
}

ContextJni::~ContextJni() {
  // Tear down any CDP session before the runtime goes away; the session's
  // agent and debug API reference the runtime.
  zipline_cdp::detach(this);

  JNIEnv* env = getEnv();
  if (env) {
    for (auto& kv : globalReferences) env->DeleteGlobalRef(kv.second);
    if (jsExceptionClass) env->DeleteGlobalRef(jsExceptionClass);
    if (stringUtf8) env->DeleteGlobalRef(stringUtf8);
    deleteBridgeRefs(env);
    if (stringClass) env->DeleteGlobalRef(stringClass);
    if (objectClass) env->DeleteGlobalRef(objectClass);
    if (doubleClass) env->DeleteGlobalRef(doubleClass);
    if (longClass) env->DeleteGlobalRef(longClass);
    if (integerClass) env->DeleteGlobalRef(integerClass);
    if (booleanClass) env->DeleteGlobalRef(booleanClass);
    if (pendingJavaException) env->DeleteGlobalRef(pendingJavaException);
  }
}

jobject ContextJni::execute(JNIEnv* env, jbyteArray byteCode, jstring fileName) {
  // Run any pending CDP runtime tasks (e.g. breakpoint installation) before
  // evaluating more JavaScript. We are on the JS thread here.
  zipline_cdp::drainTasks(this);

  const jsize n = env->GetArrayLength(byteCode);
  std::vector<uint8_t> buf(n);
  env->GetByteArrayRegion(byteCode, 0, n, reinterpret_cast<jbyte*>(buf.data()));

  std::string fileNameStr = fileName ? zipline::jniStringToUtf8(env, fileName)
                                     : std::string("zipline-module.js");

  jsi::Value result;
  try {
    result = HermesCore_evaluateBytecode(this, buf.data(), buf.size(), fileNameStr);
  } catch (const jsi::JSError& e) {
    throwJsException(env, const_cast<jsi::JSError&>(e));
    return nullptr;
  } catch (const std::exception& e) {
    throwJsExceptionFmt(env, this, "Hermes execute failed: %s", e.what());
    return nullptr;
  }
  return toJavaObject(env, result, /*throwOnUnsupportedType=*/false);
}

jobject ContextJni::evaluate(JNIEnv* env, jstring source, jstring fileName) {
#ifdef HERMESVM_LEAN
  throwJavaException(env, "java/lang/UnsupportedOperationException",
                     "evaluate() is not available in lean Hermes build");
  return nullptr;
#else
  // Run any pending CDP runtime tasks (e.g. breakpoint installation) before
  // evaluating more JavaScript. We are on the JS thread here.
  zipline_cdp::drainTasks(this);

  std::string src = zipline::jniStringToUtf8(env, source);
  std::string fileNameStr = fileName ? zipline::jniStringToUtf8(env, fileName)
                                     : std::string("zipline-module.js");

  jsi::Value result;
  try {
    result = HermesCore_evaluateSource(this, src.c_str(), fileNameStr);
  } catch (const jsi::JSError& e) {
    #ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_ERROR, "JSI", "evaluate: JSError: %s", e.getMessage().c_str());
    #endif
    throwJsException(env, const_cast<jsi::JSError&>(e));
    return nullptr;
  } catch (const std::exception& e) {
    #ifdef __ANDROID__
    __android_log_print(ANDROID_LOG_ERROR, "JSI", "evaluate: exception: %s", e.what());
    #endif
    throwJsExceptionFmt(env, this, "Hermes evaluate failed: %s", e.what());
    return nullptr;
  }
  return toJavaObject(env, result, /*throwOnUnsupportedType=*/false);
#endif // HERMESVM_LEAN
}

jbyteArray ContextJni::compile(JNIEnv* env, jstring source, jstring file,
                            jstring sourceMap) {
#ifdef HERMESVM_LEAN
  throwJavaException(env, "java/lang/UnsupportedOperationException",
                     "compile() is not available in lean Hermes build");
  return nullptr;
#else
  std::string src = toCppString(env, source);
  std::string filename = toCppString(env, file);
  std::string sourceMapStr = sourceMap != nullptr ? toCppString(env, sourceMap) : std::string();

  uint8_t* bytecodeOut = nullptr;
  size_t bytecodeSize = 0;
  char* error = nullptr;
  int ok = HermesCore_compile(
      this,
      src.c_str(),
      filename.c_str(),
      sourceMap != nullptr ? sourceMapStr.c_str() : nullptr,
      &bytecodeOut,
      &bytecodeSize,
      &error);
  if (!ok) {
    throwJsExceptionFmt(env, this, "%s", error ? error : "Failed to compile JavaScript");
    free(error);
    return nullptr;
  }

  jbyteArray result = env->NewByteArray(static_cast<jsize>(bytecodeSize));
  env->SetByteArrayRegion(result, 0, static_cast<jsize>(bytecodeSize),
                          reinterpret_cast<const jbyte*>(bytecodeOut));
  delete[] bytecodeOut;
  return result;
#endif
}

jobject ContextJni::memoryUsage(JNIEnv* env) {
  jclass memClass = findClassOrNull(env, "app/cash/zipline/MemoryUsage");
  if (memClass == nullptr) return nullptr;
  jmethodID memCtor = env->GetMethodID(
      memClass, "<init>", "(JJJJJJJJJJ)V");
  if (memCtor == nullptr) { env->ExceptionClear(); return nullptr; }

  HermesCoreMemoryUsage usage;
  if (!HermesCore_getMemoryUsage(this, &usage)) {
    return nullptr;
  }

  return env->NewObject(
      memClass, memCtor,
      static_cast<jlong>(usage.heapSize),
      static_cast<jlong>(usage.allocatedBytes),
      static_cast<jlong>(usage.totalAllocatedBytes),
      static_cast<jlong>(usage.va),
      static_cast<jlong>(usage.externalBytes),
      static_cast<jlong>(usage.mallocSizeEstimate),
      static_cast<jlong>(usage.peakAllocatedBytes),
      static_cast<jlong>(usage.peakLiveAfterGC),
      static_cast<jlong>(usage.numCollections),
      static_cast<jlong>(usage.numMarkStackOverflows)
  );
}

void ContextJni::gc(JNIEnv* /*env*/) {
  HermesCore_gc(this);
}

InboundCallChannel* ContextJni::getInboundCallChannel(JNIEnv* env, jstring name) {
  std::string serviceName = toCppString(env, name);

  jsi::Value obj = runtime->global().getProperty(*runtime, serviceName.c_str());

  InboundCallChannel* inbound = nullptr;
  if (obj.isObject()) {
    inbound = new InboundCallChannel(serviceName);
    if (!env->ExceptionCheck()) {
      inboundChannels.push_back(inbound);
    } else {
      delete inbound;
      inbound = nullptr;
    }
  } else if (env->ExceptionCheck()) {
    // JSI doesn't expose JS exceptions the way QuickJS did; if one is in
    // flight, propagate it.
  } else {
    const char* msg = obj.isUndefined()
                          ? "A global JavaScript object called %s was not found. "
                            "Try confirming that Zipline.get() has been called."
                          : "JavaScript global called %s is not an object";
    throwJavaException(env, "java/lang/IllegalStateException", msg,
                       serviceName.c_str());
  }
  return inbound;
}

void ContextJni::setOutboundCallChannel(JNIEnv* env, jstring name, jobject callChannel) {
  std::string serviceName = toCppString(env, name);

  jsi::Object globalObject = runtime->global();

  if (!globalObject.getProperty(*runtime, serviceName.c_str()).isUndefined()) {
    throwJavaException(env, "java/lang/IllegalArgumentException",
                       "A global object called %s already exists",
                       serviceName.c_str());
    return;
  }

  jsi::Object jsObj = jsi::Object(*runtime);
  auto* occ = new OutboundCallChannelJni(this, env, serviceName, callChannel, jsObj);
  globalObject.setProperty(*runtime, serviceName.c_str(), jsObj);

  // Store the OutboundCallChannel so its destructor runs at Context teardown.
  outboundChannels.push_back(occ);
}

jobject
ContextJni::toJavaObject(JNIEnv* env, const jsi::Value& value, bool throwOnUnsupportedType) {
  if (value.isBool()) {
    jvalue v;
    v.z = value.asBool() ? JNI_TRUE : JNI_FALSE;
    return env->CallStaticObjectMethodA(booleanClass, booleanValueOf, &v);
  }
  if (value.isNumber()) {
    double d = value.asNumber();
    // If it's representable as int, box as Integer; otherwise Double.
    int32_t asInt;
    if (tryAsInt32(d, asInt)) {
      jvalue v;
      v.i = asInt;
      return env->CallStaticObjectMethodA(integerClass, integerValueOf, &v);
    }
    jvalue v;
    v.d = d;
    return env->CallStaticObjectMethodA(doubleClass, doubleValueOf, &v);
  }
  if (value.isString()) {
    return toJavaString(env, value.asString(*runtime));
  }
  if (value.isNull() || value.isUndefined()) {
    return nullptr;
  }
  if (value.isObject()) {
    jsi::Object obj = value.asObject(*runtime);
    if (obj.isArray(*runtime)) {
      jsi::Array arr = obj.asArray(*runtime);
      size_t len = arr.length(*runtime);
      jobjectArray result = env->NewObjectArray(static_cast<jsize>(len), objectClass, nullptr);
      for (size_t i = 0; i < len && !env->ExceptionCheck(); i++) {
        jobject el = toJavaObject(env, arr.getValueAtIndex(*runtime, i), false);
        if (env->ExceptionCheck()) break;
        env->SetObjectArrayElement(result, static_cast<jsize>(i), el);
        if (el) env->DeleteLocalRef(el);
      }
      if (env->ExceptionCheck()) {
        env->DeleteLocalRef(result);
        return nullptr;
      }
      return result;
    }
    // Kotlin/JS collections (map/set/list) are objects, not JS arrays. The guest identifies them
    // and drives the iteration through its value ops: without this a guest-authored collection
    // decodes to null.
    int kind = jsiBridgeCollectionKind(*runtime, value);
    if (kind != BRIDGE_COLLECTION_NONE) {
      jobject result = jsiCollectionToJava(
          env, *runtime, value, kind, jsiValueToBoxedLoud, jsiValueToBoxedLoud);
      if (env->ExceptionCheck()) return nullptr;
      if (result != nullptr) return result;
    }
    // Try bridge_dispatch (bridged Kotlin/JS object → Java).
    intptr_t ptr = jsi_get_bridge_dispatch(*runtime, value);
    if (ptr != 0) {
      JniBridgeDispatch* disp = reinterpret_cast<JniBridgeDispatch*>(ptr);
      jobject result = disp->toJavaObject(env, *runtime, value);
      if (env->ExceptionCheck()) return nullptr;
      if (result) return result;
    }
    // Boxed kotlin.Long: the guest reports its 32-bit halves (its own field names are mangled).
    jobject boxedLong = jsiBridgeTryUnwrapLong(env, *runtime, value);
    if (env->ExceptionCheck()) return nullptr;
    if (boxedLong != nullptr) return boxedLong;
  }
  if (throwOnUnsupportedType) {
    // Name the offending class when a Kotlin class instance has no converter. Plain data - a plain
    // JS object, a function, a Kotlin collection or Long, or kotlin.Unit (the value a Unit-returning
    // guest function, e.g. the direct-event sink, produces) - decodes to null, which is what it did
    // before and what the Kotlin/Native decoder does; only a class instance is an error.
    jsiThrowUnbridgedJsObject(env, *runtime, value);
  }
  return nullptr;
}

void ContextJni::throwJsException(JNIEnv* env, jsi::JSError& error) {
  std::string message = error.getMessage();
  std::string stack = error.getStack();

  // If a host function (OutboundCallChannel.call/disconnect) stashed a Java
  // throwable, re-throw that one verbatim. The Kotlin test suite
  // asserts on the exact Java exception class (e.g.
  // UnsupportedOperationException), so we can't just translate to a
  // JsException.
  if (pendingJavaException) {
    jobject local = env->NewLocalRef(pendingJavaException);
    env->DeleteGlobalRef(pendingJavaException);
    pendingJavaException = nullptr;

    // Splice the JS frames into the Java exception's stack so the unified
    // trace shows where in JS the failing host call was made. This mirrors
    // what the JsException(message, stack) constructor does; the helper is
    // the @JvmStatic companion function Throwable.addJavaScriptStack.
    jmethodID addJavaScriptStack = env->GetStaticMethodID(
        jsExceptionClass, "addJavaScriptStack",
        "(Ljava/lang/Throwable;Ljava/lang/String;)V");
    if (addJavaScriptStack) {
      jstring jStack = zipline::utf8ToJniString(env, stack);
      env->CallStaticVoidMethod(jsExceptionClass, addJavaScriptStack, local,
                                jStack);
      env->DeleteLocalRef(jStack);
    }

    env->Throw(static_cast<jthrowable>(local));
    env->DeleteLocalRef(local);
    return;
  }

  jstring jMessage = zipline::utf8ToJniString(env, message);
  jstring jStack = zipline::utf8ToJniString(env, stack);

  jobject exception = env->NewObject(
      jsExceptionClass, jsExceptionConstructor, jMessage, jStack);

  env->DeleteLocalRef(jMessage);
  env->DeleteLocalRef(jStack);

  env->Throw(static_cast<jthrowable>(exception));
}

jsi::Value ContextJni::throwJavaExceptionFromJs(JNIEnv* env) {
  assert(env->ExceptionCheck());
  jthrowable pending = env->ExceptionOccurred();
  env->ExceptionClear();

  // Stash the original throwable so throwJsException() can re-raise the
  // exact same Java exception (preserving the type, message, and stack) on
  // the outer C++ side.
  if (pendingJavaException) {
    env->DeleteGlobalRef(pendingJavaException);
  }
  pendingJavaException = static_cast<jthrowable>(env->NewGlobalRef(pending));

  // Build a JS-visible error message carrying the Java exception's
  // toString(). This shows up in the JS error stack trace.
  jstring msg = static_cast<jstring>(env->CallObjectMethod(
      pending,
      env->GetMethodID(env->FindClass("java/lang/Object"), "toString",
                       "()Ljava/lang/String;")));
  std::string cpp = toCppString(env, msg);

  // Throw a JS error from C++. The outer try/catch around
  // evaluateJavaScript / evaluatePreparedJavaScript catches this as a
  // jsi::JSError; the catch handler calls throwJsException which then
  // re-raises the original Java throwable.
  throw jsi::JSError(*runtime, cpp);
}

JNIEnv* ContextJni::getEnv() const {
  JNIEnv* env = nullptr;
  javaVm->GetEnv(reinterpret_cast<void**>(&env), jniVersion);
  if (env) return env;
  javaVm->AttachCurrentThread(
#ifdef __ANDROID__
      &env,
#else
      reinterpret_cast<void**>(&env),
#endif
      nullptr);
  if (env) {
    thread_local JniThreadDetacher detacher(javaVm);
  }
  return env;
}

std::string ContextJni::toCppString(JNIEnv* env, jstring javaString) const {
  if (stringGetBytes == nullptr || stringUtf8 == nullptr) return "";

  jbyteArray utf8BytesObject = static_cast<jbyteArray>(
      env->CallObjectMethod(javaString, stringGetBytes, stringUtf8));
  size_t n = env->GetArrayLength(utf8BytesObject);
  jbyte* bytes = env->GetByteArrayElements(utf8BytesObject, nullptr);
  std::string out(reinterpret_cast<char*>(bytes), n);
  env->ReleaseByteArrayElements(utf8BytesObject, bytes, JNI_ABORT);
  env->DeleteLocalRef(utf8BytesObject);
  return out;
}

jsi::String ContextJni::toJsString(JNIEnv* env, jstring javaString) const {
  std::string cpp = toCppString(env, javaString);
  return jsi::String::createFromUtf8(*runtime, cpp);
}

jstring ContextJni::toJavaString(JNIEnv* env, const std::string& utf8) const {
  jbyteArray bytes = env->NewByteArray(static_cast<jsize>(utf8.size()));
  env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(utf8.size()),
                          reinterpret_cast<const jbyte*>(utf8.data()));
  jstring result = static_cast<jstring>(
      env->NewObject(stringClass, stringConstructor, bytes, stringUtf8));
  env->DeleteLocalRef(bytes);
  return result;
}

jstring ContextJni::toJavaString(JNIEnv* env, const jsi::String& s) const {
  return toJavaString(env, s.utf8(*runtime));
}

jsi::String ContextJni::toJsString(const std::string& str) {
  return jsi::String::createFromUtf8(*runtime, str);
}

std::string ContextJni::toCppString(const jsi::String& str) {
  return str.utf8(*runtime);
}

void ContextJni::throwJsError(jsi::JSError& error) {
  throwJsException(getEnv(), error);
}

void ContextJni::throwJsException(const std::string& message) {
  JNIEnv* env = getEnv();
  if (env) {
    jstring jMsg = zipline::utf8ToJniString(env, message);
    jobject exception = env->NewObject(jsExceptionClass, jsExceptionConstructor, jMsg, nullptr);
    env->DeleteLocalRef(jMsg);
    env->Throw(static_cast<jthrowable>(exception));
  }
}

void ContextJni::cacheRdmaBridgeMethods(JNIEnv* env) {
  // The JDK collection classes back every change that carries a payload, not just the RDMA
  // bridge, so cache them before the optional RdmaBridge lookup below can bail out - otherwise a
  // build with no redwood on the classpath leaves them null and the serialization path crashes.
  if (this->arrayListClass == nullptr) {
    jclass alCls = findClassOrNull(env, "java/util/ArrayList");
    if (alCls != nullptr) {
      this->arrayListClass = alCls;
      this->arrayListInit = env->GetMethodID(alCls, "<init>", "()V");
      this->arrayListInitWithCapacity = env->GetMethodID(alCls, "<init>", "(I)V");
      this->arrayListAdd = env->GetMethodID(alCls, "add", "(Ljava/lang/Object;)Z");
    }
  }

  jclass cls = env->FindClass("app/cash/redwood/treehouse/RdmaBridge");
  if (!cls) {
    // RDMA is an optional integration: redwood-treehouse may be absent from
    // the classpath (e.g. plain zipline consumers/tests). FindClass leaves a
    // pending NoClassDefFoundError that must be cleared, and every following
    // GetStaticMethodID on a null class would be fatal.
    env->ExceptionClear();
    this->rdmaBridgeClass = nullptr;
    return;
  }
  this->rdmaBridgeClass = static_cast<jclass>(env->NewGlobalRef(cls));
  if (!this->rdmaBridgeClass) return;

  // JsonElement factories
  this->rdmaBridgeJsonPrimitiveString = env->GetStaticMethodID(
      cls, "jsonPrimitiveString",
      "(Ljava/lang/String;)Lkotlinx/serialization/json/JsonPrimitive;");
  this->rdmaBridgeJsonPrimitiveInt = env->GetStaticMethodID(
      cls, "jsonPrimitiveInt", "(I)Lkotlinx/serialization/json/JsonPrimitive;");
  this->rdmaBridgeJsonPrimitiveLong = env->GetStaticMethodID(
    cls, "jsonPrimitiveLong", "(J)Lkotlinx/serialization/json/JsonPrimitive;");
  this->rdmaBridgeJsonPrimitiveDouble = env->GetStaticMethodID(
    cls, "jsonPrimitiveDouble", "(D)Lkotlinx/serialization/json/JsonPrimitive;");
  this->rdmaBridgeJsonPrimitiveBoolean = env->GetStaticMethodID(
    cls, "jsonPrimitiveBoolean", "(Z)Lkotlinx/serialization/json/JsonPrimitive;");
  this->rdmaBridgeJsonNull = env->GetStaticMethodID(
    cls, "jsonNull", "()Lkotlinx/serialization/json/JsonNull;");
  this->rdmaBridgeCreateJsonArray = env->GetStaticMethodID(
    cls, "createJsonArray",
    "(Ljava/util/List;)Lkotlinx/serialization/json/JsonArray;");
  this->rdmaBridgeCreateJsonObject = env->GetStaticMethodID(
    cls, "createJsonObject",
    "(Ljava/util/List;Ljava/util/List;)Lkotlinx/serialization/json/JsonObject;");

  pendingChanges.reserve(RDMA_BATCH_SIZE);
}

void ContextJni::cacheRdmaSink(jobject sink) {
  if (!sink) return;
  JNIEnv* env = getEnv();
  if (!env) return;

  rdmaChangeSink = env->NewGlobalRef(sink);
  if (!rdmaChangeSink) return;

  jclass sinkCls = env->FindClass("app/cash/zipline/RdmaChangeSink");
  if (!sinkCls) {
    env->ExceptionClear();
    env->DeleteGlobalRef(rdmaChangeSink);
    rdmaChangeSink = nullptr;
    return;
  }
  rdmaSinkCreateCreate = env->GetMethodID(sinkCls, "createCreate", "(II)V");
  rdmaSinkCreatePropertyChange = env->GetMethodID(
      sinkCls, "createPropertyChange",
      "(IIILkotlinx/serialization/json/JsonElement;)V");
  rdmaSinkCreateModifierChange = env->GetMethodID(
      sinkCls, "createModifierChange", "(ILjava/util/List;)V");
  rdmaSinkCreateAdd = env->GetMethodID(sinkCls, "createAdd", "(IIII)V");
  rdmaSinkCreateRemove = env->GetMethodID(sinkCls, "createRemove", "(IIIZ)V");
  rdmaSinkCreateMove = env->GetMethodID(sinkCls, "createMove", "(IIIII)V");
  rdmaSinkCreateBridgeChange = env->GetMethodID(
      sinkCls, "createBridgeChange", "(ILjava/lang/Object;)V");
  rdmaSinkSetRemoveDetach = env->GetMethodID(sinkCls, "setRemoveDetach", "(I)V");
  rdmaSinkSendBatch = env->GetMethodID(sinkCls, "sendBatch", "()V");
  rdmaSinkSendChanges = env->GetMethodID(sinkCls, "sendChanges", "()V");
  env->DeleteLocalRef(sinkCls);

  jclass pairCls = env->FindClass("kotlin/Pair");
  pairClass = static_cast<jclass>(env->NewGlobalRef(pairCls));
  pairInit = env->GetMethodID(pairCls, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V");
  env->DeleteLocalRef(pairCls);
}

jobject ContextJni::jsValueToJsonElement(JNIEnv* env, const jsi::Value& val) {
  if (val.isNumber()) {
    double v = val.asNumber();
    int64_t lv;
    if (tryAsInt64(v, lv)) { // Whether JS number is integral and fits in long
      if (lv >= INT32_MIN && lv <= INT32_MAX) {
        return env->CallStaticObjectMethod(
            rdmaBridgeClass, rdmaBridgeJsonPrimitiveInt, static_cast<jint>(lv));
      }
      return env->CallStaticObjectMethod(
          rdmaBridgeClass, rdmaBridgeJsonPrimitiveLong, lv);
    }
    return env->CallStaticObjectMethod(
        rdmaBridgeClass, rdmaBridgeJsonPrimitiveDouble, v);
  }
  if (val.isBool()) {
    jboolean v = val.asBool() ? JNI_TRUE : JNI_FALSE;
    return env->CallStaticObjectMethod(
        rdmaBridgeClass, rdmaBridgeJsonPrimitiveBoolean, v);
  }
  if (val.isString()) {
    std::string s = val.asString(*runtime).utf8(*runtime);
    jstring js = zipline::utf8ToJniString(env, s);
    jobject result = env->CallStaticObjectMethod(
        rdmaBridgeClass, rdmaBridgeJsonPrimitiveString, js);
    env->DeleteLocalRef(js);
    return result;
  }
  if (val.isNull() || val.isUndefined()) {
    return env->CallStaticObjectMethod(
        rdmaBridgeClass, rdmaBridgeJsonNull);
  }
  if (val.isObject()) {
    jsi::Object obj = val.asObject(*runtime);
    if (obj.isArray(*runtime)) {
      return jsArrayToJsonElement(env, val);
    } else {
      return jsObjectToJsonElement(env, val);
    }
  }
  return nullptr;
}

jobject ContextJni::jsArrayToJsonElement(JNIEnv* env, const jsi::Value& val) {
  jsi::Array arr = val.asObject(*runtime).asArray(*runtime);
  size_t length = arr.length(*runtime);

  jobject arrayList = env->NewObject(arrayListClass, arrayListInitWithCapacity, (int)length);
  for (size_t i = 0; i < length; i++) {
    jsi::Value element = arr.getValueAtIndex(*runtime, i);
    jobject jsonElement = jsValueToJsonElement(env, element);
    if (jsonElement != nullptr) {
      env->CallBooleanMethod(arrayList, arrayListAdd, jsonElement);
      env->DeleteLocalRef(jsonElement);
    }
  }
  jobject result = env->CallStaticObjectMethod(
      rdmaBridgeClass, rdmaBridgeCreateJsonArray, arrayList);
  env->DeleteLocalRef(arrayList);
  return result;
}

jobject ContextJni::jsObjectToJsonElement(JNIEnv* env, const jsi::Value& val) {
  jsi::Object obj = val.asObject(*runtime);
  jsi::Array propertyNames = obj.getPropertyNames(*runtime);
  size_t numProps = propertyNames.length(*runtime);

  jobject keysList = env->NewObject(arrayListClass, arrayListInitWithCapacity, (int)numProps);
  jobject valuesList = env->NewObject(arrayListClass, arrayListInitWithCapacity, (int)numProps);

  for (size_t i = 0; i < numProps; i++) {
    jsi::Value propName = propertyNames.getValueAtIndex(*runtime, i);
    if (!propName.isString()) continue;
    std::string key = propName.asString(*runtime).utf8(*runtime);
    jsi::Value propVal = obj.getProperty(*runtime, key.c_str());
    jstring keyJava = zipline::utf8ToJniString(env, key);
    jobject jsonElement = jsValueToJsonElement(env, propVal);
    if (jsonElement != nullptr) {
      env->CallBooleanMethod(keysList, arrayListAdd, keyJava);
      env->CallBooleanMethod(valuesList, arrayListAdd, jsonElement);
      env->DeleteLocalRef(jsonElement);
    }
    env->DeleteLocalRef(keyJava);
  }

  jobject result = env->CallStaticObjectMethod(
      rdmaBridgeClass, rdmaBridgeCreateJsonObject, keysList, valuesList);
  env->DeleteLocalRef(keysList);
  env->DeleteLocalRef(valuesList);
  return result;
}

void ContextJni::dispatchChangeToSink(JNIEnv* env, const RdmaChange& ch) {
  switch (ch.type) {
    case RdmaChangeType::Create:
      env->CallVoidMethod(rdmaChangeSink, rdmaSinkCreateCreate, ch.id, ch.field1);
      break;
    case RdmaChangeType::PropertyChange: {
      jobject jsonElement = jsValueToJsonElement(env, *ch.jsValue);
      env->CallVoidMethod(rdmaChangeSink, rdmaSinkCreatePropertyChange,
          ch.id, ch.field1, ch.field2, jsonElement);
      if (jsonElement) env->DeleteLocalRef(jsonElement);
      break;
    }
    case RdmaChangeType::ModifierChange: {
      jobject elementsList = env->NewObject(arrayListClass, arrayListInit);
      if (ch.jsValue && ch.jsValue->isObject() && ch.jsValue->asObject(*runtime).isArray(*runtime)) {
        jsi::Array arr = ch.jsValue->asObject(*runtime).asArray(*runtime);
        size_t numElements = arr.length(*runtime);
        for (size_t j = 0; j < numElements; j++) {
          jsi::Value elem = arr.getValueAtIndex(*runtime, j);
          jsi::Value modTagVal = elem.asObject(*runtime).getProperty(*runtime, "0");
          int mTag = static_cast<int>(modTagVal.asNumber());
          jsi::Value modVal = elem.asObject(*runtime).getProperty(*runtime, "1");
          jobject jModVal = modVal.isUndefined()
              ? env->CallStaticObjectMethod(rdmaBridgeClass, rdmaBridgeJsonNull)
              : jsValueToJsonElement(env, modVal);
          jobject jTag = env->CallStaticObjectMethod(integerClass, integerValueOf, (jint)mTag);
          jobject pair = env->NewObject(pairClass, pairInit, jTag, jModVal);
          env->CallBooleanMethod(elementsList, arrayListAdd, pair);
          if (jTag) env->DeleteLocalRef(jTag);
          if (jModVal) env->DeleteLocalRef(jModVal);
          env->DeleteLocalRef(pair);
        }
      }
      env->CallVoidMethod(rdmaChangeSink, rdmaSinkCreateModifierChange, ch.id, elementsList);
      env->DeleteLocalRef(elementsList);
      break;
    }
    case RdmaChangeType::Add:
      env->CallVoidMethod(rdmaChangeSink, rdmaSinkCreateAdd, ch.id, ch.field1, ch.field2, ch.field3);
      break;
    case RdmaChangeType::Remove:
      env->CallVoidMethod(rdmaChangeSink, rdmaSinkCreateRemove,
          ch.id, ch.field1, ch.field2, ch.detach ? JNI_TRUE : JNI_FALSE);
      break;
    case RdmaChangeType::Move:
      env->CallVoidMethod(rdmaChangeSink, rdmaSinkCreateMove, ch.id, ch.field1, ch.field2, ch.field3, ch.count);
      break;
    case RdmaChangeType::BridgeChange: {
      if (!ch.jsValue || !ch.jsValue->isObject()) return;
      jsi::Runtime& rt = getRuntime();
      jsi::Object obj = ch.jsValue->asObject(rt);
      jsi::Value lowVal = obj.getProperty(rt, "bridge_dispatch_low");
      jsi::Value highVal = obj.getProperty(rt, "bridge_dispatch_high");
      if (!lowVal.isUndefined() && !highVal.isUndefined()) {
        int32_t low = static_cast<int32_t>(lowVal.asNumber());
        int32_t high = static_cast<int32_t>(highVal.asNumber());
        intptr_t ptr = (static_cast<intptr_t>(high) << 32) |
                       static_cast<intptr_t>(static_cast<uint32_t>(low));
        JniBridgeDispatch* disp = reinterpret_cast<JniBridgeDispatch*>(ptr);
        jobject uiChange = disp->toJavaObject(env, rt, *ch.jsValue);
        if (!uiChange) {
          return;
        }
        env->CallVoidMethod(rdmaChangeSink, rdmaSinkCreateBridgeChange, ch.id, uiChange);
        env->DeleteLocalRef(uiChange);
      }
      break;
    }
  }
}

void ContextJni::flushPendingBatch(JNIEnv* env, int toFlush) {
  if (rdmaChangeSink == nullptr) {
    pendingChanges.erase(pendingChanges.begin(), pendingChanges.begin() + toFlush);
    return;
  }

  for (int i = 0; i < toFlush; i++) {
    dispatchChangeToSink(env, pendingChanges[i]);
  }
  env->CallVoidMethod(rdmaChangeSink, rdmaSinkSendBatch);
  pendingChanges.erase(pendingChanges.begin(), pendingChanges.begin() + toFlush);
}

void ContextJni::finishFlushPending(JNIEnv* env) {
  int remaining = (int)pendingChanges.size();
  if (remaining == 0) return;

  if (rdmaChangeSink == nullptr) {
    pendingChanges.clear();
    return;
  }

  for (int i = 0; i < remaining; i++) {
    dispatchChangeToSink(env, pendingChanges[i]);
  }
  env->CallVoidMethod(rdmaChangeSink, rdmaSinkSendChanges);
  pendingChanges.clear();
}

static inline void flushIfBatchFull(ContextJni* context) {
  if ((int)context->pendingChanges.size() >= RDMA_BATCH_SIZE) {
    auto env = context->getEnv();
    if (env) context->flushPendingBatch(env, RDMA_BATCH_SIZE);
  }
}

static jsi::Value rdmaAppendBridgeChange(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc
) {
  (void)thisVal;
  RdmaChange ch;
  ch.type = RdmaChangeType::BridgeChange;
  ch.id = static_cast<int>(args[0].asNumber());
  ch.jsValue = std::make_shared<jsi::Value>(rt, args[1]);
  ch.field1 = 0;
  ch.field2 = 0;
  ch.field3 = 0;
  ch.count = 0;
  ch.detach = false;
  context->pendingChanges.push_back(std::move(ch));
  flushIfBatchFull(context);
  return jsi::Value::undefined();
}

static jsi::Value rdmaAppendCreate(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  RdmaChange ch;
  ch.type = RdmaChangeType::Create;
  ch.id = static_cast<int>(args[0].asNumber());
  ch.field1 = static_cast<int>(args[1].asNumber());
  ch.jsValue = nullptr;
  context->pendingChanges.push_back(std::move(ch));
  flushIfBatchFull(context);
  return jsi::Value::undefined();
}

static jsi::Value rdmaAppendPropertyChange(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  RdmaChange ch;
  ch.type = RdmaChangeType::PropertyChange;
  ch.id = static_cast<int>(args[0].asNumber());
  ch.field1 = static_cast<int>(args[1].asNumber());
  ch.field2 = static_cast<int>(args[2].asNumber());
  ch.jsValue = std::make_shared<jsi::Value>(rt, args[3]);
  context->pendingChanges.push_back(std::move(ch));
  flushIfBatchFull(context);
  return jsi::Value::undefined();
}

static jsi::Value rdmaAppendModifierChange(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  RdmaChange ch;
  ch.type = RdmaChangeType::ModifierChange;
  ch.id = static_cast<int>(args[0].asNumber());
  ch.jsValue = std::make_shared<jsi::Value>(rt, args[1]);
  context->pendingChanges.push_back(std::move(ch));
  flushIfBatchFull(context);
  return jsi::Value::undefined();
}

static jsi::Value rdmaAppendAdd(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  RdmaChange ch;
  ch.type = RdmaChangeType::Add;
  ch.id = static_cast<int>(args[0].asNumber());
  ch.field1 = static_cast<int>(args[1].asNumber());
  ch.field2 = static_cast<int>(args[2].asNumber());
  ch.field3 = static_cast<int>(args[3].asNumber());
  ch.jsValue = nullptr;
  context->pendingChanges.push_back(std::move(ch));
  flushIfBatchFull(context);
  return jsi::Value::undefined();
}

static jsi::Value rdmaAppendRemove(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  RdmaChange ch;
  ch.type = RdmaChangeType::Remove;
  ch.id = static_cast<int>(args[0].asNumber());
  ch.field1 = static_cast<int>(args[1].asNumber());
  ch.field2 = static_cast<int>(args[2].asNumber());
  ch.detach = false;
  ch.jsValue = nullptr;
  context->pendingChanges.push_back(std::move(ch));
  flushIfBatchFull(context);
  return jsi::Value::undefined();
}

static jsi::Value rdmaSetRemoveDetach(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  int idx = static_cast<int>(args[0].asNumber());
  // The sink tracks remove changes in its own accumulated batch (same
  // positional semantics as the Kotlin/Native implementation).
  auto env = context->getEnv();
  if (env != nullptr && context->rdmaChangeSink != nullptr) {
    env->CallVoidMethod(context->rdmaChangeSink, context->rdmaSinkSetRemoveDetach, idx);
  }
  return jsi::Value::undefined();
}

static jsi::Value rdmaAppendMove(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  RdmaChange ch;
  ch.type = RdmaChangeType::Move;
  ch.id = static_cast<int>(args[0].asNumber());
  ch.field1 = static_cast<int>(args[1].asNumber());
  ch.field2 = static_cast<int>(args[2].asNumber());
  ch.field3 = static_cast<int>(args[3].asNumber());
  ch.count = static_cast<int>(args[4].asNumber());
  ch.jsValue = nullptr;
  context->pendingChanges.push_back(std::move(ch));
  flushIfBatchFull(context);
  return jsi::Value::undefined();
}

static jsi::Value rdmaFinishChangesCallback(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  auto env = context->getEnv();
  if (!env) return jsi::Value::undefined();

  context->finishFlushPending(env);
  return jsi::Value::undefined();
}

static jsi::Value rdmaChangesLengthCallback(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  int size = (int)context->pendingChanges.size();
  return jsi::Value(size);
}

void ContextJni::initRdmaChangesChannel(JNIEnv* env, jobject rdmaChangeSink) {
  if (!rdmaChangeSink) return;
  cacheRdmaSink(rdmaChangeSink);
  cacheRdmaBridgeMethods(env);
  if (!this->rdmaBridgeClass) {
    // Redwood is not on the classpath; leave the RDMA channel uninstalled
    // rather than exposing a channel whose flush would crash.
    return;
  }

  jsi::Runtime& rt = *runtime;
  jsi::Object globalObject = rt.global();

  jsi::Object rdmaObj = jsi::Object(rt);
  ContextJni* context = this;

  rdmaObj.setProperty(rt, "appendBridgeChange",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "appendBridgeChange"), 2,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return rdmaAppendBridgeChange(rt, context, thisVal, args, argc);
          }));
  rdmaObj.setProperty(rt, "appendCreate",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "appendCreate"), 2,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return rdmaAppendCreate(rt, context, thisVal, args, argc);
          }));
  rdmaObj.setProperty(rt, "appendPropertyChange",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "appendPropertyChange"), 4,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return rdmaAppendPropertyChange(rt, context, thisVal, args, argc);
          }));
  rdmaObj.setProperty(rt, "appendModifierChange",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "appendModifierChange"), 2,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return rdmaAppendModifierChange(rt, context, thisVal, args, argc);
          }));
  rdmaObj.setProperty(rt, "appendAdd",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "appendAdd"), 4,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return rdmaAppendAdd(rt, context, thisVal, args, argc);
          }));
  rdmaObj.setProperty(rt, "appendRemove",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "appendRemove"), 3,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return rdmaAppendRemove(rt, context, thisVal, args, argc);
          }));
  rdmaObj.setProperty(rt, "setRemoveDetach",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "setRemoveDetach"), 1,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return rdmaSetRemoveDetach(rt, context, thisVal, args, argc);
          }));
  rdmaObj.setProperty(rt, "appendMove",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "appendMove"), 5,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return rdmaAppendMove(rt, context, thisVal, args, argc);
          }));
  rdmaObj.setProperty(rt, "finishChanges",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "finishChanges"), 0,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return rdmaFinishChangesCallback(rt, context, thisVal, args, argc);
          }));
  rdmaObj.setProperty(rt, "changesLength",
      jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "changesLength"), 0,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return rdmaChangesLengthCallback(rt, context, thisVal, args, argc);
          }));

  globalObject.setProperty(rt, "app_cash_redwood_rdmaSendChanges", rdmaObj);
}
