/*
 * Copyright (C) 2019 Square, Inc.
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
#include "Context.h"
#include "bridge_dispatch.h"
#ifdef __ANDROID__
#include <android/log.h>
#endif
#include <cstring>
#include <memory>
#include <assert.h>
#include "OutboundCallChannel.h"
#include "InboundCallChannel.h"
#include "ExceptionThrowers.h"
#include "common/context-no-eval.h"
#include "common/finalization-registry.h"
#include "common/global-gc.h"
#include "common/intset-builtins.h"
#include "quickjs/quickjs.h"

#include <vector>
#include <string>
#include <utility>

static std::vector<std::pair<std::string, BridgeConverterFn>> bridgeTable;
static std::vector<void(*)(JNIEnv*)> bridgeInits;

// Process-wide JNI cache, initialized once in Context::ensureStatics(). Class refs are global
// refs that live for the process lifetime; method IDs stay valid as long as the classes do.
JavaVM* Context::javaVm = nullptr;
jint Context::jniVersion = 0;
jclass Context::booleanClass = nullptr;
jclass Context::integerClass = nullptr;
jclass Context::doubleClass = nullptr;
jclass Context::longClass = nullptr;
jclass Context::objectClass = nullptr;
jclass Context::stringClass = nullptr;
jclass Context::memoryUsageClass = nullptr;
jclass Context::quickJsExceptionClass = nullptr;
jclass Context::interruptHandlerClass = nullptr;
jstring Context::stringUtf8 = nullptr;
jmethodID Context::booleanValueOf = nullptr;
jmethodID Context::integerValueOf = nullptr;
jmethodID Context::doubleValueOf = nullptr;
jmethodID Context::longValueOf = nullptr;
jmethodID Context::stringGetBytes = nullptr;
jmethodID Context::stringConstructor = nullptr;
jmethodID Context::memoryUsageConstructor = nullptr;
jmethodID Context::quickJsExceptionConstructor = nullptr;
jmethodID Context::interruptHandlerPoll = nullptr;
jclass Context::rdmaBridgeClass = nullptr;
jmethodID Context::rdmaBridgeJsonPrimitiveString = nullptr;
jmethodID Context::rdmaBridgeJsonPrimitiveInt = nullptr;
jmethodID Context::rdmaBridgeJsonPrimitiveLong = nullptr;
jmethodID Context::rdmaBridgeJsonPrimitiveDouble = nullptr;
jmethodID Context::rdmaBridgeJsonPrimitiveBoolean = nullptr;
jmethodID Context::rdmaBridgeJsonNull = nullptr;
jmethodID Context::rdmaBridgeCreateJsonArray = nullptr;
jmethodID Context::rdmaBridgeCreateJsonObject = nullptr;
jclass Context::arrayListClass = nullptr;
jmethodID Context::arrayListInit = nullptr;
jmethodID Context::arrayListInitWithCapacity = nullptr;
jmethodID Context::arrayListAdd = nullptr;
jmethodID Context::rdmaSinkCreateCreate = nullptr;
jmethodID Context::rdmaSinkCreatePropertyChange = nullptr;
jmethodID Context::rdmaSinkCreateModifierChange = nullptr;
jmethodID Context::rdmaSinkCreateAdd = nullptr;
jmethodID Context::rdmaSinkCreateRemove = nullptr;
jmethodID Context::rdmaSinkCreateMove = nullptr;
jmethodID Context::rdmaSinkCreateBridgeChange = nullptr;
jmethodID Context::rdmaSinkSetRemoveDetach = nullptr;
jmethodID Context::rdmaSinkSendBatch = nullptr;
jmethodID Context::rdmaSinkSendChanges = nullptr;
jclass Context::pairClass = nullptr;
jmethodID Context::pairInit = nullptr;
std::once_flag Context::staticsInitFlag;

extern "C" __attribute__((used, visibility("default"))) void addBridgeEntry(const char* fq, BridgeConverterFn fn) {
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

// Shared __bridgeRegister JS function — looks up FQNs in the dynamic bridgeTable.
static JSValue bridge_register_js(JSContext *ctx, JSValueConst this_val,
    int argc, JSValueConst *argv) {
    if (argc < 2) return JS_UNDEFINED;
    if (bridgeTable.empty()) return JS_UNDEFINED;
    const char *fq = JS_ToCString(ctx, argv[0]);
    if (!fq) return JS_UNDEFINED;
    JSValue ctor = argv[1];
    if (JS_IsUndefined(ctor)) { JS_FreeCString(ctx, fq); return JS_UNDEFINED; }
    for (auto& entry : bridgeTable) {
        if (strcmp(entry.first.c_str(), fq) == 0) {
            JSValue proto = JS_GetPropertyStr(ctx, ctor, "prototype");
            JS_SetPropertyStr(ctx, proto, "bridge_dispatch",
                bridgeConverterToJSValue(ctx, entry.second));
            JS_FreeValue(ctx, proto);
            JS_FreeCString(ctx, fq);
            return JS_UNDEFINED;
        }
    }
#if defined(__ANDROID__)
    __android_log_print(ANDROID_LOG_ERROR, "BRIDGE",
        "bridge_register_js: FQN '%s' not found in bridge_table", fq);
#else
    printf("BRIDGE: bridge_register_js: FQN '%s' NOT FOUND in bridge_table\n", fq);
#endif
    JS_ThrowTypeError(ctx, "bridge_register_js: FQN '%s' not found in bridge_table", fq);
    JS_FreeCString(ctx, fq);
    return JS_EXCEPTION;
}

extern "C" __attribute__((used, visibility("default"))) void register_all(JSContext* ctx) {
    JSValue global = JS_GetGlobalObject(ctx);
    JS_SetPropertyStr(ctx, global, "__bridgeRegister",
        JS_NewCFunction(ctx, bridge_register_js, "__bridgeRegister", 2));
    JS_FreeValue(ctx, global);
}

/**
 * This signature satisfies the JSInterruptHandler typedef. It is always installed but only does
 * work if a Kotlin InterruptHandler is configured.
 */
int jsInterruptHandlerPoll(JSRuntime* jsRuntime, void *opaque) {
  auto context = reinterpret_cast<Context*>(opaque);
  auto interruptHandler = context->interruptHandler;
  if (interruptHandler == nullptr) return 0;

  JS_SetInterruptHandler(context->jsRuntime, NULL, NULL); // Suppress re-enter.
  auto env = context->getEnv();
  const jboolean halt = env->CallBooleanMethod(interruptHandler, context->interruptHandlerPoll);
  JS_SetInterruptHandler(context->jsRuntime, &jsInterruptHandlerPoll, context); // Restore handler.
  // TODO: propagate the interrupt handler's exceptions through JS.
  return halt;
}

static inline Context* getContext(JSContext* ctx) {
  auto* context = reinterpret_cast<Context*>(JS_GetRuntimeOpaque(JS_GetRuntime(ctx)));
  if (!context) {
    JS_ThrowInternalError(ctx, "Runtime Context is null");
  }
  return context;
}

