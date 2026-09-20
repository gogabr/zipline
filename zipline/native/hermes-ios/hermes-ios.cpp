#include "hermes-ios.h"
#include "hermes-core.h"
#include "../RdmaChange.h"
#include "../ContextNative.h"
#include "../CdpSession.h"
#include "../InboundCallChannel.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <cmath>

#include <string>
#include <vector>
#include <mutex>

#include <jsi/jsi.h>

namespace jsi = facebook::jsi;

// Process-global error buffer. NOT thread-safe: two engines reporting errors
// from different threads race on this buffer. The bridge is designed for
// single-threaded-per-runtime use, and errors are consumed immediately after
// each failing call on the same thread, so this is acceptable for now. If
// multi-threaded error reporting is ever needed, move this into
// ContextNative (per-runtime) or use thread_local storage.
static char g_lastError[1024];

// Returns the jsi runtime for a context, recording "Invalid runtime" in
// g_lastError and returning nullptr on failure. Callers return their own
// error value (0/NULL) when this returns nullptr.
static jsi::Runtime* getJsiRuntimeOrNull(void* context) {
    void* runtime = HermesCore_getRuntime(asNativeContext(context));
    if (!runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return nullptr;
    }
    return static_cast<jsi::Runtime*>(runtime);
}

namespace {

// Reserve handle 0 (the global object) and append [value] as a new handle. Every function that
// hands a handle to Kotlin goes through this, so the failure marker (-1) and the global-object
// sentinel (0) never collide with a real handle. Defined below; declared here because the RDMA
// host functions precede it.
int bridgeAppendHandle(ContextNative* ctx, jsi::Runtime& rt, jsi::Value value);


char* copyToMalloc(const std::string& str) {
    char* copy = (char*)malloc(str.size() + 1);
    if (copy) {
        memcpy(copy, str.c_str(), str.size() + 1);
    }
    return copy;
}

} // namespace

void HermesContext_setOutboundChannelCallbacks(void* context,
                                               OutboundCallChannelCallFn callFn,
                                               OutboundCallChannelDisconnectFn disconnectFn) {
    if (!context) return;
    ContextNative* ctx = asNativeContext(context);
    ctx->outboundCallFn = callFn;
    ctx->outboundDisconnectFn = disconnectFn;
}

void HermesContext_setRdmaChangeSink(void* context, RdmaChangeSinkFn sinkFn) {
    if (!context) return;
    asNativeContext(context)->rdmaSinkFn = sinkFn;
}

void HermesContext_setRdmaCreateCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->createCb = reinterpret_cast<RdmaCreateFn>(fn);
}
void HermesContext_setRdmaPropertyChangeCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->propertyChangeCb = reinterpret_cast<RdmaPropertyChangeFn>(fn);
}
void HermesContext_setRdmaModifierChangeCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->modifierChangeCb = reinterpret_cast<RdmaModifierChangeFn>(fn);
}
void HermesContext_setRdmaAddCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->addCb = reinterpret_cast<RdmaAddFn>(fn);
}
void HermesContext_setRdmaRemoveCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->removeCb = reinterpret_cast<RdmaRemoveFn>(fn);
}
void HermesContext_setRdmaMoveCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->moveCb = reinterpret_cast<RdmaMoveFn>(fn);
}
void HermesContext_setRdmaBridgeChangeCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->bridgeChangeCb = reinterpret_cast<RdmaBridgeChangeFn>(fn);
}
void HermesContext_setRdmaSetRemoveDetachCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->setRemoveDetachCb = reinterpret_cast<RdmaSetRemoveDetachFn>(fn);
}
void HermesContext_setRdmaSendChangesCallback(void* context, void* fn) {
    if (!context) return;
    asNativeContext(context)->sendChangesCb = reinterpret_cast<RdmaSendChangesFn>(fn);
}

int HermesFramework_init(void** runtimeOut) {
    if (!runtimeOut) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime output pointer");
        return 0;
    }
    ContextNative* ctx = new ContextNative();
    if (!HermesCore_initContext(ctx)) {
        delete ctx;
        snprintf(g_lastError, sizeof(g_lastError), "Failed to create Hermes runtime");
        return 0;
    }
    *runtimeOut = ctx;
    return 1;
}

namespace {

void* createRuntime(bool forceEagerCompilation) {
    ContextNative* ctx = new ContextNative();
    if (!HermesCore_initContext(ctx, forceEagerCompilation)) {
        delete ctx;
        snprintf(g_lastError, sizeof(g_lastError), "Failed to create Hermes runtime");
        return NULL;
    }
    return ctx;
}

} // namespace

void* HermesRuntime_create(void) {
    return createRuntime(false);
}

void* HermesRuntime_createForDebugging(void) {
    return createRuntime(true);
}

void HermesRuntime_destroy(void* runtime) {
    if (runtime) {
        ContextNative* ctx = asNativeContext(runtime);
        // Tear down any CDP session before the runtime goes away; the
        // session's agent and debug API reference the runtime.
        zipline_cdp::detach(ctx);
        if (HermesCore_getRuntime(ctx) != nullptr) {
            ctx->bridgeHandles.clear();
            ctx->pendingChanges.clear();
        }
        HermesCore_releaseContext(ctx);
        delete ctx;
    }
}

void* HermesRuntime_getJsiRuntime(void* runtime) {
    if (!runtime) return NULL;
    return HermesCore_getRuntime(asNativeContext(runtime));
}

namespace {

HermesTaggedValue toTaggedValue(jsi::Runtime& rt, const jsi::Value& v) {
    HermesTaggedValue out;
    out.tag = HERMES_TAG_NULL;
    out.number = 0;
    out.string = nullptr;
    if (v.isBool()) {
        out.tag = HERMES_TAG_BOOL;
        out.number = v.asBool() ? 1 : 0;
    } else if (v.isNumber()) {
        // Always encode numbers as double; the platform side re-narrows
        // integral values to Int.
        out.tag = HERMES_TAG_DOUBLE;
        out.number = v.asNumber();
    } else if (v.isString()) {
        out.tag = HERMES_TAG_STRING;
        out.string = strdup(v.asString(rt).utf8(rt).c_str());
    }
    return out;
}

} // namespace

HermesTaggedValue HermesContext_evaluate(void* context, const char* code, const char* sourceURL) {
    if (!context || !code) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    }

    ContextBase* ctx = asNativeContext(context);
    if (!ctx->runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    }

    // Run any pending CDP runtime tasks (e.g. breakpoint installation)
    // before evaluating more JavaScript. We are on the JS thread here.
    zipline_cdp::drainTasks(ctx);

    try {
        std::string source(code, strlen(code));
        jsi::Value result = ctx->runtime->evaluateJavaScript(
            std::make_unique<jsi::StringBuffer>(std::move(source)),
            sourceURL ? sourceURL : "<eval>");
        return toTaggedValue(*ctx->runtime, result);
    } catch (const jsi::JSError& e) {
        // Full text goes to ctx->lastError (unbounded; preferred by
        // HermesContext_getLastError), not the fixed g_lastError buffer.
        ctx->lastError = e.getMessage() + std::string("\n") + e.getStack();
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    } catch (const std::exception& e) {
        ctx->lastError = e.what();
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    }
}

