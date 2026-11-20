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

namespace {

// The guest's class prototypes and runtime factories are kept in JS globals (named by
// bridge_dispatch.h, which the converters read) so that a converter compiled into a consumer
// library - a separate .so - can reach them with nothing but the runtime. They are
// per-runtime by construction: each Hermes runtime has its own global object.
jsi::Object globalBridgeMap(jsi::Runtime &rt, const char *name) {
  jsi::Value existing = rt.global().getProperty(rt, name);
  if (existing.isObject()) return existing.asObject(rt);
  jsi::Object created(rt);
  rt.global().setProperty(rt, name, created);
  return created;
}

// Copy every property of [source] that the host could read on it -- own or inherited -- onto
// [target]. Used for the globals the guest fills in module by module; see the
// __bridgeRegisterRuntime hook.
// jsi::Object has no copy or move constructor, so both handles come in by reference.
// Records that a guest registration arrived unusable, and what arrived, into a global the host reads
// back after each module load. Recorded rather than thrown: the guest wraps its own
// `__bridgeRegisterRuntime` call in a tolerant try/catch, so an exception raised here cannot escape --
// the defect survives to a much later host->JS conversion as "the guest provides no create_<fqn>",
// naming neither the module nor the reason. Only the first problem is kept: it is the one that
// happened, everything after it is downstream.
void recordFactoriesProblem(jsi::Runtime &rt, const std::string &detail) {
    try {
        if (rt.global().getProperty(rt, "__zipline_bridgeFactoriesProblem").isString()) return;
        // The bad registration is attributed to the module that was loading, which the loader keeps
        // in this global (CURRENT_MODULE_ID in hostMain's defineJs.kt). Without it the report can only
        // name whichever module the host checked first, and a module that loaded cleanly before the
        // culprit would be blamed for it.
        std::string message = detail;
        jsi::Value loading = rt.global().getProperty(rt, "app_cash_zipline_currentModuleId");
        if (loading.isString()) message += " (while loading " + loading.asString(rt).utf8(rt) + ")";
        rt.global().setProperty(
            rt, "__zipline_bridgeFactoriesProblem", jsi::String::createFromUtf8(rt, message));
    } catch (const std::exception &) {
    }
}

// jsi::Value cannot be copied, so this takes the hook's argument array rather than a value: the
// "called with nothing" case has no value to name.
const char *describeBridgeArgument(const jsi::Value *args, size_t argc) {
    if (argc < 1) return "nothing";
    const jsi::Value &value = args[0];
    if (value.isUndefined()) return "undefined";
    if (value.isNull()) return "null";
    if (value.isBool()) return "a boolean";
    if (value.isNumber()) return "a number";
    if (value.isString()) return "a string";
    if (value.isSymbol()) return "a symbol";
    return "a value of no recognisable kind";
}

void mergeBridgeFactories(jsi::Runtime &rt, jsi::Object &target, jsi::Object &source) {
  // The guest's factories object is a Kotlin/JS `object` instance: newLong, newArrayList and every
  // create-factory are methods on its PROTOTYPE and are not enumerable, so copying the own enumerable
  // keys copies nothing at all -- the host would keep an empty map and report "the guest provides no
  // create_..." for every class. Walk the prototype chain instead, which is what a property read on
  // the instance does, so whatever the host could find on the instance it can still find here.
  jsi::Object objectCtor = rt.global().getPropertyAsObject(rt, "Object");
  jsi::Function getOwnPropertyNames = objectCtor.getPropertyAsFunction(rt, "getOwnPropertyNames");
  jsi::Function getPrototypeOf = objectCtor.getPropertyAsFunction(rt, "getPrototypeOf");
  jsi::Value objectPrototype = objectCtor.getProperty(rt, "prototype");

  jsi::Value current = jsi::Value(rt, source);
  while (current.isObject() && !jsi::Value::strictEquals(rt, current, objectPrototype)) {
    jsi::Value args[1] = {jsi::Value(rt, current.asObject(rt))};
    jsi::Object names =
        getOwnPropertyNames.call(rt, static_cast<const jsi::Value *>(args), static_cast<size_t>(1))
            .asObject(rt);
    // Explicit Value indices: a bare integer is ambiguous against jsi::Value's converting
    // constructors, and this jsi has no Object::get(Runtime&, Value).
    double count = names.getProperty(rt, "length").asNumber();
    for (double i = 0; i < count; i++) {
      jsi::Value name = names.getProperty(rt, jsi::Value(i));
      std::string key = name.asString(rt).utf8(rt);
      if (key == "constructor") continue;
      target.setProperty(
          rt, jsi::PropNameID::forUtf8(rt, key), current.asObject(rt).getProperty(rt, name));
    }
    current = getPrototypeOf.call(rt, static_cast<const jsi::Value *>(args), static_cast<size_t>(1));
  }
}
}  // namespace

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
            jsi::Object proto = ctor.getPropertyAsObject(rt, "prototype");

            // Retain the prototype: the host needs it to build instances of this class for a
            // host->JS conversion, including for classes that only ever originate host-side.
            globalBridgeMap(rt, HOST2JS_PROTOTYPES_GLOBAL)
                .setProperty(rt, fq.c_str(), jsi::Value(rt, proto));

            // Attach the JS->host converter when one is registered. A class that only travels
            // host->JS has none, which is not an error; a class the host cannot convert either way
            // fails later, by name, at the conversion.
            for (auto& entry : bridgeTable) {
                if (strcmp(entry.first.c_str(), fq.c_str()) == 0) {
                    JniBridgeDispatch *disp = new JniBridgeDispatch();
                    disp->toJavaObject = entry.second;
                    // Store pointer as two int32 halves (JS double has 53-bit mantissa;
                    // ARM64 pointers need 64 bits, so split into two 32-bit values).
                    intptr_t ptr = reinterpret_cast<intptr_t>(disp);
                    int32_t low  = static_cast<int32_t>(ptr & 0xFFFFFFFF);
                    int32_t high = static_cast<int32_t>((ptr >> 32) & 0xFFFFFFFF);
                    proto.setProperty(rt, "bridge_dispatch_low",  jsi::Value(static_cast<double>(low)));
                    proto.setProperty(rt, "bridge_dispatch_high", jsi::Value(static_cast<double>(high)));
                    break;
                }
            }
            return jsi::Value::undefined();
        });

    auto bridgeRegisterRuntimeFn = jsi::Function::createFromHostFunction(
        rt,
        jsi::PropNameID::forUtf8(rt, "__bridgeRegisterRuntime"),
        1,
        [](jsi::Runtime& rt, const jsi::Value&, const jsi::Value* args, size_t argc) -> jsi::Value {
            if (argc < 1 || !args[0].isObject()) {
              // The call site is generated, so a non-object is not a caller mistake: the construction
              // the plugin emitted produced nothing at all.
              recordFactoriesProblem(rt,
                  std::string("__bridgeRegisterRuntime was called with ") +
                      describeBridgeArgument(args, argc) +
                      " instead of a factories object");
              return jsi::Value::undefined();
            }
            // The factories build real Kotlin/JS Long/ArrayList/LinkedHashMap instances; they are
            // the guest's own stdlib calls, so nothing here relies on Kotlin/JS internals. The
            // instance carries newLong/newArrayList/newLinkedHashMap through its prototype, so
            // the host can read them off it directly.
            //
            // MERGE, do not replace. Every bridged module calls __bridgeRegisterRuntime when it is
            // warmed up, and each passes its own factories object -- prototypes are merged the same
            // way just above, and for the same reason. Assigning the global would hand ownership to
            // whichever module loaded last and silently discard every other module's create-factories
            // and runtime factories. An application has dozens of modules; a test bundle has one,
            // which is exactly why this is invisible until the app is run: the host then looks up
            // `create_<fqn>` for a class whose module loaded earlier and finds nothing.
            jsi::Object factories = globalBridgeMap(rt, HOST2JS_FACTORIES_GLOBAL);
            jsi::Object published = args[0].asObject(rt);
            // Merged rather than assigned: the guest publishes one factories object per warmed-up
            // module, so assigning hands the global to whichever module warmed up last and silently
            // discards every other module's create-factories and newLong. A test bundle has a single
            // bridged module -- one registration, where the two behave identically -- and compose-live's
            // first host->JS payload is what told them apart.
            //
            // Merging is unconditional, and that is deliberate. Skipping repeats by marking the source's
            // constructor was tried, and is not sound: two registrations whose sources are plain object
            // literals share `Object` as their constructor, so the second is silently dropped -- caught
            // by `a second module's runtime registration keeps the first module's factories` in
            // Host2JsRuntimeTest. Repeated registrations from one module would cost a few thousand
            // property copies at load; the merge is the part worth keeping simple.
            mergeBridgeFactories(rt, factories, published);
            jsi::Value newLong = factories.getProperty(rt, "newLong");
            if (!newLong.isObject() || !newLong.asObject(rt).isFunction(rt)) {
              recordFactoriesProblem(rt,
                  "the published factories object carries no newLong, so it had no methods to merge");
            }
            return jsi::Value::undefined();
        });

    jsi::Object globalObject = rt.global();
    globalObject.setProperty(rt, "__bridgeRegister", std::move(bridgeRegisterFn));
    globalObject.setProperty(rt, "__bridgeRegisterRuntime", std::move(bridgeRegisterRuntimeFn));
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

