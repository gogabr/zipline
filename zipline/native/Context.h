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
#ifndef QUICKJS_ANDROID_CONTEXT_H
#define QUICKJS_ANDROID_CONTEXT_H

#include <jni.h>
#include <string>
#include <vector>
#include <unordered_map>
#include <map>
#include <mutex>
#include "quickjs/quickjs.h"

class JSRuntime;
class JSContext;
class InboundCallChannel;

enum class RdmaChangeType {
  Create,
  PropertyChange,
  ModifierChange,
  Add,
  Remove,
  Move,
  BridgeChange,
};

constexpr int BATCH_SIZE = 2048;

struct RdmaChange {
  RdmaChangeType type;
  int id;
  int field1;    // tag (Create/Remove/Move), widgetTag (PropertyChange), childrenTag (Add)
  int field2;    // propertyTag (PropertyChange), childId (Add), index (Remove), fromIndex (Move)
  int field3;    // index (Add), toIndex (Move)
  int count;     // count (Move only)
  bool detach;   // detach flag (Remove only)
  JSValueConst jsValue; // JS payload for PropertyChange, ModifierChange; JS_NULL otherwise
};

class Context {
public:
  Context(JNIEnv *env);
  ~Context();

  InboundCallChannel* getInboundCallChannel(JNIEnv*, jstring name);
  void setOutboundCallChannel(JNIEnv*, jstring name, jobject callChannel);
  jobject execute(JNIEnv*, jbyteArray byteCode);
  jbyteArray compile(JNIEnv*, jstring source, jstring file);
  void setInterruptHandler(JNIEnv* env, jobject interruptHandler);
  jobject memoryUsage(JNIEnv*);
  void setMemoryLimit(JNIEnv* env, jlong limit);
  void setGcThreshold(JNIEnv* env, jlong gcThreshold);
  void gc(JNIEnv* env);
  void setMaxStackSize(JNIEnv* env, jlong stackSize);

  jobject toJavaObject(JNIEnv*, const JSValue& value, bool throwOnUnsupportedType = true);
  void throwJsException(JNIEnv*, const JSValue& value) const;
  JSValue throwJavaExceptionFromJs(JNIEnv*) const;

  // Host→guest bridge call (direct events): probe and call a callable on globalThis,
  // converting each argument host→JS via bridgeAnyToJs and the result JS→host via bridgeForAny.
  jboolean hasGlobalFunction(JNIEnv* env, jstring name);
  jobject callGuestFunction(JNIEnv* env, jstring name, jobject argsList);

  JNIEnv* getEnv() const;

  std::string toCppString(JNIEnv* env, jstring string) const;
  JSValue toJsString(JNIEnv* env, jstring string) const;
  jstring toJavaString(JNIEnv* env, const JSValueConst& value) const;

  // Process-wide JNI cache: class refs and method IDs are identical for every zipline session.
  // Initialized once under std::call_once by ensureStatics(); never freed (process lifetime).
  static JavaVM* javaVm;
  static jint jniVersion;
  static jclass booleanClass;
  static jclass integerClass;
  static jclass doubleClass;
  static jclass longClass;
  static jclass objectClass;
  static jclass stringClass;
  static jclass memoryUsageClass;
  static jclass quickJsExceptionClass;
  static jclass interruptHandlerClass;
  static jstring stringUtf8;
  static jmethodID booleanValueOf;
  static jmethodID integerValueOf;
  static jmethodID doubleValueOf;
  static jmethodID longValueOf;
  static jmethodID stringGetBytes;
  static jmethodID stringConstructor;
  static jmethodID memoryUsageConstructor;
  static jmethodID quickJsExceptionConstructor;
  static jmethodID interruptHandlerPoll;

  // JNI cache for RdmaBridge static JsonElement factories
  static jclass rdmaBridgeClass;
  static jmethodID rdmaBridgeJsonPrimitiveString;
  static jmethodID rdmaBridgeJsonPrimitiveInt;
  static jmethodID rdmaBridgeJsonPrimitiveLong;
  static jmethodID rdmaBridgeJsonPrimitiveDouble;
  static jmethodID rdmaBridgeJsonPrimitiveBoolean;
  static jmethodID rdmaBridgeJsonNull;
  static jmethodID rdmaBridgeCreateJsonArray;
  static jmethodID rdmaBridgeCreateJsonObject;