int HermesContext_hasGlobalObject(void* context, const char* name) {
    if (!context || !name) {
        return 0;
    }
    return HermesCore_hasGlobalObject(asNativeContext(context), name);
}

void HermesContext_freeValue(void* context, char* value) {
    if (value) {
        free(value);
    }
}

int HermesContext_compile(void* context, const char* code, const char* sourceURL,
                          const char* sourceMap, char** bytecodeOut, int* bytecodeSizeOut) {
#ifdef HERMESVM_LEAN
    snprintf(g_lastError, sizeof(g_lastError), "compile() is not available in lean Hermes build");
    return 0;
#else
    if (!context || !code) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    uint8_t* bytecode = NULL;
    size_t bytecodeSize = 0;
    char* errorOut = NULL;

    int success = HermesCore_compile(asNativeContext(context), code, sourceURL, sourceMap, &bytecode, &bytecodeSize, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Compilation failed");
        if (errorOut) free(errorOut);
        return 0;
    }

    char* bytes = static_cast<char*>(malloc(bytecodeSize));
    if (!bytes) {
        delete[] bytecode; // Allocated with new[] by HermesCore_compile.
        snprintf(g_lastError, sizeof(g_lastError), "Out of memory");
        return 0;
    }
    memcpy(bytes, bytecode, bytecodeSize);
    delete[] bytecode; // Allocated with new[] by HermesCore_compile.

    *bytecodeOut = bytes;
    *bytecodeSizeOut = (int)bytecodeSize;
    return 1;
#endif
}

HermesTaggedValue HermesContext_execute(void* context, const uint8_t* bytecode, int bytecodeSize,
                                        const char* sourceURL) {
    if (!context || !bytecode) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    }

    ContextBase* ctx = asNativeContext(context);
    if (!ctx->runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    }

    // Run any pending CDP runtime tasks (e.g. breakpoint installation)
    // before executing more bytecode. We are on the JS thread here.
    zipline_cdp::drainTasks(ctx);

    try {
        jsi::Value result = HermesCore_evaluateBytecode(
            ctx, bytecode, bytecodeSize, sourceURL ? sourceURL : "zipline-module.js");
        return toTaggedValue(*ctx->runtime, result);
    } catch (const jsi::JSError& e) {
        ctx->lastError = e.getMessage() + std::string("\n") + e.getStack();
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    } catch (const std::exception& e) {
        ctx->lastError = e.what();
        return HermesTaggedValue{HERMES_TAG_ERROR, 0, NULL};
    }
}

int HermesContext_executeToHandle(void* context, const uint8_t* bytecode, int bytecodeSize,
                                  const char* sourceURL) {
    if (!context || !bytecode) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return -1;
    }

    ContextNative* ctx = asNativeContext(context);
    if (!ctx->runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return -1;
    }

    try {
        jsi::Value result = HermesCore_evaluateBytecode(
            ctx, bytecode, bytecodeSize, sourceURL ? sourceURL : "zipline-module.js");
        // Handle 0 is reserved as "global object" in HermesBridge_createHandle, so
        // the first real handle must start at 1.
        if (ctx->bridgeHandles.empty()) {
            ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(jsi::Value::undefined()));
        }
        int handle = static_cast<int>(ctx->bridgeHandles.size());
        ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(*ctx->runtime, result));
        return handle;
    } catch (const jsi::JSError& e) {
        ctx->lastError = e.getMessage() + std::string("\n") + e.getStack();
        snprintf(g_lastError, sizeof(g_lastError), "%s", e.getMessage().c_str());
        return -1;
    } catch (const std::exception& e) {
        ctx->lastError = e.what();
        snprintf(g_lastError, sizeof(g_lastError), "%s", e.what());
        return -1;
    }
}

