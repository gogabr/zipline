#ifndef ZIPLINE_CONTEXT_NATIVE_H
#define ZIPLINE_CONTEXT_NATIVE_H

#include "DirectChange.h"
#include "hermes-core.h"

#include <jsi/jsi.h>

#include <string>
#include <vector>

// Function-pointer types for the per-context bridge callbacks. Kotlin/Native
// has no JNI, so it registers staticCFunction pointers instead of jobject
// method refs (see nativeMain/JsEngine.kt).
typedef void* (*OutboundCallChannelCallFn)(void* context, const char* callJson);
typedef int (*OutboundCallChannelDisconnectFn)(void* context, const char* instanceName);
typedef void (*DirectChangeSinkFn)(void* context);

// Per-change-type callbacks for the direct bridge host functions. Each is called
// directly from the JS host-function lambdas so changes flow straight into
// the Kotlin DirectChangeSink accumulator (matching the gogabr/jni-bridges design).
typedef void (*DirectCreateFn)(void* context, int id, int tag);
typedef void (*DirectPropertyChangeFn)(void* context, int id, int widgetTag, int propertyTag, int valueHandle);
typedef void (*DirectModifierChangeFn)(void* context, int id, int elementsHandle);
typedef void (*DirectAddFn)(void* context, int id, int childrenTag, int childId, int index);
typedef void (*DirectRemoveFn)(void* context, int id, int childrenTag, int index, int detach);
typedef void (*DirectMoveFn)(void* context, int id, int childrenTag, int fromIndex, int toIndex, int count);
typedef void (*DirectBridgeChangeFn)(void* context, int id, int jsValueHandle);
typedef void (*DirectSetRemoveDetachFn)(void* context, int index);
typedef void (*DirectSendChangesFn)(void* context);

// The Kotlin/Native engine context. Extends the core Hermes context with
// per-runtime bridge state (channel callbacks, direct bridge state), so multiple
// concurrent runtimes (e.g. an old and a new Zipline during a screen
// transition) never route calls into each other. HermesRuntime_create()
// returns this struct; HermesContext_* functions pass it to HermesCore_*
// functions via an implicit upcast (see asNativeContext).
struct ContextNative : ContextBase {
  OutboundCallChannelCallFn outboundCallFn = nullptr;
  OutboundCallChannelDisconnectFn outboundDisconnectFn = nullptr;
  DirectChangeSinkFn directSinkFn = nullptr;
  std::vector<DirectChange> pendingChanges;
  int removeCounter = 0;

  // Per-change-type direct bridge callbacks (called from JS host-function lambdas).
  DirectCreateFn createCb = nullptr;
  DirectPropertyChangeFn propertyChangeCb = nullptr;
  DirectModifierChangeFn modifierChangeCb = nullptr;
  DirectAddFn addCb = nullptr;
  DirectRemoveFn removeCb = nullptr;
  DirectMoveFn moveCb = nullptr;
  DirectBridgeChangeFn bridgeChangeCb = nullptr;
  DirectSetRemoveDetachFn setRemoveDetachCb = nullptr;
  DirectSendChangesFn sendChangesCb = nullptr;

  // Bridge-value handle table: stores jsi::Value objects that are referenced
  // by integer handles from Kotlin/Native (generated bridge code). Handle 0
  // is reserved (invalid). Handles are never reused; the table grows
  // monotonically and is cleared when the context is destroyed.
  std::vector<std::shared_ptr<facebook::jsi::Value>> bridgeHandles;
};

// Upcast a HermesContext* (void*) to the native context it was created as.
inline ContextNative* asNativeContext(void* context) {
  return static_cast<ContextNative*>(context);
}

#endif  // ZIPLINE_CONTEXT_NATIVE_H