namespace {

void jsFinalizeOutboundCallChannel(JSRuntime* jsRuntime, JSValue val) {
  auto context = reinterpret_cast<const Context*>(JS_GetRuntimeOpaque(jsRuntime));
  if (context) {
    delete reinterpret_cast<OutboundCallChannel*>(
        JS_GetOpaque(val, context->outboundCallChannelClassId));
  }
}

struct JniThreadDetacher {
  JavaVM& javaVm;

  JniThreadDetacher(JavaVM* javaVm) : javaVm(*javaVm) {
  }

  ~JniThreadDetacher() {
    javaVm.DetachCurrentThread();
  }
};

} // anonymous namespace

Context::Context(JNIEnv* env)
    : jsRuntime(JS_NewRuntime()),
      jsContext(JS_NewContextNoEval(jsRuntime)),
      jsContextForCompiling(JS_NewContext(jsRuntime)),
      outboundCallChannelClassId(0),
      lengthAtom(JS_NewAtom(jsContext, "length")),
      callAtom(JS_NewAtom(jsContext, "call")),
      disconnectAtom(JS_NewAtom(jsContext, "disconnect")),
      interruptHandler(nullptr) {
  // Class refs and method IDs are process-wide; fetch them once for the JVM.
  ensureStatics(env);
  pendingChanges.reserve(BATCH_SIZE);
  JS_SetRuntimeOpaque(jsRuntime, this);
  JS_SetInterruptHandler(jsRuntime, &jsInterruptHandlerPoll, this);
  JS_SetStripInfo(jsRuntime, JS_STRIP_SOURCE);

  JS_AddGlobalThisGc(jsContext);

  // Register C builtins for kotlin.Long arithmetic. These back the kotlinx.collections.IntSet
  // hot path in the JS runtime. The stdlib JS bodies for `add`/`multiply`/etc. are patched at
  // build time to call into these builtins.
  js_intset_register_builtins(jsContext);

  if (installFinalizationRegistry(jsContext, jsContextForCompiling) < 0) {
    throwJavaException(env, "java/lang/IllegalStateException",
                       "Failed to install FinalizationRegistry");
  }
}

Context::~Context() {
  for (auto callChannel : callChannels) {
    delete callChannel;
  }
  auto env = getEnv();
  for (auto refs : globalReferences) {
    env->DeleteGlobalRef(refs.second);
  }
  if (interruptHandler != nullptr) {
    env->DeleteGlobalRef(interruptHandler);
  }
  deleteBridgeRefs(env);
  JS_FreeAtom(jsContext, lengthAtom);
  JS_FreeAtom(jsContext, callAtom);
  JS_FreeAtom(jsContext, disconnectAtom);
  JS_FreeContext(jsContext);
  JS_FreeContext(jsContextForCompiling);
  JS_FreeRuntime(jsRuntime);
}

jobject Context::execute(JNIEnv* env, jbyteArray byteCode) {
  const auto buffer = env->GetByteArrayElements(byteCode, nullptr);
  const auto bufferLength = env->GetArrayLength(byteCode);
  const auto flags = JS_READ_OBJ_BYTECODE | JS_READ_OBJ_REFERENCE | JS_EVAL_FLAG_STRICT;
  auto obj = JS_ReadObject(jsContext, reinterpret_cast<const uint8_t*>(buffer), bufferLength, flags);
  env->ReleaseByteArrayElements(byteCode, buffer, JNI_ABORT);

  if (JS_IsException(obj)) {
    throwJsException(env, obj);
    return nullptr;
  }

  if (JS_ResolveModule(jsContext, obj)) {
    throwJsExceptionFmt(env, this, "Failed to resolve JS module");
    return nullptr;
  }

  auto val = JS_EvalFunction(jsContext, obj);
  jobject result;
  if (!JS_IsException(val)) {
    result = toJavaObject(env, val, false);
  } else {
    result = nullptr;
    throwJsException(env, val);
  }
  JS_FreeValue(jsContext, val);

  return result;
}

jbyteArray Context::compile(JNIEnv* env, jstring source, jstring file) {
  const auto sourceCode = env->GetStringUTFChars(source, 0);
  const auto fileName = env->GetStringUTFChars(file, 0);

  auto compiled = JS_Eval(jsContextForCompiling, sourceCode, strlen(sourceCode), fileName, JS_EVAL_FLAG_COMPILE_ONLY | JS_EVAL_FLAG_STRICT);

  env->ReleaseStringUTFChars(file, fileName);
  env->ReleaseStringUTFChars(source, sourceCode);

  if (JS_IsException(compiled)) {
    // TODO: figure out how to get the failing line number into the exception.
    throwJsException(env, compiled);
    JS_FreeValue(jsContextForCompiling, compiled);
    return nullptr;
  }

  size_t bufferLength = 0;
  auto buffer = JS_WriteObject(jsContextForCompiling, &bufferLength, compiled, JS_WRITE_OBJ_BYTECODE | JS_WRITE_OBJ_REFERENCE);

  auto result = buffer && bufferLength > 0 ? env->NewByteArray(bufferLength) : nullptr;
  if (result) {
    env->SetByteArrayRegion(result, 0, bufferLength, reinterpret_cast<const jbyte*>(buffer));
  } else {
    throwJsException(env, compiled);
  }

  JS_FreeValue(jsContextForCompiling, compiled);
  js_free(jsContextForCompiling, buffer);

  return result;
}

void Context::setInterruptHandler(JNIEnv* env, jobject newInterruptHandler) {
  jobject oldInterruptHandler = interruptHandler;
  if (oldInterruptHandler != nullptr) {
    env->DeleteGlobalRef(oldInterruptHandler);
  }
  interruptHandler = env->NewGlobalRef(newInterruptHandler);
}

jobject Context::memoryUsage(JNIEnv* env) {
  if (!memoryUsageClass || !memoryUsageConstructor) {
    // Should be impossible. If R8 removed the type then this function can never be invoked.
    return nullptr;
  }

  JSMemoryUsage jsMemoryUsage;
  JS_ComputeMemoryUsage(jsRuntime, &jsMemoryUsage);

  return static_cast<jstring>(env->NewObject(
    memoryUsageClass,
    memoryUsageConstructor,
    jsMemoryUsage.malloc_count,
    jsMemoryUsage.malloc_size,
    jsMemoryUsage.malloc_limit,
    jsMemoryUsage.memory_used_count,
    jsMemoryUsage.memory_used_size,
    jsMemoryUsage.atom_count,
    jsMemoryUsage.atom_size,
    jsMemoryUsage.str_count,
    jsMemoryUsage.str_size,
    jsMemoryUsage.obj_count,
    jsMemoryUsage.obj_size,
    jsMemoryUsage.prop_count,
    jsMemoryUsage.prop_size,
    jsMemoryUsage.shape_count,
    jsMemoryUsage.shape_size,
    jsMemoryUsage.js_func_count,
    jsMemoryUsage.js_func_size,
    jsMemoryUsage.js_func_code_size,
    jsMemoryUsage.js_func_pc2line_count,
    jsMemoryUsage.js_func_pc2line_size,
    jsMemoryUsage.c_func_count,
    jsMemoryUsage.array_count,
    jsMemoryUsage.fast_array_count,
    jsMemoryUsage.fast_array_elements,
    jsMemoryUsage.binary_object_count,
    jsMemoryUsage.binary_object_size
  ));
}