int HermesContext_getGlobalProperty(void* context, const char* name, char** valueOut) {
    if (!context || !name) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_getGlobalProperty(asNativeContext(context), name, valueOut, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to get global property");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_setGlobalProperty(void* context, const char* name, const char* value) {
    if (!context || !name || !value) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_setGlobalProperty(asNativeContext(context), name, value, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to set global property");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_deleteGlobalProperty(void* context, const char* name) {
    if (!context || !name) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_deleteGlobalProperty(asNativeContext(context), name, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to delete global property");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_callGlobalMethod(void* context, const char* objectName, const char* methodName) {
    if (!context || !objectName || !methodName) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_callGlobalMethod(asNativeContext(context), objectName, methodName, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to call global method");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_callGlobalFunctionWithStringArg(void* context, const char* functionName,
                                                const char* arg, char** resultOut) {
    if (!context || !functionName) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_callGlobalFunctionWithStringArg(asNativeContext(context), functionName, arg, resultOut, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to call global function");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_installModuleLoader(void* context) {
    if (!context) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid context");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_installModuleLoader(asNativeContext(context), &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to install module loader");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_callRequireMethod(void* context, const char* moduleId, const char* methodName) {
    if (!context || !moduleId || !methodName) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    char* errorOut = NULL;
    int success = HermesCore_callRequireMethod(asNativeContext(context), moduleId, methodName, &errorOut);
    if (!success) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut ? errorOut : "Failed to call require method");
        if (errorOut) free(errorOut);
    }
    return success;
}

int HermesContext_initRdmaChangesChannel(void* context) {
    if (!context) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid context");
        return 0;
    }

    jsi::Runtime* rtPtr = getJsiRuntimeOrNull(context);
    if (!rtPtr) {
        return 0;
    }
    jsi::Runtime& rt = *rtPtr;

    jsi::Object rdmaObj(rt);

    auto appendCreateFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendCreate"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2 || !args[0].isNumber() || !args[1].isNumber()) {
                throw jsi::JSError(runtime, "appendCreate expects (id, tag)");
            }
            ContextNative* ctx = asNativeContext(context);
            if (ctx->createCb) {
                ctx->createCb(context, static_cast<int>(args[0].asNumber()),
                              static_cast<int>(args[1].asNumber()));
            }
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendCreate", appendCreateFn);

    auto appendPropertyChangeFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendPropertyChange"), 4,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 4 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber()) {
                throw jsi::JSError(runtime, "appendPropertyChange expects (id, widgetTag, propertyTag, value)");
            }
            ContextNative* ctx = asNativeContext(context);
            if (!ctx->propertyChangeCb) return jsi::Value::undefined();
            int handle = bridgeAppendHandle(ctx, runtime, jsi::Value(runtime, args[3]));
            ctx->propertyChangeCb(context,
                static_cast<int>(args[0].asNumber()),
                static_cast<int>(args[1].asNumber()),
                static_cast<int>(args[2].asNumber()),
                 handle);
            HermesBridge_freeHandle(context, handle);
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendPropertyChange", appendPropertyChangeFn);

    auto appendModifierChangeFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendModifierChange"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2 || !args[0].isNumber() || !args[1].isObject()) {
                throw jsi::JSError(runtime, "appendModifierChange expects (id, elements)");
            }
            ContextNative* ctx = asNativeContext(context);
            if (!ctx->modifierChangeCb) return jsi::Value::undefined();
            int handle = bridgeAppendHandle(ctx, runtime, jsi::Value(runtime, args[1]));
            ctx->modifierChangeCb(context, static_cast<int>(args[0].asNumber()), handle);
            HermesBridge_freeHandle(context, handle);
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendModifierChange", appendModifierChangeFn);

    auto appendAddFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendAdd"), 4,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 4 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber() || !args[3].isNumber()) {
                throw jsi::JSError(runtime, "appendAdd expects (id, childrenTag, childId, index)");
            }
            ContextNative* ctx = asNativeContext(context);
            if (ctx->addCb) {
                ctx->addCb(context,
                    static_cast<int>(args[0].asNumber()),
                    static_cast<int>(args[1].asNumber()),
                    static_cast<int>(args[2].asNumber()),
                     static_cast<int>(args[3].asNumber()));
            }
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendAdd", appendAddFn);

    auto appendRemoveFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendRemove"), 3,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 3 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber()) {
                throw jsi::JSError(runtime, "appendRemove expects (id, childrenTag, index)");
            }
            ContextNative* ctx = asNativeContext(context);
            int id = static_cast<int>(args[0].asNumber());
            int ct = static_cast<int>(args[1].asNumber());
            int idx = static_cast<int>(args[2].asNumber());
            if (ctx->removeCb) {
                ctx->removeCb(context, id, ct, idx, 0);
            }
            return jsi::Value(ctx->removeCounter++);
        });
    rdmaObj.setProperty(rt, "appendRemove", appendRemoveFn);

    auto setRemoveDetachFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "setRemoveDetach"), 1,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isNumber()) {
                throw jsi::JSError(runtime, "setRemoveDetach expects (idx)");
            }
            ContextNative* ctx = asNativeContext(context);
            if (ctx->setRemoveDetachCb) {
                ctx->setRemoveDetachCb(context, static_cast<int>(args[0].asNumber()));
            }
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "setRemoveDetach", setRemoveDetachFn);

    auto appendMoveFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendMove"), 5,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 5 || !args[0].isNumber() || !args[1].isNumber() || !args[2].isNumber() || !args[3].isNumber() || !args[4].isNumber()) {
                throw jsi::JSError(runtime, "appendMove expects (id, childrenTag, fromIndex, toIndex, count)");
            }
            ContextNative* ctx = asNativeContext(context);
            if (ctx->moveCb) {
                ctx->moveCb(context,
                    static_cast<int>(args[0].asNumber()),
                    static_cast<int>(args[1].asNumber()),
                    static_cast<int>(args[2].asNumber()),
                    static_cast<int>(args[3].asNumber()),
                     static_cast<int>(args[4].asNumber()));
            }
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendMove", appendMoveFn);

    auto appendBridgeChangeFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "appendBridgeChange"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value&,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 2 || !args[0].isNumber() || !args[1].isObject()) {
                return jsi::Value::undefined();
            }
            ContextNative* ctx = asNativeContext(context);
            if (!ctx->bridgeChangeCb) return jsi::Value::undefined();
            int handle = bridgeAppendHandle(ctx, runtime, jsi::Value(runtime, args[1]));
            ctx->bridgeChangeCb(context, static_cast<int>(args[0].asNumber()), handle);
            HermesBridge_freeHandle(context, handle);
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "appendBridgeChange", appendBridgeChangeFn);

    auto finishChangesFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "finishChanges"), 0,
        [context](jsi::Runtime&, const jsi::Value&,
           const jsi::Value*, size_t) -> jsi::Value {
            ContextNative* ctx = asNativeContext(context);
            ctx->removeCounter = 0;
            if (ctx->sendChangesCb) {
                ctx->sendChangesCb(context);
            }
            return jsi::Value::undefined();
        });
    rdmaObj.setProperty(rt, "finishChanges", finishChangesFn);

    auto changesLengthFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "changesLength"), 0,
        [context](jsi::Runtime&, const jsi::Value&,
           const jsi::Value*, size_t) -> jsi::Value {
            return jsi::Value(asNativeContext(context)->removeCounter);
        });
    rdmaObj.setProperty(rt, "changesLength", changesLengthFn);

    rt.global().setProperty(rt, "app_cash_redwood_rdmaSendChanges", rdmaObj);
    return 1;
}

void HermesContext_setMemoryLimit(void* context, int64_t limitBytes) {
    if (context) {
        HermesCore_setMemoryLimit(asNativeContext(context), limitBytes);
    }
}

void HermesContext_setGcThreshold(void* context, int64_t thresholdBytes) {
    if (context) {
        HermesCore_setGcThreshold(asNativeContext(context), thresholdBytes);
    }
}

void HermesContext_setMaxStackSize(void* context, int64_t maxStackSizeBytes) {
    if (context) {
        HermesCore_setMaxStackSize(asNativeContext(context), maxStackSizeBytes);
    }
}

void HermesContext_gc(void* context) {
    if (context) {
        HermesCore_gc(asNativeContext(context));
    }
}

int HermesContext_getMemoryUsage(void* context, HermesCoreMemoryUsage* usageOut) {
    if (!context || !usageOut) {
        return 0;
    }
    return HermesCore_getMemoryUsage(asNativeContext(context), usageOut);
}

const char* Hermes_getVersion(void) {
    return HermesCore_getVersion();
}

const char* HermesContext_getLastError(void* context) {
    if (context) {
        const char* err = HermesCore_getLastError(asNativeContext(context));
        if (err && strlen(err) > 0) {
            return err;
        }
    }
    return g_lastError;
}

