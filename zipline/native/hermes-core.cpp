#include "hermes-core.h"

#include <hermes/CompileJS.h>
#include <hermes/hermes.h>
#include <hermes/Public/RuntimeConfig.h>
#include <jsi/instrumentation.h>
#include <jsi/jsi.h>

#include <cstring>
#include <memory>
#include <optional>
#include <string>
#include <vector>

namespace jsi = facebook::jsi;

hermes::vm::GCConfig HermesCore_makeGCConfig() {
  return hermes::vm::GCConfig()
      .rebuild()
      .withInitHeapSize(32u << 20)
      .withMaxHeapSize(3u << 30)
      .withShouldRecordStats(true)
      .build();
}

hermes::vm::RuntimeConfig HermesCore_makeRuntimeConfig() {
  // Kotlin/JS stdlib uses Reflect.construct (in kotlin.js.createSubclass),
  // which requires the global Reflect object. Hermes only exposes Reflect
  // when ES6Proxy is enabled, so we re-enable it on top of the hardened
  // config used by Zipline.
  return facebook::hermes::hardenedHermesRuntimeConfig().rebuild()
      .withES6Proxy(true)
      .withGCConfig(HermesCore_makeGCConfig())
      .build();
}

// CDP debugging forces eager compilation: lazily-compiled functions have no
// code blocks yet, so breakpoints in them cannot bind (the lazy-expansion
// path in the debugger does not fire for source-compiled modules here).
hermes::vm::RuntimeConfig HermesCore_makeRuntimeConfig(bool forceEagerCompilation) {
  auto builder = facebook::hermes::hardenedHermesRuntimeConfig().rebuild();
  if (forceEagerCompilation) {
    builder.withCompilationMode(hermes::vm::CompilationMode::ForceEagerCompilation);
    // CDP frame evaluation (Debugger.evaluateOnCallFrame) parses the eval
    // expression at runtime; the hardened config disables eval entirely.
    builder.withEnableEval(true);
  }
  return builder
      .withES6Proxy(true)
      .withGCConfig(HermesCore_makeGCConfig())
      .build();
}

int HermesCore_initContext(ContextBase* ctx, bool forceEagerCompilation) {
  auto runtime = facebook::hermes::makeHermesRuntime(HermesCore_makeRuntimeConfig(forceEagerCompilation));
  if (!runtime) {
    return 0;
  }
  ctx->runtime = std::move(runtime);
  ctx->debugCompilation = forceEagerCompilation;

  // Install a global `gc()` helper mirroring the QuickJS JS_AddGlobalThisGc
  // shim. Hermes has no built-in JS-visible gc function in the runtime.
  jsi::Runtime& rt = *ctx->runtime;
  rt.global().setProperty(
      rt, "gc",
      jsi::Function::createFromHostFunction(
          rt, jsi::PropNameID::forUtf8(rt, "gc"), 0,
          [](jsi::Runtime& rt2, const jsi::Value&, const jsi::Value*, size_t) -> jsi::Value {
            rt2.instrumentation().collectGarbage("host_global_gc");
            return jsi::Value::undefined();
          }));
  return 1;
}

void HermesCore_releaseContext(ContextBase* ctx) {
  if (ctx) {
    ctx->runtime.reset();
  }
}

jsi::Value HermesCore_evaluateBytecode(ContextBase* ctx,
                                       const uint8_t* bytecode,
                                       size_t bytecodeSize,
                                       const std::string& sourceURL) {
  // Copy the bytecode into a vector so it remains valid for the lifetime
  // of the prepared JavaScript and its subsequent evaluation.
  std::vector<uint8_t> buf(bytecode, bytecode + bytecodeSize);
  auto bufPtr = std::make_shared<std::vector<uint8_t>>(std::move(buf));
  class VecBuffer : public jsi::Buffer {
   public:
    explicit VecBuffer(const std::shared_ptr<std::vector<uint8_t>>& v)
        : v_(v) {}
    size_t size() const override { return v_->size(); }
    const uint8_t* data() const override { return v_->data(); }
   private:
    std::shared_ptr<std::vector<uint8_t>> v_;
  };
  auto buffer = std::make_shared<VecBuffer>(bufPtr);
  auto prepared = ctx->runtime->prepareJavaScript(buffer, sourceURL);
  return ctx->runtime->evaluatePreparedJavaScript(prepared);
}