void Context::setMemoryLimit(JNIEnv* env, jlong limit) {
  JS_SetMemoryLimit(jsRuntime, limit);
}

void Context::setGcThreshold(JNIEnv* env, jlong gcThreshold) {
  JS_SetGCThreshold(jsRuntime, gcThreshold);
}

void Context::setMaxStackSize(JNIEnv* env, jlong stackSize) {
  JS_SetMaxStackSize(jsRuntime, stackSize);
}

void Context::gc(JNIEnv* env) {
  JS_RunGC(jsRuntime);
}

InboundCallChannel* Context::getInboundCallChannel(JNIEnv* env, jstring name) {
  JSValue global = JS_GetGlobalObject(jsContext);

  const char* nameStr = env->GetStringUTFChars(name, 0);

  JSValue obj = JS_GetPropertyStr(jsContext, global, nameStr);

  InboundCallChannel* inboundCallChannel = nullptr;
  if (JS_IsObject(obj)) {
    inboundCallChannel = new InboundCallChannel(jsContext, nameStr);
    if (!env->ExceptionCheck()) {
      callChannels.push_back(inboundCallChannel);
    } else {
      delete inboundCallChannel;
      inboundCallChannel = nullptr;
    }
  } else if (JS_IsException(obj)) {
    throwJsException(env, obj);
  } else {
    const char* msg = JS_IsUndefined(obj)
                      ? "A global JavaScript object called %s was not found. Try confirming that Zipline.get() has been called."
                      : "JavaScript global called %s is not an object";
    throwJavaException(env, "java/lang/IllegalStateException", msg, nameStr);
  }

  JS_FreeValue(jsContext, obj);

  env->ReleaseStringUTFChars(name, nameStr);
  JS_FreeValue(jsContext, global);

  return inboundCallChannel;
}

void Context::setOutboundCallChannel(JNIEnv* env, jstring name, jobject callChannel) {
  auto global = JS_GetGlobalObject(jsContext);

  const char* nameStr = env->GetStringUTFChars(name, 0);

  const auto objName = JS_NewAtom(jsContext, nameStr);
  if (!JS_HasProperty(jsContext, global, objName)) {
    if (outboundCallChannelClassId == 0) {
      JS_NewClassID(&outboundCallChannelClassId);
      JSClassDef classDef;
      memset(&classDef, 0, sizeof(JSClassDef));
      classDef.class_name = "OutboundCallChannel";
      classDef.finalizer = jsFinalizeOutboundCallChannel;
      if (JS_NewClass(jsRuntime, outboundCallChannelClassId, &classDef) < 0) {
        outboundCallChannelClassId = 0;
        throwJavaException(env, "java/lang/NullPointerException",
                           "Failed to allocate JavaScript OutboundCallChannel class");
      }
    }
    if (outboundCallChannelClassId != 0) {
      auto jsOutboundCallChannel = JS_NewObjectClass(jsContext, outboundCallChannelClassId);
      if (JS_IsException(jsOutboundCallChannel) || JS_SetProperty(jsContext, global, objName, jsOutboundCallChannel) <= 0) {
        throwJsException(env, jsOutboundCallChannel);
      } else {
        std::unique_ptr<OutboundCallChannel> javaObject(new OutboundCallChannel(this, env, nameStr, callChannel, jsOutboundCallChannel));
        if (!env->ExceptionCheck()) {
          JS_SetOpaque(jsOutboundCallChannel, javaObject.release());
        }
      }
    }
  } else {
    throwJavaException(env, "java/lang/IllegalArgumentException",
                       "A global object called %s already exists", nameStr);
  }
  JS_FreeAtom(jsContext, objName);
  env->ReleaseStringUTFChars(name, nameStr);
  JS_FreeValue(jsContext, global);
}


__attribute__((used, visibility("default"))) jobject bridgeTryUnwrapLong(JNIEnv *env, JSContext *ctx, JSValue val) {
  JSValue lo = JS_GetPropertyStr(ctx, val, "low_1");
  if (JS_IsUndefined(lo)) return nullptr;

  JSValue ctor = JS_GetPropertyStr(ctx, val, "constructor");
  int isLong = 0;
  if (!JS_IsUndefined(ctor)) {
    JSValue ctorName = JS_GetPropertyStr(ctx, ctor, "name");
    const char* ctorNameStr = JS_ToCString(ctx, ctorName);
    isLong = (ctorNameStr != nullptr && strcmp(ctorNameStr, "Long") == 0);
    JS_FreeCString(ctx, ctorNameStr);
    JS_FreeValue(ctx, ctorName);
  }
  JS_FreeValue(ctx, ctor);

  if (!isLong) {
    JS_FreeValue(ctx, lo);
    return nullptr;
  }

  JSValue hi = JS_GetPropertyStr(ctx, val, "high_1");
  jint loVal = JS_VALUE_GET_INT(lo);
  jint hiVal = JS_VALUE_GET_INT(hi);
  jlong lv = ((jlong)hiVal << 32) | ((jlong)loVal & 0xFFFFFFFF);
  JS_FreeValue(ctx, hi);
  JS_FreeValue(ctx, lo);

  auto* context = reinterpret_cast<Context*>(JS_GetRuntimeOpaque(JS_GetRuntime(ctx)));
  jvalue v;
  v.j = lv;
  return env->CallStaticObjectMethodA(context->longClass, context->longValueOf, &v);
}