int HermesContext_setupOutboundCallChannel(void* context) {
    if (!context) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid context");
        return 0;
    }

    jsi::Runtime* rtPtr = getJsiRuntimeOrNull(context);
    if (!rtPtr) {
        return 0;
    }
    jsi::Runtime& rt = *rtPtr;

    jsi::Object outboundChannel(rt);

    jsi::Function callFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "call"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isString()) {
                throw jsi::JSError(runtime, "outboundChannel.call expects (callJson)");
            }
            std::string callJson = args[0].asString(runtime).utf8(runtime);

            ContextNative* ctx = asNativeContext(context);
            if (ctx->outboundCallFn) {
                char* result = (char*)ctx->outboundCallFn(context, callJson.c_str());
                if (result) {
                    jsi::Value jsResult = jsi::String::createFromUtf8(runtime, std::string(result));
                    free(result);
                    return jsResult;
                }
            }
            return jsi::Value::undefined();
        });
    outboundChannel.setProperty(rt, "call", callFn);

    jsi::Function disconnectFn = jsi::Function::createFromHostFunction(
        rt, jsi::PropNameID::forUtf8(rt, "disconnect"), 2,
        [context](jsi::Runtime& runtime, const jsi::Value& thisVal,
           const jsi::Value* args, size_t count) -> jsi::Value {
            if (count < 1 || !args[0].isString()) {
                throw jsi::JSError(runtime, "outboundChannel.disconnect expects (instanceName)");
            }
            std::string instanceName = args[0].asString(runtime).utf8(runtime);

            ContextNative* ctx = asNativeContext(context);
            if (ctx->outboundDisconnectFn) {
                int result = ctx->outboundDisconnectFn(context, instanceName.c_str());
                return jsi::Value(result != 0);
            }
            return jsi::Value(false);
        });
    outboundChannel.setProperty(rt, "disconnect", disconnectFn);

    rt.global().setProperty(rt, "app_cash_zipline_outboundChannel", outboundChannel);
    return 1;
}

char* HermesContext_callInbound(void* context, const char* channelName, const char* callJson) {
    if (!context || !channelName || !callJson) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return NULL;
    }

    ContextNative* ctx = asNativeContext(context);
    if (!ctx->runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return NULL;
    }

    // Run any pending CDP runtime tasks (e.g. breakpoint installation)
    // before calling into JavaScript. We are on the JS thread here.
    zipline_cdp::drainTasks(ctx);

    // InboundCallChannel reports errors via throwJsException, captured in
    // the context's lastError and mapped to the C API error channel.
    InboundCallChannel channel(channelName);
    std::string result = channel.call(ctx, callJson);
    if (!ctx->lastError.empty()) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", ctx->lastError.c_str());
        return NULL;
    }
    return copyToMalloc(result);
}

char* HermesContext_callInboundDisconnect(void* context, const char* channelName, const char* instanceName) {
    if (!context || !channelName || !instanceName) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return NULL;
    }

    ContextNative* ctx = asNativeContext(context);
    if (!ctx->runtime) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid runtime");
        return NULL;
    }

    InboundCallChannel channel(channelName);
    bool result = channel.disconnect(ctx, instanceName);
    if (!ctx->lastError.empty()) {
        snprintf(g_lastError, sizeof(g_lastError), "%s", ctx->lastError.c_str());
        return NULL;
    }
    return copyToMalloc(result ? "true" : "false");
}

// -- Bridge Handle Management (for Kotlin/Native generated bridge code) --
// Tag constants are defined as #defines in hermes-ios.h (BRIDGE_TAG_*).

int HermesBridge_createHandle(void* context, int parentHandle, const char* name) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return 0;
    jsi::Runtime& rt = *ctx->runtime;

    // Allocate new handle
    if (parentHandle == 0) {
        // 0 = no parent, create from global object
        return bridgeAppendHandle(ctx, rt, jsi::Value(rt, rt.global().getProperty(rt, name)));
    } else if (parentHandle > 0 && static_cast<size_t>(parentHandle) < ctx->bridgeHandles.size()) {
        auto& parent = ctx->bridgeHandles[parentHandle];
        if (parent->isObject()) {
            return bridgeAppendHandle(
                ctx, rt, jsi::Value(rt, parent->asObject(rt).getProperty(rt, name)));
        }
        return 0;
    }
    return 0;
}

int HermesBridge_createArrayElementHandle(void* context, int arrayHandle, int index) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return 0;
    if (arrayHandle < 0 || static_cast<size_t>(arrayHandle) >= ctx->bridgeHandles.size()) return 0;

    jsi::Runtime& rt = *ctx->runtime;
    auto& val = ctx->bridgeHandles[arrayHandle];
    if (!val->isObject()) return 0;
    jsi::Object obj = val->asObject(rt);

    // Use getProperty by index so both plain JS arrays and Kotlin/JS typed
    // arrays (Int32Array, Float64Array, ...) are supported.
    jsi::Value elem = obj.getProperty(rt, std::to_string(index).c_str());
    return bridgeAppendHandle(ctx, rt, std::move(elem));
}

int HermesBridge_getArrayLength(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return -1;
    if (handle < 0 || static_cast<size_t>(handle) >= ctx->bridgeHandles.size()) return -1;

    jsi::Runtime& rt = *ctx->runtime;
    auto& val = ctx->bridgeHandles[handle];
    if (!val->isObject()) return -1;
    jsi::Object obj = val->asObject(rt);

    jsi::Value lenVal = obj.getProperty(rt, "length");
    if (!lenVal.isNumber()) return -1;
    return static_cast<int>(lenVal.asNumber());
}

int HermesBridge_getValueTag(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return BRIDGE_TAG_UNDEFINED;
    if (handle < 0 || static_cast<size_t>(handle) >= ctx->bridgeHandles.size()) return BRIDGE_TAG_UNDEFINED;

    auto& val = ctx->bridgeHandles[handle];
    jsi::Runtime& rt = *ctx->runtime;

    if (val->isNull())    return BRIDGE_TAG_NULL;
    if (val->isUndefined()) return BRIDGE_TAG_UNDEFINED;
    if (val->isBool())   return BRIDGE_TAG_BOOL;

    if (val->isNumber()) {
        double d = val->asNumber();
        if (std::trunc(d) == d && d >= -2147483648.0 && d <= 2147483647.0)
            return BRIDGE_TAG_INT;
        return BRIDGE_TAG_DOUBLE;
    }

    if (val->isString()) return BRIDGE_TAG_STRING;

    if (val->isObject()) {
        jsi::Object obj = val->asObject(rt);
        if (obj.isArray(rt)) return BRIDGE_TAG_ARRAY;
        return BRIDGE_TAG_OBJECT;
    }

    return BRIDGE_TAG_UNDEFINED;
}

double HermesBridge_getValueDouble(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || handle < 0 || static_cast<size_t>(handle) >= ctx->bridgeHandles.size()) return 0.0;
    auto& val = ctx->bridgeHandles[handle];
    return val->isNumber() ? val->asNumber() : 0.0;
}