jsi::Value HermesCore_evaluateSource(ContextBase* ctx,
                                     const char* source,
                                     const std::string& sourceURL) {
#ifdef HERMESVM_LEAN
  throw std::runtime_error("evaluate() is not available in lean Hermes build");
#else
  class StringBuffer : public jsi::Buffer {
   public:
    explicit StringBuffer(std::string s) : s_(std::move(s)) {}
    size_t size() const override { return s_.size(); }
    const uint8_t* data() const override {
      return reinterpret_cast<const uint8_t*>(s_.data());
    }
   private:
    std::string s_;
  };
  auto buffer = std::make_shared<StringBuffer>(source ? source : "");
  return ctx->runtime->evaluateJavaScript(buffer, sourceURL);
#endif // HERMESVM_LEAN
}

extern "C" {

void* HermesCore_createContext(void* jniEnv) {
  ContextBase* ctx = new ContextBase();
  if (!HermesCore_initContext(ctx)) {
    delete ctx;
    return nullptr;
  }
  return static_cast<void*>(ctx);
}

void HermesCore_destroyContext(void* context) {
  if (context) {
    ContextBase* ctx = static_cast<ContextBase*>(context);
    delete ctx;
  }
}

void* HermesCore_getRuntime(void* context) {
  if (!context) return nullptr;
  ContextBase* ctx = static_cast<ContextBase*>(context);
  return ctx->runtime.get();
}

int HermesCore_compile(void* context,
                      const char* source,
                      const char* filename,
                      const char* sourceMap,
                      uint8_t** bytecodeOut,
                      size_t* bytecodeSizeOut,
                      char** errorOut) {
#ifdef HERMESVM_LEAN
  if (errorOut) *errorOut = strdup("compile() is not available in lean Hermes build");
  return 0;
#else
  ContextBase* ctx = static_cast<ContextBase*>(context);
  if (!ctx) {
    if (errorOut) *errorOut = strdup("Invalid context");
    return 0;
  }

  std::string src(source ? source : "");
  std::string fname(filename ? filename : "");
  std::optional<std::string> sourceMapBuf = std::nullopt;
  if (sourceMap) {
    sourceMapBuf = std::string(sourceMap);
  } else if (ctx->debugCompilation) {
    // CDP debugging needs full debug info even without a real source map:
    // hermes::compileJS only sets CompileFlags.debug (and disables the
    // optimizer, which would break debug info) when a source map is passed.
    // A placeholder map also lets the parser's sourceMappingURL magic comment
    // flow into the debug info, so DevTools can fetch the real map itself.
    sourceMapBuf = std::string(
        "{\"version\":3,\"sources\":[],\"names\":[],\"mappings\":\"\"}");
  }

  std::string bytecode;
  bool ok = false;
  try {
    ok = hermes::compileJS(
        src,
        fname,
        bytecode,
        // Optimize in production (source-mapped bytecode for stack traces
        // must not pay for unoptimized code); skip optimization only when
        // compiling inside a CDP context, where the optimizer's register
        // passes can break the debug info breakpoints resolve through.
        /*optimize=*/!ctx->debugCompilation,
        // Async break checks let the CDP debugger interrupt running JS
        // (Debugger.pause and other triggerInterrupt_TS users); only CDP
        // contexts pay for them.
        /*emitAsyncBreakCheck=*/ctx->debugCompilation,
        /*diagHandler=*/nullptr,
        sourceMapBuf,
        // Debug info (line tables, embedded map) only in CDP contexts —
        // never in production bytecode.
        /*debug=*/ctx->debugCompilation);
  } catch (const std::exception& e) {
    ctx->lastError = e.what();
    if (errorOut) *errorOut = strdup(e.what());
    return 0;
  }

  if (!ok) {
    ctx->lastError = "Failed to compile JavaScript";
    if (errorOut) *errorOut = strdup(ctx->lastError.c_str());
    return 0;
  }

  *bytecodeSizeOut = bytecode.size();
  *bytecodeOut = new uint8_t[*bytecodeSizeOut];
  memcpy(*bytecodeOut, bytecode.data(), *bytecodeSizeOut);

  return 1;
#endif // HERMESVM_LEAN
}



int HermesCore_hasGlobalObject(void* context, const char* name) {
  ContextBase* ctx = static_cast<ContextBase*>(context);
  if (!ctx || !ctx->runtime || !name) {
    return 0;
  }
  try {
    jsi::Runtime& rt = *ctx->runtime;
    return rt.global().getProperty(rt, name).isObject() ? 1 : 0;
  } catch (...) {
    return 0;
  }
}

int HermesCore_getGlobalProperty(void* context, const char* name, char** valueOut, char** errorOut) {
  ContextBase* ctx = static_cast<ContextBase*>(context);
  if (!ctx || !ctx->runtime) {
    if (errorOut) *errorOut = strdup("Invalid context");
    return 0;
  }

  try {
    jsi::Runtime& rt = *ctx->runtime;
    jsi::Value val = rt.global().getProperty(rt, name ? name : "");
    if (val.isString()) {
      std::string str = val.asString(rt).utf8(rt);
      *valueOut = strdup(str.c_str());
      return 1;
    }
    return 0;
  } catch (const std::exception& e) {
    ctx->lastError = e.what();
    if (errorOut) *errorOut = strdup(e.what());
    return 0;
  }
}

int HermesCore_setGlobalProperty(void* context, const char* name, const char* value, char** errorOut) {
  ContextBase* ctx = static_cast<ContextBase*>(context);
  if (!ctx || !ctx->runtime) {
    if (errorOut) *errorOut = strdup("Invalid context");
    return 0;
  }

  try {
    jsi::Runtime& rt = *ctx->runtime;
    rt.global().setProperty(rt, name ? name : "",
      jsi::String::createFromUtf8(rt, value ? value : ""));
    return 1;
  } catch (const std::exception& e) {
    ctx->lastError = e.what();
    if (errorOut) *errorOut = strdup(e.what());
    return 0;
  }
}

int HermesCore_deleteGlobalProperty(void* context, const char* name, char** errorOut) {
  ContextBase* ctx = static_cast<ContextBase*>(context);
  if (!ctx || !ctx->runtime) {
    if (errorOut) *errorOut = strdup("Invalid context");
    return 0;
  }

  try {
    jsi::Runtime& rt = *ctx->runtime;
    rt.global().setProperty(rt, name ? name : "", jsi::Value::undefined());
    return 1;
  } catch (const std::exception& e) {
    ctx->lastError = e.what();
    if (errorOut) *errorOut = strdup(e.what());
    return 0;
  }
}

int HermesCore_callGlobalMethod(void* context, const char* objectName, const char* methodName, char** errorOut) {
  ContextBase* ctx = static_cast<ContextBase*>(context);
  if (!ctx || !ctx->runtime) {
    if (errorOut) *errorOut = strdup("Invalid context");
    return 0;
  }

  try {
    jsi::Runtime& rt = *ctx->runtime;
    std::string objName(objectName ? objectName : "");
    std::string mtdName(methodName ? methodName : "");

    jsi::Value requireVal = rt.global().getProperty(rt, "require");
    if (!requireVal.isObject() || !requireVal.asObject(rt).isFunction(rt)) {
      ctx->lastError = "require not found";
      if (errorOut) *errorOut = strdup(ctx->lastError.c_str());
      return 0;
    }

    jsi::Value exports = requireVal.asObject(rt).asFunction(rt).call(
      rt, jsi::String::createFromUtf8(rt, objName), 1);

    if (!exports.isObject()) {
      ctx->lastError = "module exports not an object";
      if (errorOut) *errorOut = strdup(ctx->lastError.c_str());
      return 0;
    }

    jsi::Object exportsObj = exports.asObject(rt);
    jsi::Value method = exportsObj.getProperty(rt, mtdName.c_str());
    if (!method.isObject() || !method.asObject(rt).isFunction(rt)) {
      ctx->lastError = "method not found";
      if (errorOut) *errorOut = strdup(ctx->lastError.c_str());
      return 0;
    }

    method.asObject(rt).asFunction(rt).call(rt, nullptr, 0);
    return 1;
  } catch (const std::exception& e) {
    ctx->lastError = e.what();
    if (errorOut) *errorOut = strdup(e.what());
    return 0;
  }
}

int HermesCore_callGlobalFunctionWithStringArg(void* context, const char* functionName, const char* arg, char** resultOut, char** errorOut) {
  ContextBase* ctx = static_cast<ContextBase*>(context);
  if (!ctx || !ctx->runtime) {
    if (errorOut) *errorOut = strdup("Invalid context");
    return 0;
  }

  try {
    jsi::Runtime& rt = *ctx->runtime;
    std::string fnName(functionName ? functionName : "");
    std::string argStr(arg ? arg : "");

    jsi::Value fnVal = rt.global().getProperty(rt, fnName.c_str());
    if (!fnVal.isObject() || !fnVal.asObject(rt).isFunction(rt)) {
      ctx->lastError = "function not found";
      if (errorOut) *errorOut = strdup(ctx->lastError.c_str());
      return 0;
    }

    jsi::Value result = fnVal.asObject(rt).asFunction(rt).call(
      rt, jsi::String::createFromUtf8(rt, argStr), 1);

    if (resultOut && result.isString()) {
      std::string str = result.asString(rt).utf8(rt);
      *resultOut = strdup(str.c_str());
    }
    return 1;
  } catch (const std::exception& e) {
    ctx->lastError = e.what();
    if (errorOut) *errorOut = strdup(e.what());
    return 0;
  }
}

int HermesCore_callRequireMethod(void* context, const char* moduleId, const char* methodName, char** errorOut) {
  ContextBase* ctx = static_cast<ContextBase*>(context);
  if (!ctx || !ctx->runtime) {
    if (errorOut) *errorOut = strdup("Invalid context");
    return 0;
  }

  try {
    jsi::Runtime& rt = *ctx->runtime;
    std::string modId(moduleId ? moduleId : "");
    std::string mtdName(methodName ? methodName : "");

    jsi::Value requireVal = rt.global().getProperty(rt, "require");
    if (!requireVal.isObject() || !requireVal.asObject(rt).isFunction(rt)) {
      ctx->lastError = "require not found";
      if (errorOut) *errorOut = strdup(ctx->lastError.c_str());
      return 0;
    }

    jsi::Value exports = requireVal.asObject(rt).asFunction(rt).call(
      rt, jsi::String::createFromUtf8(rt, modId), 1);

    if (exports.isUndefined()) {
      ctx->lastError = "module exports undefined";
      if (errorOut) *errorOut = strdup(ctx->lastError.c_str());
      return 0;
    }

    if (!exports.isObject()) {
      ctx->lastError = "module exports not an object";
      if (errorOut) *errorOut = strdup(ctx->lastError.c_str());
      return 0;
    }

    jsi::Value current = jsi::Value(rt, exports.asObject(rt));
    size_t start = 0;
    for (size_t i = 0; i <= mtdName.length(); i++) {
      if (i == mtdName.length() || mtdName[i] == '.') {
        std::string part = mtdName.substr(start, i - start);
        if (!current.isObject()) {
          ctx->lastError = "property path traversal failed";
          if (errorOut) *errorOut = strdup(ctx->lastError.c_str());
          return 0;
        }
        current = current.asObject(rt).getProperty(rt, part.c_str());
        if (i == mtdName.length()) {
          if (!current.isObject() || !current.asObject(rt).isFunction(rt)) {
            ctx->lastError = "method not found";
            if (errorOut) *errorOut = strdup(ctx->lastError.c_str());
            return 0;
          }
          current.asObject(rt).asFunction(rt).call(rt, nullptr, 0);
          return 1;
        }
        start = i + 1;
      }
    }

    ctx->lastError = "method not called";
    if (errorOut) *errorOut = strdup(ctx->lastError.c_str());
    return 0;
  } catch (const std::exception& e) {
    ctx->lastError = e.what();
    if (errorOut) *errorOut = strdup(e.what());
    return 0;
  }
}

namespace {

/**
 * Depth-first search for a named function inside a module's export namespace, bounded so a large
 * namespace cannot make module loading expensive. Kotlin/JS exports a module's top-level
 * declarations under their package path, so the bridge plugin's module-load hook sits one or
 * more namespace objects below the exports root.
 */
bool findExportedFunction(
    jsi::Runtime& rt, const jsi::Value& node, const char* name, int depth,
    std::optional<jsi::Function>& out) {
  constexpr int kMaxDepth = 8;
  if (depth > kMaxDepth || !node.isObject()) return false;
  jsi::Object obj = node.asObject(rt);
  // Only plain namespace objects are descended into: a function is a leaf, and an array's own
  // properties are its indices.
  if (obj.isFunction(rt) || obj.isArray(rt)) return false;

  jsi::Value direct = obj.getProperty(rt, name);
  if (direct.isObject() && direct.asObject(rt).isFunction(rt)) {
    out = direct.asObject(rt).asFunction(rt);
    return true;
  }

  jsi::Array names = obj.getPropertyNames(rt);
  size_t count = names.length(rt);
  for (size_t i = 0; i < count; i++) {
    jsi::Value key = names.getValueAtIndex(rt, i);
    if (!key.isString()) continue;
    std::string keyName = key.asString(rt).utf8(rt);
    jsi::Value child = obj.getProperty(rt, keyName.c_str());
    if (!child.isObject()) continue;
    if (findExportedFunction(rt, child, name, depth + 1, out)) return true;
  }
  return false;
}

}  // namespace

int HermesCore_warmUpModule(void* context, const char* moduleId, const char* functionName, char** errorOut) {
  ContextBase* ctx = static_cast<ContextBase*>(context);
  if (!ctx || !ctx->runtime) {
    if (errorOut) *errorOut = strdup("Invalid context");
    return 0;
  }

  try {
    jsi::Runtime& rt = *ctx->runtime;

    jsi::Value requireVal = rt.global().getProperty(rt, "require");
    if (!requireVal.isObject() || !requireVal.asObject(rt).isFunction(rt)) {
      return 0;
    }

    jsi::Value exports = requireVal.asObject(rt).asFunction(rt).call(
      rt, jsi::String::createFromUtf8(rt, moduleId ? moduleId : ""), 1);
    if (!exports.isObject()) {
      return 0;
    }

    std::string name(functionName ? functionName : "");
    jsi::Value hook = exports.asObject(rt).getProperty(rt, name.c_str());
    if (!hook.isObject() || !hook.asObject(rt).isFunction(rt)) {
      // Kotlin/JS exports a module's top-level declarations under their package path, so the
      // hook is usually one or more namespace objects deep. Search for it rather than making the
      // generator depend on the export layout.
      std::optional<jsi::Function> found;
      if (!findExportedFunction(rt, exports, name.c_str(), 0, found)) return 0;
      found->call(rt, nullptr, 0);
      return 1;
    }

    hook.asObject(rt).asFunction(rt).call(rt, nullptr, 0);
    return 1;
  } catch (const std::exception& e) {
    ctx->lastError = e.what();
    if (errorOut) *errorOut = strdup(e.what());
    return 0;
  }
}

int HermesCore_installModuleLoader(void* context, char** errorOut) {
  ContextBase* ctx = static_cast<ContextBase*>(context);
  if (!ctx || !ctx->runtime) {
    if (errorOut) *errorOut = strdup("Invalid context");
    return 0;
  }

  try {
    jsi::Runtime& rt = *ctx->runtime;

    jsi::Object idToExportsObj(rt);
    rt.global().setProperty(rt, "app_cash_zipline_idToExports", idToExportsObj);

    auto requireFn = jsi::Function::createFromHostFunction(
      rt,
      jsi::PropNameID::forUtf8(rt, "require"),
      1,
      [&rt](jsi::Runtime& runtime, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
        if (count < 1 || !args[0].isString()) {
          throw jsi::JSError(runtime, "require expects a string id");
        }
        std::string modId = args[0].asString(runtime).utf8(runtime);

        jsi::Object idToExports = runtime.global().getProperty(runtime, "app_cash_zipline_idToExports").asObject(runtime);
        jsi::Value exports = idToExports.getProperty(runtime, modId.c_str());

        if (exports.isUndefined()) {
          throw jsi::JSError(runtime, "\"" + modId + "\" not found");
        }
        return exports;
      });
    rt.global().setProperty(rt, "require", std::move(requireFn));

    auto defineFn = jsi::Function::createFromHostFunction(
      rt,
      jsi::PropNameID::forUtf8(rt, "define"),
      0,
      [&rt](jsi::Runtime& runtime, const jsi::Value& thisVal, const jsi::Value* args, size_t count) -> jsi::Value {
        std::string modId;
        size_t factoryIndex = count - 1;
        size_t depsIndex = SIZE_MAX;

        if (count >= 2) {
          if (args[count - 2].isObject() && args[count - 2].asObject(runtime).isArray(runtime)) {
            depsIndex = count - 2;
          } else if (args[count - 2].isString()) {
            modId = args[count - 2].asString(runtime).utf8(runtime);
          }
        }

        // define(id, deps, factory): the id is the leading string argument.
        if (modId.empty() && count >= 1 && args[0].isString()) {
          modId = args[0].asString(runtime).utf8(runtime);
        }

        if (modId.empty()) {
          jsi::Value currentModId = runtime.global().getProperty(runtime, "app_cash_zipline_currentModuleId");
          if (currentModId.isString()) {
            modId = currentModId.asString(runtime).utf8(runtime);
          }
        }

        if (count == 0) {
          throw jsi::JSError(runtime, "define requires at least a factory function");
        }

        if (!args[factoryIndex].isObject() || !args[factoryIndex].asObject(runtime).isFunction(runtime)) {
          throw jsi::JSError(runtime, "define last argument must be a factory function");
        }
        jsi::Function factoryFn = args[factoryIndex].asObject(runtime).asFunction(runtime);

        size_t depCount = 0;
        std::vector<unsigned char> depStorage;
        jsi::Value* depArgs = nullptr;
        // The exports object created for an "exports" dependency; the factory
        // may populate it instead of returning a value.
        std::optional<jsi::Object> factoryExports;

        if (depsIndex != SIZE_MAX) {
          jsi::Array deps = args[depsIndex].asObject(runtime).asArray(runtime);
          depCount = deps.length(runtime);
          depStorage.resize(depCount * sizeof(jsi::Value));
          depArgs = reinterpret_cast<jsi::Value*>(depStorage.data());
          jsi::Object idToExports = runtime.global().getProperty(runtime, "app_cash_zipline_idToExports").asObject(runtime);

          for (size_t i = 0; i < depCount; i++) {
            jsi::Value dep = deps.getValueAtIndex(runtime, i);
            if (!dep.isString()) {
              new(&depArgs[i]) jsi::Value(jsi::Value::undefined());
              continue;
            }
            std::string depId = dep.asString(runtime).utf8(runtime);

            if (depId == "exports") {
              factoryExports.emplace(runtime);
              new(&depArgs[i]) jsi::Value(runtime, *factoryExports);
            } else if (depId == "require") {
              new(&depArgs[i]) jsi::Value(runtime.global().getProperty(runtime, "require"));
            } else {
              jsi::Value depMod = idToExports.getProperty(runtime, depId.c_str());
              if (depMod.isUndefined()) {
                throw jsi::JSError(runtime, "\"" + depId + "\" not found");
              }
              new(&depArgs[i]) jsi::Value(std::move(depMod));
            }
          }
        }

        jsi::Value result = runtime.call(factoryFn, jsi::Value::undefined(), depArgs, depCount);

        for (size_t i = 0; i < depCount; i++) {
          depArgs[i].~Value();
        }

        if (!modId.empty()) {
          jsi::Object idToExports = runtime.global().getProperty(runtime, "app_cash_zipline_idToExports").asObject(runtime);
          if (result.isObject()) {
            idToExports.setProperty(runtime, modId.c_str(), result);
          } else if (factoryExports.has_value()) {
            // CommonJS style: the factory populated the exports object it was
            // given instead of returning a value.
            idToExports.setProperty(runtime, modId.c_str(), *factoryExports);
          } else {
            idToExports.setProperty(runtime, modId.c_str(), jsi::Object(runtime));
          }
        }

        return jsi::Value::undefined();
      });
    rt.global().setProperty(rt, "define", std::move(defineFn));

    jsi::Object defineObj = rt.global().getProperty(rt, "define").asObject(rt);
    jsi::Object amdObj(rt);
    defineObj.setProperty(rt, "amd", amdObj);

    return 1;
  } catch (const std::exception& e) {
    ctx->lastError = e.what();
    if (errorOut) *errorOut = strdup(e.what());
    return 0;
  }
}

void HermesCore_setMemoryLimit(void* context, int64_t limitBytes) {
}

void HermesCore_setGcThreshold(void* context, int64_t thresholdBytes) {
}

void HermesCore_setMaxStackSize(void* context, int64_t maxSizeBytes) {
}

void HermesCore_gc(void* context) {
  ContextBase* ctx = static_cast<ContextBase*>(context);
  if (ctx && ctx->runtime) {
    ctx->runtime->instrumentation().collectGarbage("hermes_core_gc");
  }
}

int HermesCore_getMemoryUsage(void* context, HermesCoreMemoryUsage* usageOut) {
  ContextBase* ctx = static_cast<ContextBase*>(context);
  if (!ctx || !ctx->runtime || !usageOut) {
    return 0;
  }
  const auto info = ctx->runtime->instrumentation().getHeapInfo(true);
  auto get = [&info](const char* key) -> int64_t {
    const auto it = info.find(key);
    return it != info.end() ? it->second : 0;
  };
  usageOut->heapSize = get("hermes_heapSize");
  usageOut->allocatedBytes = get("hermes_allocatedBytes");
  usageOut->totalAllocatedBytes = get("hermes_totalAllocatedBytes");
  usageOut->va = get("hermes_va");
  usageOut->externalBytes = get("hermes_externalBytes");
  usageOut->mallocSizeEstimate = get("hermes_mallocSizeEstimate");
  usageOut->peakAllocatedBytes = get("hermes_peakAllocatedBytes");
  usageOut->peakLiveAfterGC = get("hermes_peakLiveAfterGC");
  usageOut->numCollections = get("hermes_numCollections");
  usageOut->numMarkStackOverflows = get("hermes_numMarkStackOverflows");
  return 1;
}

const char* HermesCore_getVersion(void) {
  return "1.0.0-hermes-core";
}

const char* HermesCore_getLastError(void* context) {
  ContextBase* ctx = static_cast<ContextBase*>(context);
  if (!ctx) {
    return "Invalid context";
  }
  return ctx->lastError.c_str();
}

} // extern "C"
