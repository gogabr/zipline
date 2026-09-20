#ifndef ZIPLINE_CONTEXT_JNI_H
#define ZIPLINE_CONTEXT_JNI_H

#include "RdmaChange.h"
#include "hermes-core.h"

#include <jni.h>
#include <memory>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

namespace jsi = facebook::jsi;
namespace hermes_vm = hermes::vm;

class ContextJni;

class ContextJni : public ContextBase {
 public:
  // forceEagerCompilation disables lazy compilation so CDP breakpoints bind
  // in runtime-compiled source (set when the CDP debug server is enabled).
  explicit ContextJni(JNIEnv* env, bool forceEagerCompilation = false);
  ~ContextJni() override;

  jsi::String toJsString(const std::string& str) override;
  std::string toCppString(const jsi::String& str) override;
  void throwJsException(const std::string& message) override;
  void throwJsError(facebook::jsi::JSError& error) override;

  // ----- JS / bytecode lifecycle.
  jobject execute(JNIEnv* env, jbyteArray byteCode, jstring fileName);
  jobject evaluate(JNIEnv* env, jstring source, jstring fileName);
  jbyteArray compile(JNIEnv* env, jstring source, jstring file,
                     jstring sourceMap);

  // ----- Configuration.
  jobject memoryUsage(JNIEnv* env);
  void gc(JNIEnv* env);

  // ----- Bridged call channels.
  InboundCallChannel* getInboundCallChannel(JNIEnv* env, jstring name);
  void setOutboundCallChannel(JNIEnv* env, jstring name, jobject callChannel);

  // ----- Helpers used by InboundCallChannel / OutboundCallChannel.
  jstring toJavaString(JNIEnv* env, const std::string& utf8) const;
  jstring toJavaString(JNIEnv* env, const jsi::String& s) const;
  std::string toCppString(JNIEnv* env, jstring javaString) const;
  jsi::String toJsString(JNIEnv* env, jstring javaString) const;
  jobject toJavaObject(JNIEnv* env, const jsi::Value& value,
                       bool throwOnUnsupportedType = true);
  void throwJsException(JNIEnv* env, jsi::JSError& error);
  jsi::Value throwJavaExceptionFromJs(JNIEnv* env);

  // Stashed Java throwable from a host-function call. Set by
  // throwJavaExceptionFromJs (after ExceptionClear), consumed and reset
  // by throwJsException when the wrapping JS error is observed.
  jthrowable pendingJavaException;

  JNIEnv* getEnv() const;

  // Cached JNI references used by throwers and the value converter.
  JavaVM* javaVm;
  const jint jniVersion;

  // Thread the engine was created on (the Zipline dispatcher thread). Engine
  // APIs that touch the runtime (heap sampling, heap snapshots) must run on
  // this thread; used to log a warning when they don't.
  std::thread::id jsThreadId;

  // Hermes runtime configuration snapshot (shared Zipline defaults from
  // HermesCore_makeRuntimeConfig) — memoryUsage() reports its heap sizes.
  hermes::vm::RuntimeConfig runtimeConfig;

  // ----- Cached JNI method / class refs.
  jclass booleanClass;
  jclass integerClass;
  jclass doubleClass;
  jclass longClass;
  jclass objectClass;
  jclass stringClass;
  jclass memoryUsageClass;
  jstring stringUtf8;
  jclass jsExceptionClass;
  jmethodID booleanValueOf;
  jmethodID integerValueOf;
  jmethodID doubleValueOf;
  jmethodID longValueOf;
  jmethodID stringGetBytes;
  jmethodID stringConstructor;
  jmethodID jsExceptionConstructor;

  // RDMA Changes support.
  // Stateless JsonElement factories on the RdmaBridge companion (still @JvmStatic).
  jclass rdmaBridgeClass = nullptr;
  jmethodID rdmaBridgeJsonPrimitiveString = nullptr;
  jmethodID rdmaBridgeJsonPrimitiveInt = nullptr;
  jmethodID rdmaBridgeJsonPrimitiveLong = nullptr;
  jmethodID rdmaBridgeJsonPrimitiveDouble = nullptr;
  jmethodID rdmaBridgeJsonPrimitiveBoolean = nullptr;
  jmethodID rdmaBridgeJsonNull = nullptr;
  jmethodID rdmaBridgeCreateJsonArray = nullptr;
  jmethodID rdmaBridgeCreateJsonObject = nullptr;
  jclass arrayListClass = nullptr;
  jmethodID arrayListInit = nullptr;
  jmethodID arrayListInitWithCapacity = nullptr;
  jmethodID arrayListAdd = nullptr;

  // Per-session RdmaChangeSink (global ref owned by this context). All change
  // delivery goes through this instance, so concurrent sessions never route
  // changes into each other's UI.
  jobject rdmaChangeSink = nullptr;
  jmethodID rdmaSinkCreateCreate = nullptr;
  jmethodID rdmaSinkCreatePropertyChange = nullptr;
  jmethodID rdmaSinkCreateModifierChange = nullptr;
  jmethodID rdmaSinkCreateAdd = nullptr;
  jmethodID rdmaSinkCreateRemove = nullptr;
  jmethodID rdmaSinkCreateMove = nullptr;
  jmethodID rdmaSinkCreateBridgeChange = nullptr;
  jmethodID rdmaSinkSetRemoveDetach = nullptr;
  jmethodID rdmaSinkSendBatch = nullptr;
  jmethodID rdmaSinkSendChanges = nullptr;

  // kotlin.Pair for modifier elements.
  jclass pairClass = nullptr;
  jmethodID pairInit = nullptr;

  std::vector<RdmaChange> pendingChanges;

  void cacheRdmaBridgeMethods(JNIEnv* env);
  void cacheRdmaSink(jobject sink);
  void dispatchChangeToSink(JNIEnv* env, const RdmaChange& ch);
  void deleteBridgeRefs(JNIEnv* env);
  jobject jsValueToJsonElement(JNIEnv* env, const jsi::Value& val);
  jobject jsArrayToJsonElement(JNIEnv* env, const jsi::Value& val);
  jobject jsObjectToJsonElement(JNIEnv* env, const jsi::Value& val);
  void flushPendingBatch(JNIEnv* env, int toFlush);
  void finishFlushPending(JNIEnv* env);
  void initRdmaChangesChannel(JNIEnv* env, jobject rdmaChangeSink);

  std::unordered_map<std::string, jclass> globalReferences;
};

#endif  // ZIPLINE_CONTEXT_JNI_H