int HermesBridge_getValueBool(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || handle < 0 || static_cast<size_t>(handle) >= ctx->bridgeHandles.size()) return 0;
    auto& val = ctx->bridgeHandles[handle];
    return val->isBool() ? (val->asBool() ? 1 : 0) : 0;
}

char* HermesBridge_getValueString(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return NULL;
    if (handle < 0 || static_cast<size_t>(handle) >= ctx->bridgeHandles.size()) return NULL;

    jsi::Runtime& rt = *ctx->runtime;
    auto& val = ctx->bridgeHandles[handle];
    if (!val->isString()) return NULL;

    std::string s = val->asString(rt).utf8(rt);
    return strdup(s.c_str());  // caller frees with free()
}

void HermesBridge_freeHandle(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx) return;
    if (handle >= 0 && static_cast<size_t>(handle) < ctx->bridgeHandles.size()) {
        ctx->bridgeHandles[handle].reset();
    }
}

int HermesBridge_getObjectPropertyNames(void* context, int objectHandle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return 0;
    if (objectHandle < 0 || static_cast<size_t>(objectHandle) >= ctx->bridgeHandles.size()) return 0;

    jsi::Runtime& rt = *ctx->runtime;
    auto& val = ctx->bridgeHandles[objectHandle];
    if (!val->isObject()) return 0;
    jsi::Object obj = val->asObject(rt);
    if (obj.isArray(rt)) return 0;

    jsi::Array names = obj.getPropertyNames(rt);
    return bridgeAppendHandle(ctx, rt, jsi::Value(rt, names));
}

static jsi::Value bridgeFindMethod(jsi::Runtime& rt, const jsi::Value& obj, const char* prefix) {
    if (!obj.isObject()) return jsi::Value::undefined();
    jsi::Object o = obj.asObject(rt);
    jsi::Value ctor = o.getProperty(rt, "constructor");
    if (!ctor.isObject()) return jsi::Value::undefined();
    jsi::Value proto = ctor.asObject(rt).getProperty(rt, "prototype");
    while (proto.isObject()) {
        jsi::Array names = proto.asObject(rt).getPropertyNames(rt);
        size_t n = names.length(rt);
        for (size_t i = 0; i < n; i++) {
            jsi::Value nm = names.getValueAtIndex(rt, i);
            if (!nm.isString()) continue;
            std::string name = nm.asString(rt).utf8(rt);
            if (strncmp(name.c_str(), prefix, strlen(prefix)) == 0) {
                jsi::Value fn = o.getProperty(rt, name.c_str());
                if (fn.isObject() && fn.asObject(rt).isFunction(rt)) {
                    return fn;
                }
            }
        }
        proto = proto.asObject(rt).getProperty(rt, "__proto__");
    }
    return jsi::Value::undefined();
}

int HermesBridge_getMapEntries(void* context, int mapHandle, int* keysHandleOut, int* valuesHandleOut) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime || !keysHandleOut || !valuesHandleOut) return 0;
    if (mapHandle < 0 || static_cast<size_t>(mapHandle) >= ctx->bridgeHandles.size()) return 0;

    jsi::Runtime& rt = *ctx->runtime;
    auto& val = ctx->bridgeHandles[mapHandle];
    if (!val || !val->isObject()) return 0;

    jsi::Object map = val->asObject(rt);

    try {
        // Iterate via the public Kotlin/JS Map.entries API (get_entries_ →
        // iterator_ → hasNext_/next_ → get_key_/get_value_). This works for any
        // Kotlin/JS Map implementation (HashMap, LinkedHashMap, etc.), unlike the
        // HashMap-specific internalMap backing table. Mirrors the C bridge.
        jsi::Value entriesFn = bridgeFindMethod(rt, *val, "get_entries_");
        if (entriesFn.isUndefined() || !entriesFn.isObject()) return 0;
        jsi::Value entries = entriesFn.asObject(rt).asFunction(rt).callWithThis(rt, map);
        if (!entries.isObject()) return 0;

        jsi::Value iterFn = bridgeFindMethod(rt, entries, "iterator_");
        if (iterFn.isUndefined() || !iterFn.isObject()) return 0;
        jsi::Value iterator = iterFn.asObject(rt).asFunction(rt).callWithThis(rt, entries.asObject(rt));
        if (!iterator.isObject()) return 0;

        jsi::Value hasNextFn = bridgeFindMethod(rt, iterator, "hasNext_");
        jsi::Value nextFn = bridgeFindMethod(rt, iterator, "next_");
        if (hasNextFn.isUndefined() || nextFn.isUndefined()) return 0;

        std::vector<jsi::Value> keyVec;
        std::vector<jsi::Value> valueVec;
        while (true) {
            jsi::Value hn = hasNextFn.asObject(rt).asFunction(rt).callWithThis(rt, iterator.asObject(rt));
            if (!hn.isBool() || !hn.asBool()) break;
            jsi::Value entry = nextFn.asObject(rt).asFunction(rt).callWithThis(rt, iterator.asObject(rt));
            if (!entry.isObject()) break;

            jsi::Value keyFn = bridgeFindMethod(rt, entry, "get_key_");
            jsi::Value valueFn = bridgeFindMethod(rt, entry, "get_value_");
            if (keyFn.isUndefined() || valueFn.isUndefined()) break;

            keyVec.emplace_back(keyFn.asObject(rt).asFunction(rt).callWithThis(rt, entry.asObject(rt)));
            valueVec.emplace_back(valueFn.asObject(rt).asFunction(rt).callWithThis(rt, entry.asObject(rt)));
        }

        jsi::Array keys = jsi::Array(rt, keyVec.size());
        jsi::Array values = jsi::Array(rt, valueVec.size());
        for (size_t i = 0; i < keyVec.size(); i++) {
            keys.setValueAtIndex(rt, i, keyVec[i]);
            values.setValueAtIndex(rt, i, valueVec[i]);
        }

        int keysHandle = static_cast<int>(ctx->bridgeHandles.size());
        ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(rt, keys));
        int valuesHandle = static_cast<int>(ctx->bridgeHandles.size());
        ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(rt, values));
        *keysHandleOut = keysHandle;
        *valuesHandleOut = valuesHandle;
        return 1;
    } catch (const jsi::JSError& e) {
        return 0;
    }
}

intptr_t HermesBridge_getBridgeDispatch(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return 0;
    if (handle < 0 || static_cast<size_t>(handle) >= ctx->bridgeHandles.size()) return 0;

    jsi::Runtime& rt = *ctx->runtime;
    auto& val = ctx->bridgeHandles[handle];
    if (!val->isObject()) return 0;

    jsi::Object obj = val->asObject(rt);
    jsi::Value lowVal = obj.getProperty(rt, "bridge_dispatch_low");
    jsi::Value highVal = obj.getProperty(rt, "bridge_dispatch_high");
    if (lowVal.isUndefined() || highVal.isUndefined()) {
        return 0;
    }

    int32_t low = static_cast<int32_t>(lowVal.asNumber());
    int32_t high = static_cast<int32_t>(highVal.asNumber());
    intptr_t result = (static_cast<intptr_t>(high) << 32) |
           static_cast<intptr_t>(static_cast<uint32_t>(low));
    return result;
}