__attribute__((used, visibility("default"))) jobject bridgeForAny(JNIEnv *env, JSContext *ctx, JSValue val) {
  int tag = JS_VALUE_GET_NORM_TAG(val);
  auto* context = reinterpret_cast<Context*>(JS_GetRuntimeOpaque(JS_GetRuntime(ctx)));

  switch (tag) {
    case JS_TAG_INT: {
      jvalue v;
      v.j = static_cast<jint>(JS_VALUE_GET_INT(val));
      return env->CallStaticObjectMethodA(context->integerClass, context->integerValueOf, &v);
    }
    case JS_TAG_FLOAT64: {
      jvalue v;
      v.d = static_cast<jdouble>(JS_VALUE_GET_FLOAT64(val));
      return env->CallStaticObjectMethodA(context->doubleClass, context->doubleValueOf, &v);
    }
    case JS_TAG_BOOL: {
      jvalue v;
      v.z = static_cast<jboolean>(JS_VALUE_GET_BOOL(val));
      return env->CallStaticObjectMethodA(context->booleanClass, context->booleanValueOf, &v);
    }
    case JS_TAG_STRING:
      return context->toJavaString(env, val);

    case JS_TAG_NULL:
    case JS_TAG_UNDEFINED:
      return nullptr;

    case JS_TAG_OBJECT: {
      jobject result = nullptr;
      // 1) Try bridge_dispatch
      JSValue disp = JS_GetPropertyStr(ctx, val, "bridge_dispatch");
      BridgeConverterFn d = bridgeConverterFromJSValue(disp);
      JS_FreeValue(ctx, disp);
      if (d != nullptr) {
        result = d(env, ctx, val);
        if (result) return result;
      }
      // 2) Try Kotlin/JS Long
      result = bridgeTryUnwrapLong(env, ctx, val);
      if (result) return result;
      return nullptr;
    }

    default:
      return nullptr;
  }
}

jobject
Context::toJavaObject(JNIEnv* env, const JSValueConst& value, bool throwOnUnsupportedType) {
  jobject result;
  switch (JS_VALUE_GET_NORM_TAG(value)) {
    case JS_TAG_EXCEPTION: {
      throwJsException(env, value);
      result = nullptr;
      break;
    }

    case JS_TAG_STRING: {
      result = toJavaString(env, value);
      break;
    }

    case JS_TAG_BOOL: {
      jvalue v;
      v.z = static_cast<jboolean>(JS_VALUE_GET_BOOL(value));
      result = env->CallStaticObjectMethodA(booleanClass, booleanValueOf, &v);
      break;
    }

    case JS_TAG_INT: {
      jvalue v;
      v.j = static_cast<jint>(JS_VALUE_GET_INT(value));
      result = env->CallStaticObjectMethodA(integerClass, integerValueOf, &v);
      break;
    }

    case JS_TAG_FLOAT64: {
      jvalue v;
      v.d = static_cast<jdouble>(JS_VALUE_GET_FLOAT64(value));
      result = env->CallStaticObjectMethodA(doubleClass, doubleValueOf, &v);
      break;
    }

    case JS_TAG_NULL:
    case JS_TAG_UNDEFINED:
      result = nullptr;
      break;

    case JS_TAG_OBJECT:
      if (JS_IsArray(jsContext, value)) {
        auto arrayLengthProperty = JS_GetPropertyStr(jsContext, value, "length");
        const auto arrayLength = JS_VALUE_GET_INT(arrayLengthProperty);
        JS_FreeValue(jsContext, arrayLengthProperty);

        result = env->NewObjectArray(arrayLength, objectClass, nullptr);
        for (int i = 0; i < arrayLength && !env->ExceptionCheck(); i++) {
          auto element = JS_GetPropertyUint32(jsContext, value, i);
          auto javaElement = toJavaObject(env, element);
          if (!env->ExceptionCheck()) {
            env->SetObjectArrayElement(static_cast<jobjectArray>(result), i, javaElement);
          }
          JS_FreeValue(jsContext, element);
        }
        break;
      }
      // Try bridge_dispatch
      {
        JSValue disp = JS_GetPropertyStr(jsContext, value, "bridge_dispatch");
        BridgeConverterFn d = bridgeConverterFromJSValue(disp);
        if (d != nullptr) {
          result = d(env, jsContext, value);
        }
        JS_FreeValue(jsContext, disp);
        if (result) return result;
      }
      // Try Kotlin/JS Long
      {
        result = bridgeTryUnwrapLong(env, jsContext, value);
        if (result) return result;
      }
      // Fall through.
    default:
      if (throwOnUnsupportedType) {
        auto str = JS_ToCString(jsContext, value);
        throwJsExceptionFmt(env, this, "Cannot marshal value %s to Java", str);
        JS_FreeCString(jsContext, str);
      }
      result = nullptr;
      break;
  }
  return result;
}

void Context::throwJsException(JNIEnv* env, const JSValue& value) const {
  JSValue exceptionValue = JS_GetException(jsContext);

  JSValue messageValue = JS_GetPropertyStr(jsContext, exceptionValue, "message");
  JSValue stackValue = JS_GetPropertyStr(jsContext, exceptionValue, "stack");

  // If the JS does a `throw 2;`, there won't be a message property.
  jstring message = toJavaString(env,
                                 JS_IsUndefined(messageValue) ? exceptionValue : messageValue);
  JS_FreeValue(jsContext, messageValue);

  jstring stack = toJavaString(env, stackValue);
  JS_FreeValue(jsContext, stackValue);
  JS_FreeValue(jsContext, exceptionValue);

  jthrowable cause = static_cast<jthrowable>(JS_GetContextOpaque(jsContext));
  JS_SetContextOpaque(jsContext, nullptr);

  jobject exception;
  if (cause) {
    exception = env->NewLocalRef(cause);
    env->DeleteGlobalRef(cause);

    // add the JavaScript stack to this exception.
    const jmethodID addJavaScriptStack =
        env->GetStaticMethodID(quickJsExceptionClass,
                               "addJavaScriptStack",
                               "(Ljava/lang/Throwable;Ljava/lang/String;)V");
    env->CallStaticVoidMethod(quickJsExceptionClass, addJavaScriptStack, exception,
                              stack);
  } else {
    exception = env->NewObject(quickJsExceptionClass,
                               quickJsExceptionConstructor,
                               message,
                               stack);
  }

  env->DeleteLocalRef(stack);
  env->DeleteLocalRef(message);

  env->Throw(static_cast<jthrowable>(exception));
}

JSValue Context::throwJavaExceptionFromJs(JNIEnv* env) const {
  assert(env->ExceptionCheck()); // There must be something to throw.
  assert(JS_GetContextOpaque(jsContext) == nullptr); // There can't be a pending thrown exception.
  auto exception = env->ExceptionOccurred();
  env->ExceptionClear();
  JS_SetContextOpaque(jsContext, env->NewGlobalRef(exception));
  return JS_ThrowInternalError(jsContext, "Java Exception");
}