/**
 * Reports a pending Java exception and clears it. A conversion that failed leaves one pending;
 * continuing to call JNI with it pending aborts the VM, which hides the original failure, and the
 * caller is dropping the work item anyway. The message goes to logcat (Android) or stderr (JVM)
 * so the real cause stays visible.
 */
/**
 * Reports a pending Java exception and puts the *same* throwable back.
 *
 * The sink paths bail rather than make another JNI call, which is right, but bailing quietly is not:
 * the exception reaches Kotlin inside a frame-clock coroutine, which dies without a log, so "every
 * module downloaded and then nothing but loading placeholders" had no author. Naming it is free, and
 * re-throwing keeps the propagation that makes it somebody's problem. toString() is itself a JNI call,
 * so the exception must be cleared first and re-thrown after.
 */
void reportAndKeepPendingException(JNIEnv* env, const char* what) {
  if (!env->ExceptionCheck()) return;
  jthrowable pending = env->ExceptionOccurred();
  env->ExceptionClear();
  std::string description = "<unreadable>";
  if (pending != nullptr) {
    jclass throwableClass = env->FindClass("java/lang/Throwable");
    if (throwableClass != nullptr) {
      jmethodID toString = env->GetMethodID(throwableClass, "toString", "()Ljava/lang/String;");
      if (toString != nullptr) {
        jstring text = static_cast<jstring>(env->CallObjectMethod(pending, toString));
        if (!env->ExceptionCheck() && text != nullptr) {
          description = zipline::jniStringToUtf8(env, text);
        }
      }
      env->ExceptionClear();
      env->DeleteLocalRef(throwableClass);
    }
    env->ExceptionClear();
    if (env->Throw(pending) != JNI_OK) description += " (re-throwing it failed too)";
    env->DeleteLocalRef(pending);
  }
#ifdef __ANDROID__
  __android_log_print(ANDROID_LOG_ERROR, "BRIDGE", "%s: %s", what, description.c_str());
#else
  fprintf(stderr, "BRIDGE: %s: %s\n", what, description.c_str());
#endif
}