void HermesBridge_clearHandles(void* context) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx) return;
    ctx->bridgeHandles.clear();
}

// -- Bridge dispatch registration (__bridgeRegister) --

static std::vector<std::pair<std::string, void*>> g_iosBridgeTable;
static std::mutex g_iosBridgeMutex;

void HermesBridge_addBridgeEntry(const char* fqn, void* fn) {
    std::lock_guard<std::mutex> lock(g_iosBridgeMutex);
    g_iosBridgeTable.push_back({fqn, fn});
}

namespace {

// Reserve handle 0 (the global object) and append [value] as a new handle. Every function that
// hands a handle back to Kotlin goes through here so the failure marker (-1) never collides
// with a real handle.
int bridgeAppendHandle(ContextNative* ctx, jsi::Runtime& rt, jsi::Value value) {
    if (ctx->bridgeHandles.empty()) {
        ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(jsi::Value::undefined()));
    }
    int handle = static_cast<int>(ctx->bridgeHandles.size());
    ctx->bridgeHandles.push_back(std::make_shared<jsi::Value>(rt, std::move(value)));
    return handle;
}

// Record [message] as this context's last error (the same buffer the other failing entry
// points fill) and return the handle failure marker.
int bridgeFailure(ContextNative* ctx, const std::string& message) {
    if (ctx) ctx->lastError = message;
    snprintf(g_lastError, sizeof(g_lastError), "%s", message.c_str());
    return -1;
}

// The value behind [handle], or nullptr when the handle is invalid. Handle 0 is the global
// object and has no stored value of its own.
jsi::Value* bridgeHandleValue(ContextNative* ctx, int handle) {
    if (handle <= 0 || static_cast<size_t>(handle) >= ctx->bridgeHandles.size()) return nullptr;
    return ctx->bridgeHandles[handle].get();
}

} // namespace

void HermesBridge_installBridgeRegister(void* jsiRuntime) {
    if (!jsiRuntime) return;
    jsi::Runtime& rt = *static_cast<jsi::Runtime*>(jsiRuntime);

    auto bridgeRegisterFn = jsi::Function::createFromHostFunction(
        rt,
        jsi::PropNameID::forUtf8(rt, "__bridgeRegister"),
        2,
        [](jsi::Runtime& rt, const jsi::Value&, const jsi::Value* args, size_t argc) -> jsi::Value {
            if (argc < 2) return jsi::Value::undefined();
            if (!args[0].isString() || !args[1].isObject()) return jsi::Value::undefined();
            auto fq = args[0].asString(rt).utf8(rt);
            jsi::Object ctor = args[1].asObject(rt);

            // Retain the class prototype so the host can build instances of it later
            // (newObjectWithPrototype). A class that only travels host->JS has no entry in the
            // FQN table below; that is not an error, it just carries no bridge dispatch.
            jsi::Value protoVal = ctor.getProperty(rt, "prototype");
            if (protoVal.isObject()) {
                jsi::Value protosVal = rt.global().getProperty(rt, "__zipline_bridgePrototypes");
                if (!protosVal.isObject()) {
                    protosVal = jsi::Value(rt, jsi::Object(rt));
                    rt.global().setProperty(rt, "__zipline_bridgePrototypes", jsi::Value(rt, protosVal));
                }
                protosVal.asObject(rt).setProperty(rt, fq.c_str(), jsi::Value(rt, protoVal));

                std::lock_guard<std::mutex> lock(g_iosBridgeMutex);
                jsi::Object proto = protoVal.asObject(rt);
                for (auto& entry : g_iosBridgeTable) {
                    if (entry.first == fq) {
                        intptr_t ptr = reinterpret_cast<intptr_t>(entry.second);
                        int32_t low  = static_cast<int32_t>(ptr & 0xFFFFFFFF);
                        int32_t high = static_cast<int32_t>((ptr >> 32) & 0xFFFFFFFF);
                        proto.setProperty(rt, "bridge_dispatch_low",  jsi::Value(static_cast<double>(low)));
                        proto.setProperty(rt, "bridge_dispatch_high", jsi::Value(static_cast<double>(high)));
                        break;
                    }
                }
            }
            return jsi::Value::undefined();
        });

    rt.global().setProperty(rt, "__bridgeRegister", std::move(bridgeRegisterFn));

    // The guest's module-load hook hands its host2js runtime factories (newLong, newArrayList,
    // newLinkedHashMap) to the host. They are keyed by globalThis.__zipline_bridgeFactories
    // because jsi::Value is runtime-local: the map cannot live in a file-static table.
    auto bridgeRegisterRuntimeFn = jsi::Function::createFromHostFunction(
        rt,
        jsi::PropNameID::forUtf8(rt, "__bridgeRegisterRuntime"),
        1,
        [](jsi::Runtime& rt, const jsi::Value&, const jsi::Value* args, size_t argc) -> jsi::Value {
            if (argc < 1 || !args[0].isObject()) return jsi::Value::undefined();
            rt.global().setProperty(rt, "__zipline_bridgeFactories", jsi::Value(rt, args[0]));
            return jsi::Value::undefined();
        });

    rt.global().setProperty(rt, "__bridgeRegisterRuntime", std::move(bridgeRegisterRuntimeFn));
}

int HermesBridge_newObjectWithPrototype(void* context, const char* fq) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime || !fq) return bridgeFailure(ctx, "host2js: invalid context");
    jsi::Runtime& rt = *ctx->runtime;
    try {
        jsi::Value protoVal = jsi::Value::undefined();
        jsi::Value protosVal = rt.global().getProperty(rt, "__zipline_bridgePrototypes");
        if (protosVal.isObject()) {
            protoVal = protosVal.asObject(rt).getProperty(rt, fq);
        }
        if (!protoVal.isObject()) {
            return bridgeFailure(ctx,
                std::string("host2js: no registered prototype for ") + fq +
                "; the guest module did not call __bridgeRegister for this class");
        }
        jsi::Object instance = jsi::Object::create(rt, protoVal);
        return bridgeAppendHandle(ctx, rt, jsi::Value(rt, instance));
    } catch (const jsi::JSError& e) {
        return bridgeFailure(ctx, e.getMessage());
    } catch (const std::exception& e) {
        return bridgeFailure(ctx, e.what());
    }
}