JNIEnv* Context::getEnv() const {
  JNIEnv* env = nullptr;
  javaVm->GetEnv(reinterpret_cast<void**>(&env), jniVersion);
  if (env) {
    return env;
  }

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

/*
 * Converts `string` to UTF-8. Prefer this over `GetStringUTFChars()` for any string that might
 * contain non-ASCII characters because that function returns modified UTF-8.
 */
std::string Context::toCppString(JNIEnv* env, jstring string) const {
  const jbyteArray utf8BytesObject = static_cast<jbyteArray>(env->CallObjectMethod(string, stringGetBytes, stringUtf8));
  size_t utf8Length = env->GetArrayLength(utf8BytesObject);
  jbyte* utf8Bytes = env->GetByteArrayElements(utf8BytesObject, NULL);
  std::string result = std::string(reinterpret_cast<char*>(utf8Bytes), utf8Length);
  env->ReleaseByteArrayElements(utf8BytesObject, utf8Bytes, JNI_ABORT);
  env->DeleteLocalRef(utf8BytesObject);
  return result;
}

JSValue Context::toJsString(JNIEnv* env, jstring javaString) const {
  std::string cppString = this->toCppString(env, javaString);
  return JS_NewString(this->jsContext, cppString.c_str());
}

/*
 * Converts `value` to a Java string. Prefer this over `NewStringUTF()` for any string that might
 * contain non-ASCII characters because that function expects modified UTF-8.
 */
jstring Context::toJavaString(JNIEnv* env, const JSValueConst& value) const {
  size_t utf8Length;
  const char* string = JS_ToCStringLen(jsContext, &utf8Length, value);
  jbyteArray utf8BytesObject = env->NewByteArray(utf8Length);
  jbyte* utf8Bytes = env->GetByteArrayElements(utf8BytesObject, NULL);
  std::copy(string, string + utf8Length, utf8Bytes);
  env->ReleaseByteArrayElements(utf8BytesObject, utf8Bytes, JNI_COMMIT);
  JS_FreeCString(jsContext, string);
  jstring result = static_cast<jstring>(env->NewObject(stringClass, stringConstructor, utf8BytesObject, stringUtf8));
  env->DeleteLocalRef(utf8BytesObject);
  return result;
}

void Context::ensureStatics(JNIEnv* env) {
  std::call_once(staticsInitFlag, [&] {
    env->GetJavaVM(&javaVm);
    jniVersion = env->GetVersion();

    booleanClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/Boolean")));
    integerClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/Integer")));
    doubleClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/Double")));
    longClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/Long")));
    objectClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/Object")));
    stringClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass("java/lang/String")));
    stringUtf8 = static_cast<jstring>(env->NewGlobalRef(env->NewStringUTF("UTF-8")));
    quickJsExceptionClass = static_cast<jclass>(env->NewGlobalRef(
        env->FindClass("app/cash/zipline/QuickJsException")));
    booleanValueOf = env->GetStaticMethodID(booleanClass, "valueOf", "(Z)Ljava/lang/Boolean;");
    integerValueOf = env->GetStaticMethodID(integerClass, "valueOf", "(I)Ljava/lang/Integer;");
    doubleValueOf = env->GetStaticMethodID(doubleClass, "valueOf", "(D)Ljava/lang/Double;");
    stringGetBytes = env->GetMethodID(stringClass, "getBytes", "(Ljava/lang/String;)[B");
    longValueOf = env->GetStaticMethodID(longClass, "valueOf", "(J)Ljava/lang/Long;");
    stringConstructor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
    quickJsExceptionConstructor = env->GetMethodID(quickJsExceptionClass, "<init>",
                                                   "(Ljava/lang/String;Ljava/lang/String;)V");
    interruptHandlerClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass("app/cash/zipline/InterruptHandler")));
    interruptHandlerPoll = env->GetMethodID(interruptHandlerClass, "poll", "()Z");

    memoryUsageClass = static_cast<jclass>(env->NewGlobalRef(env->FindClass("app/cash/zipline/MemoryUsage")));
    if (memoryUsageClass) {
      memoryUsageConstructor = env->GetMethodID(
          memoryUsageClass, "<init>", "(JJJJJJJJJJJJJJJJJJJJJJJJJJ)V");
    } else {
      // MemoryUsage absent (e.g. R8-stripped): leave the cache null; memoryUsage() returns
      // nullptr. The failed FindClass left a pending exception — clear it so that session
      // creation still succeeds.
      env->ExceptionClear();
    }

    // RdmaBridge static JsonElement factories. A missing class means RDMA is unused: leave the
    // cache null and skip the factory lookups, matching the old per-session failure behavior.
    jclass bridgeCls = env->FindClass("app/cash/redwood/treehouse/RdmaBridge");
    rdmaBridgeClass = static_cast<jclass>(env->NewGlobalRef(bridgeCls));
    if (rdmaBridgeClass) {
      rdmaBridgeJsonPrimitiveString = env->GetStaticMethodID(
          bridgeCls, "jsonPrimitiveString",
          "(Ljava/lang/String;)Lkotlinx/serialization/json/JsonPrimitive;");
      rdmaBridgeJsonPrimitiveInt = env->GetStaticMethodID(
          bridgeCls, "jsonPrimitiveInt", "(I)Lkotlinx/serialization/json/JsonPrimitive;");
      rdmaBridgeJsonPrimitiveLong = env->GetStaticMethodID(
          bridgeCls, "jsonPrimitiveLong", "(J)Lkotlinx/serialization/json/JsonPrimitive;");
      rdmaBridgeJsonPrimitiveDouble = env->GetStaticMethodID(
          bridgeCls, "jsonPrimitiveDouble", "(D)Lkotlinx/serialization/json/JsonPrimitive;");
      rdmaBridgeJsonPrimitiveBoolean = env->GetStaticMethodID(
          bridgeCls, "jsonPrimitiveBoolean", "(Z)Lkotlinx/serialization/json/JsonPrimitive;");
      rdmaBridgeJsonNull = env->GetStaticMethodID(
          bridgeCls, "jsonNull", "()Lkotlinx/serialization/json/JsonNull;");
      rdmaBridgeCreateJsonArray = env->GetStaticMethodID(
          bridgeCls, "createJsonArray",
          "(Ljava/util/List;)Lkotlinx/serialization/json/JsonArray;");
      rdmaBridgeCreateJsonObject = env->GetStaticMethodID(
          bridgeCls, "createJsonObject",
          "(Ljava/util/List;Ljava/util/List;)Lkotlinx/serialization/json/JsonObject;");

      // ArrayList
      jclass alCls = env->FindClass("java/util/ArrayList");
      arrayListClass = static_cast<jclass>(env->NewGlobalRef(alCls));
      arrayListInit = env->GetMethodID(alCls, "<init>", "()V");
      arrayListInitWithCapacity = env->GetMethodID(alCls, "<init>", "(I)V");
      arrayListAdd = env->GetMethodID(alCls, "add", "(Ljava/lang/Object;)Z");
    } else {
      // RdmaBridge absent (RDMA unused, e.g. a JVM host without the redwood dependency): the
      // factory cache stays null. The failed FindClass left a pending exception — clear it so
      // that session creation proceeds normally.
      env->ExceptionClear();
    }

    // RdmaChangeSink interface method IDs
    jclass sinkCls = env->FindClass("app/cash/zipline/RdmaChangeSink");
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

    // kotlin.Pair construction for modifier elements
    jclass pairCls = env->FindClass("kotlin/Pair");
    pairClass = static_cast<jclass>(env->NewGlobalRef(pairCls));
    pairInit = env->GetMethodID(pairCls, "<init>", "(Ljava/lang/Object;Ljava/lang/Object;)V");
    env->DeleteLocalRef(pairCls);
  });
}

