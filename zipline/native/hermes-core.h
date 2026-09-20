#ifndef HERMES_CORE_H
#define HERMES_CORE_H

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus

#include <hermes/hermes.h>
#include <hermes/Public/GCConfig.h>
#include <hermes/Public/RuntimeConfig.h>

#include "ContextBase.h"

#include <memory>
#include <string>

// Initialize/release a caller-allocated context (returns 1 on success).
// forceEagerCompilation disables lazy compilation (needed for CDP breakpoints
// to bind in runtime-compiled source).
int HermesCore_initContext(ContextBase* ctx, bool forceEagerCompilation = false);
void HermesCore_releaseContext(ContextBase* ctx);

// The single source of the Zipline runtime configuration: hardened Hermes
// config with ES6Proxy re-enabled (Kotlin/JS stdlib needs Reflect.construct)
// and the GC settings from HermesCore_makeGCConfig.
// Platform layers chain .rebuild() to add their own knobs.
hermes::vm::RuntimeConfig HermesCore_makeRuntimeConfig();
hermes::vm::RuntimeConfig HermesCore_makeRuntimeConfig(bool forceEagerCompilation);

// The shared GC configuration: 32 MB initial heap, 3 GB max heap, stats
// recorded (heap stats feed memoryUsage()).
hermes::vm::GCConfig HermesCore_makeGCConfig();

// Evaluate precompiled Hermes bytecode and return the result value.
// Throws jsi::JSError on script errors, std::exception on engine errors.
facebook::jsi::Value HermesCore_evaluateBytecode(ContextBase* ctx,
                                                 const uint8_t* bytecode,
                                                 size_t bytecodeSize,
                                                 const std::string& sourceURL);

// Compile and evaluate JavaScript source directly in the runtime. Unlike
// compile()+evaluateBytecode(), the in-memory debug info (scoping table,
// sourceMappingURL magic comment) survives, so CDP frame eval works.
// Throws jsi::JSError on script errors, std::exception on engine errors.
facebook::jsi::Value HermesCore_evaluateSource(ContextBase* ctx,
                                               const char* source,
                                               const std::string& sourceURL);

extern "C" {
#endif

// Create a new Hermes Context (pass JNIEnv* cast to void* for JNI-based platforms)
void* HermesCore_createContext(void* jniEnv);

// Destroy a Hermes Context created by HermesCore_createContext
void HermesCore_destroyContext(void* context);

// Returns 1 if a global object property called name exists and is an object.
int HermesCore_hasGlobalObject(void* context, const char* name);

// Get the jsi::Runtime from a context (for iOS wrapper layer)
void* HermesCore_getRuntime(void* context);

// Compile JavaScript to bytecode (sourceMap is optional, pass nullptr if not needed)
// On success, allocates *bytecodeOut which caller must delete[]
int HermesCore_compile(void* context,
                      const char* source,
                      const char* filename,
                      const char* sourceMap,
                      uint8_t** bytecodeOut,
                      size_t* bytecodeSizeOut,
                      char** errorOut);

// Global property access (all return 1 on success, 0 on failure)
int HermesCore_getGlobalProperty(void* context, const char* name, char** valueOut, char** errorOut);
int HermesCore_setGlobalProperty(void* context, const char* name, const char* value, char** errorOut);
int HermesCore_deleteGlobalProperty(void* context, const char* name, char** errorOut);

// Function calls (all return 1 on success, 0 on failure)
int HermesCore_callGlobalMethod(void* context, const char* objectName, const char* methodName, char** errorOut);
int HermesCore_callGlobalFunctionWithStringArg(void* context, const char* functionName, const char* arg, char** resultOut, char** errorOut);

// Module system
// moduleId is the module to require
// methodName is a dotted path to method on module exports (e.g., "io.clive.wb.services.wbRootMain")
int HermesCore_callRequireMethod(void* context, const char* moduleId, const char* methodName, char** errorOut);

// Install AMD-style module loader (define/require)
int HermesCore_installModuleLoader(void* context, char** errorOut);

// Calls `require(moduleId)[functionName]()` when the module exports that function, and does
// nothing when it does not (a module compiled without the bridge plugin has no hook, which is not
// an error). Returns 1 when the hook was called, 0 when there was no such export, no such module,
// or the require machinery is absent. A hook that throws is not swallowed: errorOut carries the
// message and the caller reports it.
int HermesCore_warmUpModule(void* context, const char* moduleId, const char* functionName, char** errorOut);

// Memory management (currently stubs)
void HermesCore_setMemoryLimit(void* context, int64_t limitBytes);
void HermesCore_setGcThreshold(void* context, int64_t thresholdBytes);
void HermesCore_setMaxStackSize(void* context, int64_t maxSizeBytes);
void HermesCore_gc(void* context);

// Memory usage stats, mirroring facebook::jsi::Instrumentation::getHeapInfo
// (the "hermes_*" keys returned by HermesRuntime).
typedef struct HermesCoreMemoryUsage {
  int64_t heapSize;             // hermes_heapSize: bytes reserved by the GC heap
  int64_t allocatedBytes;       // hermes_allocatedBytes: live JS heap objects
  int64_t totalAllocatedBytes;  // hermes_totalAllocatedBytes: cumulative allocations
  int64_t va;                   // hermes_va: virtual address space of the heap
  int64_t externalBytes;        // hermes_externalBytes: memory retained outside the heap
  int64_t mallocSizeEstimate;   // hermes_mallocSizeEstimate (expensive to compute)
  int64_t peakAllocatedBytes;   // hermes_peakAllocatedBytes
  int64_t peakLiveAfterGC;      // hermes_peakLiveAfterGC
  int64_t numCollections;       // hermes_numCollections
  int64_t numMarkStackOverflows;// hermes_numMarkStackOverflows
} HermesCoreMemoryUsage;

// Returns 1 on success, 0 on failure.
int HermesCore_getMemoryUsage(void* context, HermesCoreMemoryUsage* usageOut);

// Version string
const char* HermesCore_getVersion(void);

// Get last error message
const char* HermesCore_getLastError(void* context);

#ifdef __cplusplus
}
#endif

#endif // HERMES_CORE_H