void HermesBridge_defineProperty(void* context, int objHandle, const char* name, int valueHandle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime || !name) return;
    jsi::Runtime& rt = *ctx->runtime;
    jsi::Value* objVal = bridgeHandleValue(ctx, objHandle);
    jsi::Value* val = bridgeHandleValue(ctx, valueHandle);
    if (!objVal || !val || !objVal->isObject()) return;
    try {
        // jsi has no defineProperty: go through Object.defineProperty so the property is an own
        // data property (writable/enumerable/configurable) rather than a [[Set]] that could
        // invoke a getter-only accessor on the class prototype.
        jsi::Value objectCtorVal = rt.global().getProperty(rt, "Object");
        if (!objectCtorVal.isObject()) return;
        jsi::Object objectCtor = objectCtorVal.asObject(rt);
        jsi::Value definePropertyVal = objectCtor.getProperty(rt, "defineProperty");
        if (!definePropertyVal.isObject()) return;
        jsi::Function defineProperty = definePropertyVal.asObject(rt).getFunction(rt);

        jsi::Object descriptor(rt);
        descriptor.setProperty(rt, "value", jsi::Value(rt, *val));
        descriptor.setProperty(rt, "writable", jsi::Value(true));
        descriptor.setProperty(rt, "enumerable", jsi::Value(true));
        descriptor.setProperty(rt, "configurable", jsi::Value(true));

        jsi::Value args[3] = {
            jsi::Value(rt, *objVal),
            jsi::String::createFromUtf8(rt, name),
            jsi::Value(rt, descriptor),
        };
        // Cast explicitly: the variadic callWithThis template otherwise beats the
        // (Value*, size_t) overload when the count needs a conversion.
        defineProperty.callWithThis(
            rt, objectCtor, static_cast<const jsi::Value*>(args), static_cast<size_t>(3));
    } catch (const jsi::JSError& e) {
        ctx->lastError = e.getMessage();
        snprintf(g_lastError, sizeof(g_lastError), "%s", e.getMessage().c_str());
    } catch (const std::exception& e) {
        ctx->lastError = e.what();
        snprintf(g_lastError, sizeof(g_lastError), "%s", e.what());
    }
}

int HermesBridge_createInt(void* context, int value) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return bridgeFailure(ctx, "bridge: invalid context");
    return bridgeAppendHandle(ctx, *ctx->runtime, jsi::Value(static_cast<double>(value)));
}

int HermesBridge_createDouble(void* context, double value) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return bridgeFailure(ctx, "bridge: invalid context");
    return bridgeAppendHandle(ctx, *ctx->runtime, jsi::Value(value));
}

int HermesBridge_createBool(void* context, int value) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return bridgeFailure(ctx, "bridge: invalid context");
    return bridgeAppendHandle(ctx, *ctx->runtime, jsi::Value(value != 0));
}

int HermesBridge_createString(void* context, const char* utf8) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime || !utf8) return bridgeFailure(ctx, "bridge: invalid string");
    jsi::Runtime& rt = *ctx->runtime;
    try {
        return bridgeAppendHandle(ctx, rt,
            jsi::Value(rt, jsi::String::createFromUtf8(rt, std::string(utf8))));
    } catch (const jsi::JSError& e) {
        return bridgeFailure(ctx, e.getMessage());
    } catch (const std::exception& e) {
        return bridgeFailure(ctx, e.what());
    }
}

int HermesBridge_createNull(void* context) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return bridgeFailure(ctx, "bridge: invalid context");
    return bridgeAppendHandle(ctx, *ctx->runtime, jsi::Value::null());
}

int HermesBridge_getProperty(void* context, int parentHandle, const char* name) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime || !name) return -1;
    jsi::Runtime& rt = *ctx->runtime;
    jsi::Value value = jsi::Value::undefined();
    try {
        if (parentHandle == 0) {
            // Same convention as HermesBridge_createHandle: 0 means the global object.
            value = rt.global().getProperty(rt, name);
        } else {
            jsi::Value* parent = bridgeHandleValue(ctx, parentHandle);
            if (!parent || !parent->isObject()) return -1;
            value = parent->asObject(rt).getProperty(rt, name);
        }
    } catch (const jsi::JSError&) {
        return -1;
    } catch (const std::exception&) {
        return -1;
    }
    if (value.isUndefined() || value.isNull()) return -1;
    return bridgeAppendHandle(ctx, rt, std::move(value));
}

int HermesBridge_newArray(void* context) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return bridgeFailure(ctx, "bridge: invalid context");
    jsi::Runtime& rt = *ctx->runtime;
    return bridgeAppendHandle(ctx, rt, jsi::Value(rt, jsi::Array(rt, 0)));
}

void HermesBridge_setArrayElement(void* context, int arrayHandle, int index, int valueHandle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime || index < 0) return;
    jsi::Runtime& rt = *ctx->runtime;
    jsi::Value* arrVal = bridgeHandleValue(ctx, arrayHandle);
    jsi::Value* val = bridgeHandleValue(ctx, valueHandle);
    if (!arrVal || !val || !arrVal->isObject()) return;
    try {
        jsi::Object arrObj = arrVal->asObject(rt);
        if (!arrObj.isArray(rt)) return;
        // Not Array::setValueAtIndex: Hermes' implementation *throws* when the index is out of
        // bounds instead of growing the array. A numeric property write has [[Set]] semantics on
        // an array, so it appends (and leaves holes for a skipped index) like JS itself does.
        arrObj.setProperty(rt, std::to_string(index).c_str(), jsi::Value(rt, *val));
    } catch (const jsi::JSError& e) {
        ctx->lastError = e.getMessage();
        snprintf(g_lastError, sizeof(g_lastError), "%s", e.getMessage().c_str());
    } catch (const std::exception& e) {
        ctx->lastError = e.what();
        snprintf(g_lastError, sizeof(g_lastError), "%s", e.what());
    }
}

namespace {

// Shared body of the callFunction* entry points: -1 (and no table change) unless [fnHandle]
// holds a callable, the result handle otherwise.
int bridgeCallFunction(ContextNative* ctx, int fnHandle, const jsi::Value* args, size_t argc) {
    if (!ctx || !ctx->runtime) return bridgeFailure(ctx, "bridge: invalid context");
    jsi::Runtime& rt = *ctx->runtime;
    jsi::Value* fnVal = bridgeHandleValue(ctx, fnHandle);
    if (!fnVal || !fnVal->isObject() || !fnVal->asObject(rt).isFunction(rt)) {
        return bridgeFailure(ctx, "bridge: value is not a function");
    }
    try {
        jsi::Value result = fnVal->asObject(rt).asFunction(rt).call(rt, args, argc);
        return bridgeAppendHandle(ctx, rt, std::move(result));
    } catch (const jsi::JSError& e) {
        return bridgeFailure(ctx, e.getMessage());
    } catch (const std::exception& e) {
        return bridgeFailure(ctx, e.what());
    }
}

} // namespace