void Context::cacheRdmaSink(jobject sink) {
  if (!sink) return;
  // All change delivery goes through the per-QuickJs RdmaChangeSink instance.
  rdmaChangeSink = getEnv()->NewGlobalRef(sink);
}

void Context::deleteBridgeRefs(JNIEnv* env) {
  if (rdmaChangeSink != nullptr) {
    env->DeleteGlobalRef(rdmaChangeSink);
  }
}

static int readIntProp(JSContext* ctx, JSValueConst obj, const char* name) {
  JSValue prop = JS_GetPropertyStr(ctx, obj, name);
  int result = JS_VALUE_GET_INT(prop);
  JS_FreeValue(ctx, prop);
  return result;
}

jobject Context::jsValueToJsonElement(JNIEnv* env, JSValueConst val) {
  switch (JS_VALUE_GET_NORM_TAG(val)) {
    case JS_TAG_INT: {
      jint v = JS_VALUE_GET_INT(val);
      return env->CallStaticObjectMethod(
          rdmaBridgeClass, rdmaBridgeJsonPrimitiveInt, v);
    }
    case JS_TAG_FLOAT64: {
      jdouble v = JS_VALUE_GET_FLOAT64(val);
      jlong lv = (jlong)v;

      if (v == (jdouble)lv) { // Whether JS number is long
          return env->CallStaticObjectMethod(
              rdmaBridgeClass, rdmaBridgeJsonPrimitiveLong, lv);
      }
      return env->CallStaticObjectMethod(
        rdmaBridgeClass, rdmaBridgeJsonPrimitiveDouble, v);
    }
    case JS_TAG_BOOL: {
      jboolean v = JS_VALUE_GET_BOOL(val) ? JNI_TRUE : JNI_FALSE;
      return env->CallStaticObjectMethod(
          rdmaBridgeClass, rdmaBridgeJsonPrimitiveBoolean, v);
    }
    case JS_TAG_STRING: {
      jstring s = toJavaString(env, val);
      jobject result = env->CallStaticObjectMethod(
          rdmaBridgeClass, rdmaBridgeJsonPrimitiveString, s);
      env->DeleteLocalRef(s);
      return result;
    }
    case JS_TAG_NULL:
    case JS_TAG_UNDEFINED:
      return env->CallStaticObjectMethod(
          rdmaBridgeClass, rdmaBridgeJsonNull);
    case JS_TAG_OBJECT:
      if (JS_IsArray(jsContext, val)) {
        return jsArrayToJsonElement(env, val);
      } else {
        return jsObjectToJsonElement(env, val);
      }
    default:
      return nullptr;
  }
}

jobject Context::jsArrayToJsonElement(JNIEnv* env, JSValueConst val) {
  uint32_t length = readIntProp(jsContext, val, "length");

  jobject arrayList = env->NewObject(arrayListClass, arrayListInitWithCapacity, (int)length);
  for (uint32_t i = 0; i < length; i++) {
    JSValue element = JS_GetPropertyUint32(jsContext, val, i);
    jobject jsonElement = jsValueToJsonElement(env, element);
    if (jsonElement != nullptr) {
      env->CallBooleanMethod(arrayList, arrayListAdd, jsonElement);
      env->DeleteLocalRef(jsonElement);
    }
    JS_FreeValue(jsContext, element);
  }
  jobject result = env->CallStaticObjectMethod(
      rdmaBridgeClass, rdmaBridgeCreateJsonArray, arrayList);
  env->DeleteLocalRef(arrayList);
  return result;
}

jobject Context::jsObjectToJsonElement(JNIEnv* env, JSValueConst val) {
  JSPropertyEnum* ptab;
  uint32_t plen;
  if (JS_GetOwnPropertyNames(jsContext, &ptab, &plen, val,
                             JS_GPN_STRING_MASK) != 0) {
    return nullptr;
  }

  jobject keysList = env->NewObject(arrayListClass, arrayListInitWithCapacity, (int)plen);
  jobject valuesList = env->NewObject(arrayListClass, arrayListInitWithCapacity, (int)plen);

  for (uint32_t i = 0; i < plen; i++) {
    const char* keyCStr = JS_AtomToCString(jsContext, ptab[i].atom);
    if (!keyCStr) continue;
    JSValue propVal = JS_GetProperty(jsContext, val, ptab[i].atom);
    jstring keyJava = env->NewStringUTF(keyCStr);
    jobject jsonElement = jsValueToJsonElement(env, propVal);
    if (jsonElement != nullptr) {
      env->CallBooleanMethod(keysList, arrayListAdd, keyJava);
      env->CallBooleanMethod(valuesList, arrayListAdd, jsonElement);
      env->DeleteLocalRef(jsonElement);
    }
    env->DeleteLocalRef(keyJava);
    JS_FreeValue(jsContext, propVal);
    JS_FreeCString(jsContext, keyCStr);
    JS_FreeAtom(jsContext, ptab[i].atom);
  }
  js_free(jsContext, ptab);

  jobject result = env->CallStaticObjectMethod(
      rdmaBridgeClass, rdmaBridgeCreateJsonObject, keysList, valuesList);
  env->DeleteLocalRef(keysList);
  env->DeleteLocalRef(valuesList);
  return result;
}

