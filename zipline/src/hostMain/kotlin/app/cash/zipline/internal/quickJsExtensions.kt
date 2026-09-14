package app.cash.zipline.internal

import app.cash.zipline.QuickJs
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

internal fun collectModuleDependencies(quickJs: QuickJs) {
  quickJs.evaluate(COLLECT_DEPENDENCIES_DEFINE_JS, "collectDependencies.js")
}

internal fun getModuleDependencies(quickJs: QuickJs): List<String> {
  val dependenciesString = quickJs.getGlobalThis(CURRENT_MODULE_DEPENDENCIES)
    ?: "[]" // If define is never called, dependencies is returned as null
  return Json.decodeFromString(dependenciesString)
}

internal fun QuickJs.getGlobalThis(key: String): String? {
  return evaluate("globalThis.$key", "getGlobalThis.js") as String?
}

internal fun getLog(quickJs: QuickJs): String? = quickJs.getGlobalThis("log")

public fun initModuleLoader(quickJs: QuickJs) {
  quickJs.evaluate(DEFINE_JS, "define.js")
  // Register __bridgeRegister JS function on globalThis (C side).
  // Runs before any modules load, so generated .kt files can call it.
  quickJs.bridgeInitAll()
}

internal fun loadJsModule(quickJs: QuickJs, script: String, id: String) {
  quickJs.evaluate("globalThis.$CURRENT_MODULE_ID = '$id';")
  quickJs.evaluate(script, id)
  // Publish the guest value ops (the plugin's __bridgeWarmUpHost2Js hook) while the module is
  // still current: require() needs globalThis.$CURRENT_MODULE_ID to resolve its exports.
  warmUpBridges(quickJs, id)
  quickJs.evaluate("delete globalThis.$CURRENT_MODULE_ID;")
  logBridgeDiagnostics(quickJs)
}

public fun loadJsModule(quickJs: QuickJs, id: String, bytecode: ByteArray) {
  quickJs.evaluate("globalThis.$CURRENT_MODULE_ID = '$id';")
  quickJs.execute(bytecode)
  // Publish the guest value ops (the plugin's __bridgeWarmUpHost2Js hook) while the module is
  // still current: require() needs globalThis.$CURRENT_MODULE_ID to resolve its exports.
  warmUpBridges(quickJs, id)
  quickJs.evaluate("delete globalThis.$CURRENT_MODULE_ID;")
  logBridgeDiagnostics(quickJs)
}

/** Print __define_log after each module — shows which modules have bridge FQNs and registration status. */
private fun logBridgeDiagnostics(quickJs: QuickJs) {
  val log = quickJs.evaluate("var _l = globalThis.__define_log; globalThis.__define_log = ''; _l") as? String
  if (!log.isNullOrEmpty()) println("BRIDGE_DEFINE: $log")
}

internal fun runApplication(quickJs: QuickJs, mainModuleId: String, mainFunction: String) {
  quickJs.evaluate(
    script = "require('$mainModuleId').$mainFunction()",
    fileName = "RunApplication.kt",
  )
}

private const val BRIDGE_WARM_UP_FUNCTION_NAME = "__bridgeWarmUpHost2Js"

/**
 * Calls the bridge plugin's `@JsExport __bridgeWarmUpHost2Js()` hook of the module that was just
 * defined, if it has one. The hook registers every `@WithHost2JSBridge` class of that module
 * (prototypes + runtime factories) with the host, which is what lets the host build host→JS
 * payload objects for classes the guest never constructs. Kotlin/JS itself cannot do this
 * eagerly — file-level property initializers run only when their file is first touched — so it
 * happens here, at module load, before the application runs and therefore before any host→JS
 * conversion.
 *
 * The hook lives wherever the plugin put it (any package of the module), so the export
 * namespace is searched for a function with that name; only plain namespace objects are
 * descended into, so no guest instance is touched.
 */
private fun warmUpBridges(quickJs: QuickJs, id: String) {
  val escapedId = id.replace("\\", "\\\\").replace("'", "\\'")
  quickJs.evaluate(
    script = """
      (function () {
        function find(ns, depth) {
          if (ns === null || typeof ns !== 'object' || depth > 8) return null;
          var direct = ns['$BRIDGE_WARM_UP_FUNCTION_NAME'];
          if (typeof direct === 'function') return direct;
          var keys = Object.keys(ns);
          for (var i = 0; i < keys.length; i++) {
            var value = ns[keys[i]];
            if (value !== null && typeof value === 'object'
                && Object.getPrototypeOf(value) === Object.prototype) {
              var found = find(value, depth + 1);
              if (found !== null) return found;
            }
          }
          return null;
        }
        var hook = find(require('$escapedId'), 0);
        if (hook) hook();
      })()
    """.trimIndent(),
    fileName = "bridgeWarmUp.js",
  )
}