int HermesBridge_callFunction(void* context, int fnHandle, int argsArrayHandle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return bridgeFailure(ctx, "bridge: invalid context");
    jsi::Runtime& rt = *ctx->runtime;
    std::vector<jsi::Value> args;
    jsi::Value* argsVal = bridgeHandleValue(ctx, argsArrayHandle);
    if (argsVal && argsVal->isObject()) {
        try {
            jsi::Object argsObj = argsVal->asObject(rt);
            if (argsObj.isArray(rt)) {
                jsi::Array argsArray = argsObj.getArray(rt);
                size_t count = argsArray.length(rt);
                args.reserve(count);
                for (size_t i = 0; i < count; i++) {
                    args.emplace_back(argsArray.getValueAtIndex(rt, i));
                }
            }
        } catch (const jsi::JSError& e) {
            return bridgeFailure(ctx, e.getMessage());
        } catch (const std::exception& e) {
            return bridgeFailure(ctx, e.what());
        }
    }
    return bridgeCallFunction(ctx, fnHandle, args.data(), args.size());
}

int HermesBridge_callFunctionWithArg(void* context, int fnHandle, int argHandle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return bridgeFailure(ctx, "bridge: invalid context");
    jsi::Value* arg = bridgeHandleValue(ctx, argHandle);
    if (!arg) return bridgeFailure(ctx, "bridge: invalid argument handle");
    jsi::Value args[1] = { jsi::Value(*ctx->runtime, *arg) };
    return bridgeCallFunction(ctx, fnHandle, args, 1);
}

int HermesBridge_callFunctionWithArgs2(void* context, int fnHandle, int arg1Handle, int arg2Handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return bridgeFailure(ctx, "bridge: invalid context");
    jsi::Value* arg1 = bridgeHandleValue(ctx, arg1Handle);
    jsi::Value* arg2 = bridgeHandleValue(ctx, arg2Handle);
    if (!arg1 || !arg2) return bridgeFailure(ctx, "bridge: invalid argument handle");
    jsi::Value args[2] = { jsi::Value(*ctx->runtime, *arg1), jsi::Value(*ctx->runtime, *arg2) };
    return bridgeCallFunction(ctx, fnHandle, args, 2);
}

int HermesBridge_isFunction(void* context, int handle) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime) return 0;
    jsi::Value* val = bridgeHandleValue(ctx, handle);
    if (!val || !val->isObject()) return 0;
    return val->asObject(*ctx->runtime).isFunction(*ctx->runtime) ? 1 : 0;
}

int HermesBridge_hasGlobalFunction(void* context, const char* name) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx || !ctx->runtime || !name) return 0;
    jsi::Runtime& rt = *ctx->runtime;
    try {
        jsi::Value val = rt.global().getProperty(rt, name);
        if (!val.isObject()) return 0;
        return val.asObject(rt).isFunction(rt) ? 1 : 0;
    } catch (const jsi::JSError&) {
        return 0;
    } catch (const std::exception&) {
        return 0;
    }
}

int HermesBridge_warmUpModule(void* context, const char* moduleId, const char* functionName) {
    ContextNative* ctx = asNativeContext(context);
    if (!ctx) return 0;
    char* errorOut = NULL;
    int result = HermesCore_warmUpModule(ctx, moduleId, functionName, &errorOut);
    if (errorOut) {
        // The hook itself threw: report it as a failure, not as "no such export".
        ctx->lastError = errorOut;
        snprintf(g_lastError, sizeof(g_lastError), "%s", errorOut);
        free(errorOut);
        return -1;
    }
    return result;
}

// ---------------------------------------------------------------------------
// CDP debugging. Bridges the C function-pointer API to the platform-neutral
// CDP session core (CdpSession.cpp).

namespace {

// The listener handle handed to the session core: the platform's opaque
// listener pointer plus the C callbacks to invoke for it.
struct IosCdpListener {
    void* listener;
    CdpMessageFn messageFn;
    CdpTasksEnqueuedFn tasksEnqueuedFn;
    CdpListenerDisposedFn disposedFn;
};

void iosCdpOnMessage(void* ref, const std::string& json) {
    IosCdpListener* listener = static_cast<IosCdpListener*>(ref);
    listener->messageFn(listener->listener, json.c_str());
}

void iosCdpOnTasksEnqueued(void* ref) {
    IosCdpListener* listener = static_cast<IosCdpListener*>(ref);
    listener->tasksEnqueuedFn(listener->listener);
}

void iosCdpDispose(void* ref) {
    IosCdpListener* listener = static_cast<IosCdpListener*>(ref);
    if (!listener) return;
    if (listener->disposedFn) {
        listener->disposedFn(listener->listener);
    }
    delete listener;
}

} // namespace

int HermesContext_cdpAttach(void* context, void* listener,
                            CdpMessageFn messageFn,
                            CdpTasksEnqueuedFn tasksEnqueuedFn,
                            CdpListenerDisposedFn disposedFn) {
    if (!context || !listener || !messageFn || !tasksEnqueuedFn) {
        snprintf(g_lastError, sizeof(g_lastError), "Invalid parameters");
        return 0;
    }

    IosCdpListener* iosListener = new IosCdpListener();
    iosListener->listener = listener;
    iosListener->messageFn = messageFn;
    iosListener->tasksEnqueuedFn = tasksEnqueuedFn;
    iosListener->disposedFn = disposedFn;

    zipline_cdp::Listener coreListener;
    coreListener.ref = iosListener;
    coreListener.onMessage = &iosCdpOnMessage;
    coreListener.onTasksEnqueued = &iosCdpOnTasksEnqueued;
    coreListener.dispose = &iosCdpDispose;
    // The core disposes iosListener if the session is not created (e.g.
    // builds without HERMES_ENABLE_DEBUGGER, or a duplicate attach).
    if (!zipline_cdp::attach(asNativeContext(context), coreListener)) {
        snprintf(g_lastError, sizeof(g_lastError),
                 "Engine does not support debugging (HERMES_ENABLE_DEBUGGER off?)");
        return 0;
    }
    return 1;
}

void HermesContext_cdpHandleCommand(void* context, const char* json) {
    if (!context || !json) return;
    zipline_cdp::handleCommand(asNativeContext(context), std::string(json));
}

void HermesContext_cdpDrainTasks(void* context) {
    if (!context) return;
    zipline_cdp::drainTasks(asNativeContext(context));
}

void HermesContext_cdpResetAgent(void* context) {
    if (!context) return;
    zipline_cdp::resetAgent(asNativeContext(context));
}