void Context::dispatchChangeToSink(JNIEnv* env, const RdmaChange& ch) {
  switch (ch.type) {
    case RdmaChangeType::Create:
      env->CallVoidMethod(rdmaChangeSink, rdmaSinkCreateCreate, ch.id, ch.field1);
      break;
    case RdmaChangeType::PropertyChange: {
      jobject jsonElement = jsValueToJsonElement(env, ch.jsValue);
      env->CallVoidMethod(rdmaChangeSink, rdmaSinkCreatePropertyChange,
          ch.id, ch.field1, ch.field2, jsonElement);
      if (jsonElement) env->DeleteLocalRef(jsonElement);
      JS_FreeValue(jsContext, ch.jsValue);
      break;
    }
    case RdmaChangeType::ModifierChange: {
      jobject elementsList = env->NewObject(arrayListClass, arrayListInit);
      if (JS_IsArray(jsContext, ch.jsValue)) {
        uint32_t numElements = readIntProp(jsContext, ch.jsValue, "length");
        for (uint32_t j = 0; j < numElements; j++) {
          JSValue elem = JS_GetPropertyUint32(jsContext, ch.jsValue, j);
          JSValue modTagVal = JS_GetPropertyUint32(jsContext, elem, 0);
          int mTag = JS_VALUE_GET_INT(modTagVal);
          JS_FreeValue(jsContext, modTagVal);
          JSValue modVal = JS_GetPropertyUint32(jsContext, elem, 1);
          jobject jModVal = JS_IsUndefined(modVal)
              ? env->CallStaticObjectMethod(rdmaBridgeClass, rdmaBridgeJsonNull)
              : jsValueToJsonElement(env, modVal);
          jobject jTag = env->CallStaticObjectMethod(integerClass, integerValueOf, (jint)mTag);
          jobject pair = env->NewObject(pairClass, pairInit, jTag, jModVal);
          env->CallBooleanMethod(elementsList, arrayListAdd, pair);
          if (jTag) env->DeleteLocalRef(jTag);
          if (jModVal) env->DeleteLocalRef(jModVal);
          env->DeleteLocalRef(pair);
          JS_FreeValue(jsContext, modVal);
          JS_FreeValue(jsContext, elem);
        }
      }
      env->CallVoidMethod(rdmaChangeSink, rdmaSinkCreateModifierChange, ch.id, elementsList);
      env->DeleteLocalRef(elementsList);
      JS_FreeValue(jsContext, ch.jsValue);
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
      env->CallVoidMethod(rdmaChangeSink, rdmaSinkCreateMove,
          ch.id, ch.field1, ch.field2, ch.field3, ch.count);
      break;
    case RdmaChangeType::BridgeChange: {
      JSValue dispVal = JS_GetPropertyStr(jsContext, ch.jsValue, "bridge_dispatch");
      BridgeConverterFn disp = bridgeConverterFromJSValue(dispVal);
      if (disp != nullptr) {
        jobject uiChange = disp(env, jsContext, ch.jsValue);
        if (!uiChange) {
#ifdef __ANDROID__
          __android_log_print(ANDROID_LOG_ERROR, "BRIDGE",
              "dispatchChangeToSink: bridgeChange toJavaObject returned NULL");
#endif
          JS_FreeValue(jsContext, dispVal);
          JS_FreeValue(jsContext, ch.jsValue);
          break;
        }
        env->CallVoidMethod(rdmaChangeSink, rdmaSinkCreateBridgeChange, ch.id, uiChange);
        env->DeleteLocalRef(uiChange);
      } else {
        // The JS object has no bridge_dispatch: this is a host/guest bridge mismatch that
        // cannot be recovered from. Log the constructor name and abort.
        JS_FreeValue(jsContext, dispVal);
        const char *dbgName = "(unknown)";
        JSValue dbgCtor = JS_GetPropertyStr(jsContext, ch.jsValue, "constructor");
        if (!JS_IsUndefined(dbgCtor) && !JS_IsNull(dbgCtor)) {
          JSValue dbgCtorName = JS_GetPropertyStr(jsContext, dbgCtor, "name");
          dbgName = JS_ToCString(jsContext, dbgCtorName);
          JS_FreeValue(jsContext, dbgCtorName);
        }
#if defined(__ANDROID__)
        __android_log_assert("FATAL", "BRIDGE",
            "bridge_dispatch NOT found for ctor='%s' — nothing useful can be done",
            dbgName ? dbgName : "(null)");
#else
        fprintf(stderr, "BRIDGE: bridge_dispatch NOT found for ctor='%s' — nothing useful can be done\n",
            dbgName ? dbgName : "(null)");
        abort();
#endif
        if (dbgName && dbgName != "(unknown)") JS_FreeCString(jsContext, dbgName);
        JS_FreeValue(jsContext, dbgCtor);
      }
      JS_FreeValue(jsContext, ch.jsValue);
      break;
    }
  }
}

void Context::flushPendingBatch(JNIEnv* env, int toFlush) {
  if (rdmaChangeSink == nullptr) {
    for (int i = 0; i < toFlush; i++) {
      if (JS_VALUE_GET_NORM_TAG(pendingChanges[i].jsValue) != JS_TAG_NULL) {
        JS_FreeValue(jsContext, pendingChanges[i].jsValue);
      }
    }
    pendingChanges.erase(pendingChanges.begin(), pendingChanges.begin() + toFlush);
    return;
  }
  for (int i = 0; i < toFlush; i++) {
    dispatchChangeToSink(env, pendingChanges[i]);
  }
  env->CallVoidMethod(rdmaChangeSink, rdmaSinkSendBatch);
  pendingChanges.erase(pendingChanges.begin(), pendingChanges.begin() + toFlush);
}

void Context::finishFlushPending(JNIEnv* env) {
  int remaining = (int)pendingChanges.size();
  if (remaining == 0) return;
  if (rdmaChangeSink == nullptr) {
    for (int i = 0; i < remaining; i++) {
      if (JS_VALUE_GET_NORM_TAG(pendingChanges[i].jsValue) != JS_TAG_NULL) {
        JS_FreeValue(jsContext, pendingChanges[i].jsValue);
      }
    }
    pendingChanges.clear();
    return;
  }
  for (int i = 0; i < remaining; i++) {
    dispatchChangeToSink(env, pendingChanges[i]);
  }
  env->CallVoidMethod(rdmaChangeSink, rdmaSinkSendChanges);
  pendingChanges.clear();
}

static inline void flushIfBatchFull(Context* context) {
  if ((int)context->pendingChanges.size() >= BATCH_SIZE) {
    auto env = context->getEnv();
    if (env) context->flushPendingBatch(env, BATCH_SIZE);
  }
}

static JSValue rdmaAppendBridgeChange(
    JSContext* ctx, JSValueConst thisVal,
    int argc, JSValueConst* argv
) {
  Context* context = reinterpret_cast<Context*>(JS_GetRuntimeOpaque(JS_GetRuntime(ctx)));
  if (!context) return JS_UNDEFINED;

  RdmaChange ch;
  ch.type = RdmaChangeType::BridgeChange;
  ch.id = JS_VALUE_GET_INT(argv[0]);
  ch.jsValue = JS_DupValue(ctx, argv[1]); // keep JS object alive until flush
#ifdef __ANDROID__
  //__android_log_print(ANDROID_LOG_INFO, "BRIDGE", "rdmaAppendBridgeChange id=%d", ch.id);
#endif
  ch.field1 = 0;
  ch.field2 = 0;
  ch.field3 = 0;
  ch.count = 0;
  ch.detach = false;
  context->pendingChanges.push_back(ch);
  flushIfBatchFull(context);
  return JS_UNDEFINED;
}

static JSValue rdmaAppendCreate(
    JSContext* ctx, JSValueConst thisVal,
    int argc, JSValueConst* argv
) {
  auto* context = getContext(ctx);

  RdmaChange ch;
  ch.type = RdmaChangeType::Create;
  ch.id = JS_VALUE_GET_INT(argv[0]);
  ch.field1 = JS_VALUE_GET_INT(argv[1]);
  ch.jsValue = JS_NULL;
  context->pendingChanges.push_back(ch);
  flushIfBatchFull(context);
  return JS_UNDEFINED;
}

static JSValue rdmaAppendPropertyChange(
    JSContext* ctx, JSValueConst thisVal,
    int argc, JSValueConst* argv
) {
  auto* context = getContext(ctx);

  RdmaChange ch;
  ch.type = RdmaChangeType::PropertyChange;
  ch.id = JS_VALUE_GET_INT(argv[0]);
  ch.field1 = JS_VALUE_GET_INT(argv[1]);
  ch.field2 = JS_VALUE_GET_INT(argv[2]);
  ch.jsValue = JS_DupValue(ctx, argv[3]);

  context->pendingChanges.push_back(ch);
  return JS_UNDEFINED;
}

static JSValue rdmaAppendModifierChange(
    JSContext* ctx, JSValueConst thisVal,
    int argc, JSValueConst* argv
) {
  auto* context = getContext(ctx);

  RdmaChange ch;
  ch.type = RdmaChangeType::ModifierChange;
  ch.id = JS_VALUE_GET_INT(argv[0]);
  ch.jsValue = JS_DupValue(ctx, argv[1]);
  context->pendingChanges.push_back(ch);
  flushIfBatchFull(context);
  return JS_UNDEFINED;
}

static JSValue rdmaAppendAdd(
    JSContext* ctx, JSValueConst thisVal,
    int argc, JSValueConst* argv
) {
  auto* context = getContext(ctx);

  RdmaChange ch;
  ch.type = RdmaChangeType::Add;
  ch.id = JS_VALUE_GET_INT(argv[0]);
  ch.field1 = JS_VALUE_GET_INT(argv[1]);
  ch.field2 = JS_VALUE_GET_INT(argv[2]);
  ch.field3 = JS_VALUE_GET_INT(argv[3]);
  ch.jsValue = JS_NULL;
  context->pendingChanges.push_back(ch);
  flushIfBatchFull(context);
  return JS_UNDEFINED;
}

static JSValue rdmaAppendRemove(
    JSContext* ctx, JSValueConst thisVal,
    int argc, JSValueConst* argv
) {
  auto* context = getContext(ctx);

  RdmaChange ch;
  ch.type = RdmaChangeType::Remove;
  ch.id = JS_VALUE_GET_INT(argv[0]);
  ch.field1 = JS_VALUE_GET_INT(argv[1]);
  ch.field2 = JS_VALUE_GET_INT(argv[2]);
  ch.detach = false;
  ch.jsValue = JS_NULL;
  context->pendingChanges.push_back(ch);
  flushIfBatchFull(context);
  return JS_UNDEFINED;
}

static JSValue rdmaSetRemoveDetach(
    JSContext* ctx, JSValueConst thisVal,
    int argc, JSValueConst* argv
) {
  auto* context = getContext(ctx);

  // The sink tracks remove changes in its own accumulated batch (same positional semantics
  // as the Kotlin/Native implementation).
  int idx = JS_VALUE_GET_INT(argv[0]);
  auto env = context->getEnv();
  if (env != nullptr && context->rdmaChangeSink != nullptr) {
    env->CallVoidMethod(context->rdmaChangeSink, context->rdmaSinkSetRemoveDetach, idx);
  }
  return JS_UNDEFINED;
}

static JSValue rdmaAppendMove(
    JSContext* ctx, JSValueConst thisVal,
    int argc, JSValueConst* argv
) {
  auto* context = getContext(ctx);

  RdmaChange ch;
  ch.type = RdmaChangeType::Move;
  ch.id = JS_VALUE_GET_INT(argv[0]);
  ch.field1 = JS_VALUE_GET_INT(argv[1]);
  ch.field2 = JS_VALUE_GET_INT(argv[2]);
  ch.field3 = JS_VALUE_GET_INT(argv[3]);
  ch.count = JS_VALUE_GET_INT(argv[4]);
  ch.jsValue = JS_NULL;
  context->pendingChanges.push_back(ch);
  flushIfBatchFull(context);
  return JS_UNDEFINED;
}

static JSValue rdmaFinishChangesCallback(
    JSContext* ctx, JSValueConst thisVal,
    int argc, JSValueConst* argv) {
  auto* context = getContext(ctx);

  auto env = context->getEnv();
  if (!env) return JS_UNDEFINED;

  context->finishFlushPending(env);
  return JS_UNDEFINED;
}

static JSValue rdmaChangesLengthCallback(
    JSContext* ctx, JSValueConst thisVal,
    int argc, JSValueConst* argv) {
  auto* context = getContext(ctx);

  int size = (int)context->pendingChanges.size();
  return JS_MKVAL(JS_TAG_INT, size);
}

void Context::initRdmaChangesChannel(JNIEnv* env, jobject rdmaChangeSink) {
  if (!rdmaChangeSink) return;
  cacheRdmaSink(rdmaChangeSink);

  JSValue global = JS_GetGlobalObject(jsContext);

  JSValue rdmaObj = JS_NewObject(jsContext);
  if (JS_IsException(rdmaObj)) {
    JS_FreeValue(jsContext, global);
    return;
  }

  JS_SetPropertyStr(jsContext, rdmaObj, "appendBridgeChange",
      JS_NewCFunction(jsContext, rdmaAppendBridgeChange, "appendBridgeChange", 2));
  JS_SetPropertyStr(jsContext, rdmaObj, "appendCreate",
      JS_NewCFunction(jsContext, rdmaAppendCreate, "appendCreate", 2));
  JS_SetPropertyStr(jsContext, rdmaObj, "appendPropertyChange",
      JS_NewCFunction(jsContext, rdmaAppendPropertyChange, "appendPropertyChange", 4));
  JS_SetPropertyStr(jsContext, rdmaObj, "appendModifierChange",
      JS_NewCFunction(jsContext, rdmaAppendModifierChange, "appendModifierChange", 2));
  JS_SetPropertyStr(jsContext, rdmaObj, "appendAdd",
      JS_NewCFunction(jsContext, rdmaAppendAdd, "appendAdd", 4));
  JS_SetPropertyStr(jsContext, rdmaObj, "appendRemove",
      JS_NewCFunction(jsContext, rdmaAppendRemove, "appendRemove", 3));
  JS_SetPropertyStr(jsContext, rdmaObj, "setRemoveDetach",
      JS_NewCFunction(jsContext, rdmaSetRemoveDetach, "setRemoveDetach", 1));
  JS_SetPropertyStr(jsContext, rdmaObj, "appendMove",
      JS_NewCFunction(jsContext, rdmaAppendMove, "appendMove", 5));
  JS_SetPropertyStr(jsContext, rdmaObj, "finishChanges",
      JS_NewCFunction(jsContext, rdmaFinishChangesCallback, "finishChanges", 0));
  JS_SetPropertyStr(jsContext, rdmaObj, "changesLength",
      JS_NewCFunction(jsContext, rdmaChangesLengthCallback, "changesLength", 0));

  int setResult = JS_SetPropertyStr(jsContext, global,
      "app_cash_redwood_rdmaSendChanges", rdmaObj);

  JS_FreeValue(jsContext, global);
}
