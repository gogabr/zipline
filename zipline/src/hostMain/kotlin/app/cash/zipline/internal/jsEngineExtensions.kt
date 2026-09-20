package app.cash.zipline.internal

import app.cash.zipline.JsEngine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * The export the bridge plugin adds to every guest module compiled with it. It registers the
 * module's host->JS class prototypes and the runtime factories the host needs to build Kotlin/JS
 * values, which Kotlin/JS cannot do on its own: file-level initializers only run when some
 * declaration of the file is first touched, and the host needs them before the application runs.
 */
internal const val BRIDGE_WARM_UP_FUNCTION_NAME = "__bridgeWarmUpHost2Js"

private fun warmUpBridges(jsEngine: JsEngine, id: String) {
  // A module compiled without the bridge plugin has no hook; that is not an error.
  jsEngine.warmUpModule(id, BRIDGE_WARM_UP_FUNCTION_NAME)
}

internal fun collectModuleDependencies(jsEngine: JsEngine) {
  jsEngine.evaluate(COLLECT_DEPENDENCIES_DEFINE_JS, "collectDependencies.js")
}

internal fun getModuleDependencies(jsEngine: JsEngine): List<String> {
  val dependenciesString = jsEngine.getGlobalProperty(CURRENT_MODULE_DEPENDENCIES)
    ?: "[]" // If define is never called, dependencies is returned as null
  return Json.decodeFromString(dependenciesString)
}

internal fun getLog(jsEngine: JsEngine): String? = jsEngine.getGlobalProperty("log")

public fun initModuleLoader(jsEngine: JsEngine) {
  jsEngine.installModuleLoader()
  // Register __bridgeRegister JS function on globalThis (C side).
  // Runs before any modules load, so generated .kt files can call it.
  jsEngine.bridgeInitAll()
}

internal fun loadJsModule(jsEngine: JsEngine, script: String, id: String) {
  jsEngine.evaluate("globalThis.$CURRENT_MODULE_ID = '$id';")
  jsEngine.evaluate(script, id)
  // The module is defined (and therefore require-able) now, and before the current module id is
  // dropped: the guest's hook registers this module's host->JS prototypes.
  warmUpBridges(jsEngine, id)
  jsEngine.evaluate("delete globalThis.$CURRENT_MODULE_ID;")
}

public fun loadJsModule(jsEngine: JsEngine, id: String, bytecode: ByteArray) {
  jsEngine.setGlobalProperty(CURRENT_MODULE_ID, id)
  jsEngine.execute(bytecode, id)
  warmUpBridges(jsEngine, id)
  jsEngine.deleteGlobalProperty(CURRENT_MODULE_ID)
}

internal fun runApplication(jsEngine: JsEngine, mainModuleId: String, mainFunction: String) {
  jsEngine.callRequireMethod(mainModuleId, mainFunction)
}