  // JNI cache for ArrayList
  static jclass arrayListClass;
  static jmethodID arrayListInit;
  static jmethodID arrayListInitWithCapacity;
  static jmethodID arrayListAdd;

  // JNI cache for host2js boxed-primitive keys and java.util.List/Map iteration
  static jclass floatClass;
  static jclass shortClass;
  static jclass byteClass;
  static jclass characterClass;
  static jclass listClass;
  static jclass mapClass;
  static jclass mapEntryClass;
  static jclass setClass;
  static jclass iteratorClass;
  static jmethodID integerIntValue;
  static jmethodID longLongValue;
  static jmethodID doubleDoubleValue;
  static jmethodID floatFloatValue;
  static jmethodID booleanBooleanValue;
  static jmethodID shortShortValue;
  static jmethodID byteByteValue;
  static jmethodID characterCharValue;
  static jmethodID objectToString;
  static jmethodID listSize;
  static jmethodID listGet;
  static jmethodID mapEntrySet;
  static jmethodID setIterator;
  static jmethodID iteratorHasNext;
  static jmethodID iteratorNext;
  static jmethodID entryGetKey;
  static jmethodID entryGetValue;

  // JNI cache for the RdmaChangeSink interface and kotlin.Pair
  static jmethodID rdmaSinkCreateCreate;
  static jmethodID rdmaSinkCreatePropertyChange;
  static jmethodID rdmaSinkCreateModifierChange;
  static jmethodID rdmaSinkCreateAdd;
  static jmethodID rdmaSinkCreateRemove;
  static jmethodID rdmaSinkCreateMove;
  static jmethodID rdmaSinkCreateBridgeChange;
  static jmethodID rdmaSinkSetRemoveDetach;
  static jmethodID rdmaSinkSendBatch;
  static jmethodID rdmaSinkSendChanges;
  static jclass pairClass;
  static jmethodID pairInit;

  static std::once_flag staticsInitFlag;
  static void ensureStatics(JNIEnv* env);

  // Per-session state: owned by this Context and freed in the destructor.
  JSRuntime *jsRuntime;
  JSContext *jsContext;
  JSContext *jsContextForCompiling;
  JSClassID outboundCallChannelClassId;
  JSAtom lengthAtom;
  JSAtom callAtom;
  JSAtom disconnectAtom;
  jobject interruptHandler;
  std::vector<InboundCallChannel*> callChannels;
  std::unordered_map<std::string, jclass> globalReferences;

  // Host2JS state: retained guest class prototypes (for bridgeNewJsObject instance creation)
  // and the guest's runtime factory functions (kotlin.Long/ArrayList/LinkedHashMap
  // construction). JS values are context-local, so these live on the Context and are freed in
  // the destructor.
  std::map<std::string, JSValue> bridgeProtos;
  JSValue bridgeNewLong;
  JSValue bridgeNewArrayList;
  JSValue bridgeNewLinkedHashMap;

  // Per-QuickJs RdmaChangeSink: all RDMA change delivery is routed through this instance so
  // that each zipline session gets its own change stream.
  jobject rdmaChangeSink = nullptr;

  std::vector<RdmaChange> pendingChanges;
  jobject jsValueToJsonElement(JNIEnv* env, JSValueConst val);
  jobject jsArrayToJsonElement(JNIEnv* env, JSValueConst val);
  jobject jsObjectToJsonElement(JNIEnv* env, JSValueConst val);
  void dispatchChangeToSink(JNIEnv* env, const RdmaChange& ch);
  void flushPendingBatch(JNIEnv* env, int count);
  void finishFlushPending(JNIEnv* env);
  void cacheRdmaSink(jobject rdmaChangeSink);
  void deleteBridgeRefs(JNIEnv* env);
  void initRdmaChangesChannel(JNIEnv* env, jobject rdmaChangeSink);
};

#endif //QUICKJS_ANDROID_CONTEXT_H