void reportAndClearPendingException(JNIEnv* env, const char* what) {
  if (!env->ExceptionCheck()) return;
  jthrowable pending = env->ExceptionOccurred();
  env->ExceptionClear();
  std::string description;
  if (pending != nullptr) {
    jclass throwableClass = env->FindClass("java/lang/Throwable");
    if (throwableClass != nullptr) {
      jmethodID toString = env->GetMethodID(throwableClass, "toString", "()Ljava/lang/String;");
      if (toString != nullptr) {
        jstring text = static_cast<jstring>(env->CallObjectMethod(pending, toString));
        if (!env->ExceptionCheck() && text != nullptr) {
          description = zipline::jniStringToUtf8(env, text);
        }
        env->ExceptionClear();
      }
      env->DeleteLocalRef(throwableClass);
    }
    env->ExceptionClear();
    env->DeleteLocalRef(pending);
  }
#ifdef __ANDROID__
  __android_log_print(ANDROID_LOG_ERROR, "BRIDGE", "%s: %s", what, description.c_str());
#else
  fprintf(stderr, "BRIDGE: %s: %s\n", what, description.c_str());
#endif
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
  if (directBridgeClass != nullptr) {
    env->DeleteGlobalRef(directBridgeClass);
      directBridgeClass = nullptr;
  }
  if (directChangeSink != nullptr) {
    env->DeleteGlobalRef(directChangeSink);
      directChangeSink = nullptr;
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
  // Guest code that called into a host bridge may have left a Java exception pending. Every
  // further JNI call with one pending is undefined and aborts the VM, so report it and stop.
  if (env->ExceptionCheck()) return nullptr;
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
  // Use time is loud: an unbridged class instance returned to the host throws
  // ZiplineApiMismatchException instead of decoding to a silent null. Plain JS data and
  // kotlin.Unit stay exempt (jsiIsUnbridgedDecodeExemptClass).
  return toJavaObject(env, result, /*throwOnUnsupportedType=*/true);
}

jobject ContextJni::evaluate(JNIEnv* env, jstring source, jstring fileName) {
#ifdef HERMESVM_LEAN
  throwJavaException(env, "java/lang/UnsupportedOperationException",
                     "evaluate() is not available in lean Hermes build");
  return nullptr;
#else
  // Guest code that called into a host bridge may have left a Java exception pending. Every
  // further JNI call with one pending is undefined and aborts the VM, so report it and stop.
  if (env->ExceptionCheck()) return nullptr;
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
  return toJavaObject(env, result, /*throwOnUnsupportedType=*/true);
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

// The padded public entry: no C++ exception may escape into the JNI frame, where it would
// reach std::terminate. Every JS->host decode crosses here.
jobject
ContextJni::toJavaObject(JNIEnv* env, const jsi::Value& value, bool throwOnUnsupportedType) {
  try {
    return toJavaObjectUnchecked(env, value, throwOnUnsupportedType);
  } catch (const jsi::JSIException& e) {
    // jsi::JSError and JSINativeException both derive from JSIException; the number/bool
    // reads throw the sibling of JSError, so catching only JSError leaves those aborting.
    jsiThrowBridgeEscape(env, "toJavaObject", e.what());
    return nullptr;
  } catch (const std::exception& e) {
    jsiThrowBridgeEscape(env, "toJavaObject", e.what());
    return nullptr;
  }
}

jobject
ContextJni::toJavaObjectUnchecked(JNIEnv* env, const jsi::Value& value, bool throwOnUnsupportedType) {
  // A pending Java exception (e.g. a host bridge that threw while guest code ran) makes every
  // further JNI call undefined; never start a conversion on top of one.
  if (env->ExceptionCheck()) return nullptr;
  if (value.isBool()) {
    jvalue v;
    v.z = value.asBool() ? JNI_TRUE : JNI_FALSE;
    jobject boxed = env->CallStaticObjectMethodA(booleanClass, booleanValueOf, &v);
    if (env->ExceptionCheck()) return nullptr;
    return boxed;
  }
  if (value.isNumber()) {
    double d = value.asNumber();
    // If it's representable as int, box as Integer; otherwise Double.
    int32_t asInt;
    if (tryAsInt32(d, asInt)) {
      jvalue v;
      v.i = asInt;
      jobject boxed = env->CallStaticObjectMethodA(integerClass, integerValueOf, &v);
      if (env->ExceptionCheck()) return nullptr;
      return boxed;
    }
    jvalue v;
    v.d = d;
    jobject boxed = env->CallStaticObjectMethodA(doubleClass, doubleValueOf, &v);
    if (env->ExceptionCheck()) return nullptr;
    return boxed;
  }
  if (value.isString()) {
    jstring result = toJavaString(env, value.asString(*runtime));
    if (env->ExceptionCheck()) return nullptr;
    return result;
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
        jobject el = toJavaObjectUnchecked(env, arr.getValueAtIndex(*runtime, i), false);
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

bool ContextJni::hasPendingPlatformException() {
  JNIEnv* env = getEnv();
  return env != nullptr && env->ExceptionCheck();
}

jboolean ContextJni::hasGlobalFunction(JNIEnv* env, jstring name) {
  if (env->ExceptionCheck()) return JNI_FALSE;
  std::string functionName = toCppString(env, name);
  if (env->ExceptionCheck()) return JNI_FALSE;
  try {
    jsi::Value value = runtime->global().getProperty(*runtime, functionName.c_str());
    if (!value.isObject() || !value.asObject(*runtime).isFunction(*runtime)) return JNI_FALSE;
    return JNI_TRUE;
  } catch (const jsi::JSError& e) {
    throwJsException(env, const_cast<jsi::JSError&>(e));
    return JNI_FALSE;
  }
}

jobject ContextJni::callGuestFunction(JNIEnv* env, jstring name, jobject argsList) {
  if (env->ExceptionCheck()) return nullptr;
  std::string functionName = toCppString(env, name);
  if (env->ExceptionCheck()) return nullptr;
  try {
    jsi::Runtime& rt = *runtime;
    jsi::Value function = rt.global().getProperty(rt, functionName.c_str());
    if (!function.isObject() || !function.asObject(rt).isFunction(rt)) {
      throwJavaException(env, "java/lang/IllegalStateException",
                         "JavaScript global function %s was not found",
                         functionName.c_str());
      return nullptr;
    }

    // Arguments cross host->JS one by one; a value that has no counterpart fails loudly here
    // rather than arriving as undefined on the guest side.
    std::vector<jsi::Value> args;
    if (argsList != nullptr) {
      jclass listClass = env->FindClass("java/util/List");
      jmethodID sizeMethod = env->GetMethodID(listClass, "size", "()I");
      jmethodID getMethod = env->GetMethodID(listClass, "get", "(I)Ljava/lang/Object;");
      jint size = env->CallIntMethod(argsList, sizeMethod);
      if (env->ExceptionCheck()) {
        env->DeleteLocalRef(listClass);
        return nullptr;
      }
      for (jint i = 0; i < size && !env->ExceptionCheck(); i++) {
        jobject arg = env->CallObjectMethod(argsList, getMethod, i);
        if (env->ExceptionCheck()) {
          if (arg) env->DeleteLocalRef(arg);
          break;
        }
        args.push_back(jsiHost2JsAnyToJs(env, rt, arg));
        if (arg) env->DeleteLocalRef(arg);
        if (env->ExceptionCheck()) break;
      }
      env->DeleteLocalRef(listClass);
      if (env->ExceptionCheck()) return nullptr;
    }

    jsi::Value result = function.asObject(rt).asFunction(rt).call(
        rt, static_cast<const jsi::Value *>(args.data()), args.size());
    return toJavaObject(env, result, true);
  } catch (const jsi::JSError& e) {
    throwJsException(env, const_cast<jsi::JSError&>(e));
    return nullptr;
  } catch (const jsi::JSIException& e) {
    // JSINativeException (thrown by number/bool reads) is a sibling of JSError; catching
    // only JSError left those aborting the process.
    throwJsExceptionFmt(env, this, "callGuestFunction failed: %s", e.what());
    return nullptr;
  } catch (const std::exception& e) {
    throwJsExceptionFmt(env, this, "callGuestFunction failed: %s", e.what());
    return nullptr;
  }
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

void ContextJni::cacheDirectBridgeMethods(JNIEnv* env) {
  // The JDK collection classes back every change that carries a payload, not just the direct
  // bridge, so cache them before the optional DirectBridge lookup below can bail out - otherwise a
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

  jclass cls = env->FindClass("app/cash/redwood/treehouse/DirectBridge");
  if (!cls) {
    // Direct bridge is an optional integration: redwood-treehouse may be absent from
    // the classpath (e.g. plain zipline consumers/tests). FindClass leaves a
    // pending NoClassDefFoundError that must be cleared, and every following
    // GetStaticMethodID on a null class would be fatal.
    env->ExceptionClear();
    this->directBridgeClass = nullptr;
    return;
  }
  this->directBridgeClass = static_cast<jclass>(env->NewGlobalRef(cls));
  if (!this->directBridgeClass) return;

  // JsonElement factories
  this->directBridgeJsonPrimitiveString = env->GetStaticMethodID(
      cls, "jsonPrimitiveString",
      "(Ljava/lang/String;)Lkotlinx/serialization/json/JsonPrimitive;");
  this->directBridgeJsonPrimitiveInt = env->GetStaticMethodID(
      cls, "jsonPrimitiveInt", "(I)Lkotlinx/serialization/json/JsonPrimitive;");
  this->directBridgeJsonPrimitiveLong = env->GetStaticMethodID(
    cls, "jsonPrimitiveLong", "(J)Lkotlinx/serialization/json/JsonPrimitive;");
  this->directBridgeJsonPrimitiveDouble = env->GetStaticMethodID(
    cls, "jsonPrimitiveDouble", "(D)Lkotlinx/serialization/json/JsonPrimitive;");
  this->directBridgeJsonPrimitiveBoolean = env->GetStaticMethodID(
    cls, "jsonPrimitiveBoolean", "(Z)Lkotlinx/serialization/json/JsonPrimitive;");
  this->directBridgeJsonNull = env->GetStaticMethodID(
    cls, "jsonNull", "()Lkotlinx/serialization/json/JsonNull;");
  this->directBridgeCreateJsonArray = env->GetStaticMethodID(
    cls, "createJsonArray",
    "(Ljava/util/List;)Lkotlinx/serialization/json/JsonArray;");
  this->directBridgeCreateJsonObject = env->GetStaticMethodID(
    cls, "createJsonObject",
    "(Ljava/util/List;Ljava/util/List;)Lkotlinx/serialization/json/JsonObject;");

  pendingChanges.reserve(DIRECT_BRIDGE_BATCH_SIZE);
}

void ContextJni::cacheDirectChangeSink(jobject sink) {
  if (!sink) return;
  JNIEnv* env = getEnv();
  if (!env) return;

    directChangeSink = env->NewGlobalRef(sink);
  if (!directChangeSink) return;

  jclass sinkCls = env->FindClass("app/cash/zipline/DirectChangeSink");
  if (!sinkCls) {
    env->ExceptionClear();
    env->DeleteGlobalRef(directChangeSink);
      directChangeSink = nullptr;
    return;
  }
    directSinkCreateCreate = env->GetMethodID(sinkCls, "createCreate", "(II)V");
    directSinkCreatePropertyChange = env->GetMethodID(
      sinkCls, "createPropertyChange",
      "(IIILkotlinx/serialization/json/JsonElement;)V");
    directSinkCreateModifierChange = env->GetMethodID(
      sinkCls, "createModifierChange", "(ILjava/util/List;)V");
    directSinkCreateAdd = env->GetMethodID(sinkCls, "createAdd", "(IIII)V");
    directSinkCreateRemove = env->GetMethodID(sinkCls, "createRemove", "(IIIZ)V");
    directSinkCreateMove = env->GetMethodID(sinkCls, "createMove", "(IIIII)V");
    directSinkCreateBridgeChange = env->GetMethodID(
      sinkCls, "createBridgeChange", "(ILjava/lang/Object;)V");
    directSinkSetRemoveDetach = env->GetMethodID(sinkCls, "setRemoveDetach", "(I)V");
    directSinkSendBatch = env->GetMethodID(sinkCls, "sendBatch", "()V");
    directSinkSendChanges = env->GetMethodID(sinkCls, "sendChanges", "()V");
  env->DeleteLocalRef(sinkCls);

  jclass pairCls = env->FindClass("kotlin/Pair");
  pairClass = static_cast<jclass>(env->NewGlobalRef(pairCls));
  pairInit = env->GetMethodID(pairCls, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V");
  env->DeleteLocalRef(pairCls);
}

jobject ContextJni::jsValueToJsonElement(JNIEnv* env, const jsi::Value& val) {
  // Every caller below crosses into Java, so a decode that begins with an exception already pending
  // would make the next JNI call illegal. Bail and let that exception propagate.
  if (env->ExceptionCheck()) return nullptr;
  if (val.isNumber()) {
    double v = val.asNumber();
    int64_t lv;
    if (tryAsInt64(v, lv)) { // Whether JS number is integral and fits in long
      if (lv >= INT32_MIN && lv <= INT32_MAX) {
        return env->CallStaticObjectMethod(
                directBridgeClass, directBridgeJsonPrimitiveInt, static_cast<jint>(lv));
      }
      return env->CallStaticObjectMethod(
              directBridgeClass, directBridgeJsonPrimitiveLong, lv);
    }
    return env->CallStaticObjectMethod(
            directBridgeClass, directBridgeJsonPrimitiveDouble, v);
  }
  if (val.isBool()) {
    jboolean v = val.asBool() ? JNI_TRUE : JNI_FALSE;
    return env->CallStaticObjectMethod(
            directBridgeClass, directBridgeJsonPrimitiveBoolean, v);
  }
  if (val.isString()) {
    std::string s = val.asString(*runtime).utf8(*runtime);
    jstring js = zipline::utf8ToJniString(env, s);
    jobject result = env->CallStaticObjectMethod(
            directBridgeClass, directBridgeJsonPrimitiveString, js);
    env->DeleteLocalRef(js);
    return result;
  }
  if (val.isNull() || val.isUndefined()) {
    return env->CallStaticObjectMethod(
            directBridgeClass, directBridgeJsonNull);
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
  if (env->ExceptionCheck()) return nullptr;
  for (size_t i = 0; i < length && !env->ExceptionCheck(); i++) {
    jsi::Value element = arr.getValueAtIndex(*runtime, i);
    jobject jsonElement = jsValueToJsonElement(env, element);
    if (jsonElement != nullptr) {
      env->CallBooleanMethod(arrayList, arrayListAdd, jsonElement);
      env->DeleteLocalRef(jsonElement);
    }
  }
  if (env->ExceptionCheck()) {
    env->DeleteLocalRef(arrayList);
    return nullptr;
  }
  jobject result = env->CallStaticObjectMethod(
          directBridgeClass, directBridgeCreateJsonArray, arrayList);
  env->DeleteLocalRef(arrayList);
  return result;
}

jobject ContextJni::jsObjectToJsonElement(JNIEnv* env, const jsi::Value& val) {
  jsi::Object obj = val.asObject(*runtime);
  jsi::Array propertyNames = obj.getPropertyNames(*runtime);
  size_t numProps = propertyNames.length(*runtime);

  jobject keysList = env->NewObject(arrayListClass, arrayListInitWithCapacity, (int)numProps);
  jobject valuesList = env->NewObject(arrayListClass, arrayListInitWithCapacity, (int)numProps);
  if (env->ExceptionCheck()) return nullptr;

  for (size_t i = 0; i < numProps && !env->ExceptionCheck(); i++) {
    jsi::Value propName = propertyNames.getValueAtIndex(*runtime, i);
    if (!propName.isString()) continue;
    std::string key = propName.asString(*runtime).utf8(*runtime);
    jsi::Value propVal = obj.getProperty(*runtime, key.c_str());
    jstring keyJava = zipline::utf8ToJniString(env, key);
    jobject jsonElement = jsValueToJsonElement(env, propVal);
    if (keyJava != nullptr) env->DeleteLocalRef(keyJava);
    if (jsonElement != nullptr) {
      env->CallBooleanMethod(keysList, arrayListAdd, keyJava);
      env->CallBooleanMethod(valuesList, arrayListAdd, jsonElement);
      env->DeleteLocalRef(jsonElement);
    }
  }

  if (env->ExceptionCheck()) {
    env->DeleteLocalRef(keysList);
    env->DeleteLocalRef(valuesList);
    return nullptr;
  }
  jobject result = env->CallStaticObjectMethod(
          directBridgeClass, directBridgeCreateJsonObject, keysList, valuesList);
  env->DeleteLocalRef(keysList);
  env->DeleteLocalRef(valuesList);
  return result;
}

void ContextJni::dispatchChangeToSink(JNIEnv* env, const DirectChange& ch) {
  switch (ch.type) {
    case DirectChangeType::Create:
      env->CallVoidMethod(directChangeSink, directSinkCreateCreate, ch.id, ch.field1);
      break;
    case DirectChangeType::PropertyChange: {
      jobject jsonElement = jsValueToJsonElement(env, *ch.jsValue);
      if (env->ExceptionCheck()) {
        reportAndKeepPendingException(env, "host bridge: dropping a property change");
        return;
      }  // see the comment at the ModifierChange case below
      env->CallVoidMethod(directChangeSink, directSinkCreatePropertyChange,
                          ch.id, ch.field1, ch.field2, jsonElement);
      if (jsonElement) env->DeleteLocalRef(jsonElement);
      break;
    }
    case DirectChangeType::ModifierChange: {
      // This is the site that killed the app: decoding a modifier element runs a host converter, a
      // host converter is Java, Java threw ZiplineApiMismatchException for a class with no converter,
      // and the pair() beneath it was then constructed on top of the pending exception. ART aborts
      // on that and prints the abort, not the cause; the cause was already named correctly.
      jobject elementsList = env->NewObject(arrayListClass, arrayListInit);
      if (env->ExceptionCheck()) return;
      if (ch.jsValue && ch.jsValue->isObject() && ch.jsValue->asObject(*runtime).isArray(*runtime)) {
        jsi::Array arr = ch.jsValue->asObject(*runtime).asArray(*runtime);
        size_t numElements = arr.length(*runtime);
        for (size_t j = 0; j < numElements && !env->ExceptionCheck(); j++) {
          jsi::Value elem = arr.getValueAtIndex(*runtime, j);
          jsi::Value modTagVal = elem.asObject(*runtime).getProperty(*runtime, "0");
          int mTag = static_cast<int>(modTagVal.asNumber());
          jsi::Value modVal = elem.asObject(*runtime).getProperty(*runtime, "1");
          jobject jModVal = modVal.isUndefined()
              ? env->CallStaticObjectMethod(directBridgeClass, directBridgeJsonNull)
              : jsValueToJsonElement(env, modVal);
          if (env->ExceptionCheck()) break;
          jobject jTag = env->CallStaticObjectMethod(integerClass, integerValueOf, (jint)mTag);
          if (env->ExceptionCheck()) break;
          jobject pair = env->NewObject(pairClass, pairInit, jTag, jModVal);
          env->CallBooleanMethod(elementsList, arrayListAdd, pair);
          if (jTag) env->DeleteLocalRef(jTag);
          if (jModVal) env->DeleteLocalRef(jModVal);
          env->DeleteLocalRef(pair);
        }
      }
      if (env->ExceptionCheck()) {
        env->DeleteLocalRef(elementsList);
        reportAndKeepPendingException(env, "host bridge: dropping a modifier change");
        return;
      }
      env->CallVoidMethod(directChangeSink, directSinkCreateModifierChange, ch.id, elementsList);
      env->DeleteLocalRef(elementsList);
      break;
    }
    case DirectChangeType::Add:
      env->CallVoidMethod(directChangeSink, directSinkCreateAdd, ch.id, ch.field1, ch.field2, ch.field3);
      break;
    case DirectChangeType::Remove:
      env->CallVoidMethod(directChangeSink, directSinkCreateRemove,
                          ch.id, ch.field1, ch.field2, ch.detach ? JNI_TRUE : JNI_FALSE);
      break;
    case DirectChangeType::Move:
      env->CallVoidMethod(directChangeSink, directSinkCreateMove, ch.id, ch.field1, ch.field2, ch.field3, ch.count);
      break;
    case DirectChangeType::BridgeChange: {
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
          // The generated converter failed. Report why and clear it: the change is dropped
          // either way, and a pending exception left behind would abort the VM at the next JNI
          // call, far from the cause (and hide it).
          reportAndClearPendingException(
              env, "host bridge: cannot decode a bridge change payload");
          return;
        }
        env->CallVoidMethod(directChangeSink, directSinkCreateBridgeChange, ch.id, uiChange);
        env->DeleteLocalRef(uiChange);
      }
      break;
    }
  }
}

void ContextJni::flushPendingBatch(JNIEnv* env, int toFlush) {
  if (directChangeSink == nullptr) {
    pendingChanges.erase(pendingChanges.begin(), pendingChanges.begin() + toFlush);
    return;
  }

  for (int i = 0; i < toFlush; i++) {
    dispatchChangeToSink(env, pendingChanges[i]);
    if (env->ExceptionCheck()) {
      // Half a batch reached the sink and the rest never will. Say so, and keep the exception: a host
      // that stopped painting looks from outside exactly like one still waiting for data.
      reportAndKeepPendingException(env, "host bridge: change batch truncated");
      break;
    }
  }
  // Only the dispatch is guarded. `sendBatch` is what makes the sink apply what it was given, so
  // skipping it on any path -- as a previous revision of these two functions did, which is how this
  // comment came to exist -- means the host receives changes and never paints them.
  if (!env->ExceptionCheck()) {
    env->CallVoidMethod(directChangeSink, directSinkSendBatch);
  }
  pendingChanges.erase(pendingChanges.begin(), pendingChanges.begin() + toFlush);
}

void ContextJni::finishFlushPending(JNIEnv* env) {
  int remaining = (int)pendingChanges.size();
  if (remaining == 0) return;

  if (directChangeSink == nullptr) {
    pendingChanges.clear();
    return;
  }

  for (int i = 0; i < remaining; i++) {
    dispatchChangeToSink(env, pendingChanges[i]);
    if (env->ExceptionCheck()) break;
  }
  if (env->ExceptionCheck()) {
    reportAndKeepPendingException(env, "host bridge: changes dropped before send");
  } else {
    env->CallVoidMethod(directChangeSink, directSinkSendChanges);
  }
  pendingChanges.clear();
}

static inline void flushIfBatchFull(ContextJni* context) {
  if ((int)context->pendingChanges.size() >= DIRECT_BRIDGE_BATCH_SIZE) {
    auto env = context->getEnv();
    if (env) context->flushPendingBatch(env, DIRECT_BRIDGE_BATCH_SIZE);
  }
}

static jsi::Value directAppendBridgeChange(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc
) {
  (void)thisVal;
  DirectChange ch;
  ch.type = DirectChangeType::BridgeChange;
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

static jsi::Value directAppendCreate(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  DirectChange ch;
  ch.type = DirectChangeType::Create;
  ch.id = static_cast<int>(args[0].asNumber());
  ch.field1 = static_cast<int>(args[1].asNumber());
  ch.jsValue = nullptr;
  context->pendingChanges.push_back(std::move(ch));
  flushIfBatchFull(context);
  return jsi::Value::undefined();
}

static jsi::Value directAppendPropertyChange(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  DirectChange ch;
  ch.type = DirectChangeType::PropertyChange;
  ch.id = static_cast<int>(args[0].asNumber());
  ch.field1 = static_cast<int>(args[1].asNumber());
  ch.field2 = static_cast<int>(args[2].asNumber());
  ch.jsValue = std::make_shared<jsi::Value>(rt, args[3]);
  context->pendingChanges.push_back(std::move(ch));
  flushIfBatchFull(context);
  return jsi::Value::undefined();
}

static jsi::Value directAppendModifierChange(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  DirectChange ch;
  ch.type = DirectChangeType::ModifierChange;
  ch.id = static_cast<int>(args[0].asNumber());
  ch.jsValue = std::make_shared<jsi::Value>(rt, args[1]);
  context->pendingChanges.push_back(std::move(ch));
  flushIfBatchFull(context);
  return jsi::Value::undefined();
}

static jsi::Value directAppendAdd(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  DirectChange ch;
  ch.type = DirectChangeType::Add;
  ch.id = static_cast<int>(args[0].asNumber());
  ch.field1 = static_cast<int>(args[1].asNumber());
  ch.field2 = static_cast<int>(args[2].asNumber());
  ch.field3 = static_cast<int>(args[3].asNumber());
  ch.jsValue = nullptr;
  context->pendingChanges.push_back(std::move(ch));
  flushIfBatchFull(context);
  return jsi::Value::undefined();
}

static jsi::Value directAppendRemove(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  DirectChange ch;
  ch.type = DirectChangeType::Remove;
  ch.id = static_cast<int>(args[0].asNumber());
  ch.field1 = static_cast<int>(args[1].asNumber());
  ch.field2 = static_cast<int>(args[2].asNumber());
  ch.detach = false;
  ch.jsValue = nullptr;
  context->pendingChanges.push_back(std::move(ch));
  flushIfBatchFull(context);
  return jsi::Value::undefined();
}

static jsi::Value directSetRemoveDetach(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  int idx = static_cast<int>(args[0].asNumber());
  // The sink tracks remove changes in its own accumulated batch (same
  // positional semantics as the Kotlin/Native implementation).
  auto env = context->getEnv();
  if (env != nullptr && context->directChangeSink != nullptr) {
    env->CallVoidMethod(context->directChangeSink, context->directSinkSetRemoveDetach, idx);
  }
  return jsi::Value::undefined();
}

static jsi::Value directAppendMove(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  DirectChange ch;
  ch.type = DirectChangeType::Move;
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

static jsi::Value directFinishChangesCallback(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  auto env = context->getEnv();
  if (!env) return jsi::Value::undefined();

  context->finishFlushPending(env);
  return jsi::Value::undefined();
}

static jsi::Value directChangesLengthCallback(
    jsi::Runtime& rt, ContextJni* context, const jsi::Value& thisVal,
    const jsi::Value* args, size_t argc) {
  int size = (int)context->pendingChanges.size();
  return jsi::Value(size);
}

void ContextJni::initDirectChangesChannel(JNIEnv* env, jobject directChangeSink) {
  if (!directChangeSink) return;
    cacheDirectChangeSink(directChangeSink);
    cacheDirectBridgeMethods(env);
  if (!this->directBridgeClass) {
    // Redwood is not on the classpath; leave the direct bridge channel uninstalled
    // rather than exposing a channel whose flush would crash.
    return;
  }

  jsi::Runtime& rt = *runtime;
  jsi::Object globalObject = rt.global();

  jsi::Object directBridgeObj = jsi::Object(rt);
  ContextJni* context = this;

  directBridgeObj.setProperty(rt, "appendBridgeChange",
                              jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "appendBridgeChange"), 2,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return directAppendBridgeChange(rt, context, thisVal, args, argc);
          }));
  directBridgeObj.setProperty(rt, "appendCreate",
                              jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "appendCreate"), 2,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return directAppendCreate(rt, context, thisVal, args, argc);
          }));
  directBridgeObj.setProperty(rt, "appendPropertyChange",
                              jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "appendPropertyChange"), 4,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return directAppendPropertyChange(rt, context, thisVal, args, argc);
          }));
  directBridgeObj.setProperty(rt, "appendModifierChange",
                              jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "appendModifierChange"), 2,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return directAppendModifierChange(rt, context, thisVal, args, argc);
          }));
  directBridgeObj.setProperty(rt, "appendAdd",
                              jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "appendAdd"), 4,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return directAppendAdd(rt, context, thisVal, args, argc);
          }));
  directBridgeObj.setProperty(rt, "appendRemove",
                              jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "appendRemove"), 3,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return directAppendRemove(rt, context, thisVal, args, argc);
          }));
  directBridgeObj.setProperty(rt, "setRemoveDetach",
                              jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "setRemoveDetach"), 1,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return directSetRemoveDetach(rt, context, thisVal, args, argc);
          }));
  directBridgeObj.setProperty(rt, "appendMove",
                              jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "appendMove"), 5,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return directAppendMove(rt, context, thisVal, args, argc);
          }));
  directBridgeObj.setProperty(rt, "finishChanges",
                              jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "finishChanges"), 0,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return directFinishChangesCallback(rt, context, thisVal, args, argc);
          }));
  directBridgeObj.setProperty(rt, "changesLength",
                              jsi::Function::createFromHostFunction(rt, jsi::PropNameID::forUtf8(rt, "changesLength"), 0,
          [context](jsi::Runtime& rt, const jsi::Value& thisVal, const jsi::Value* args, size_t argc) {
            return directChangesLengthCallback(rt, context, thisVal, args, argc);
          }));

  globalObject.setProperty(rt, "app_cash_redwood_directSendChanges", directBridgeObj);
}
