package app.cash.zipline

import kotlin.js.JsClass
import kotlin.js.JsName

/**
 * Registers the guest class [jsClass] with the host under [fqn], so the host can attach its
 * converter there and build objects of the class later. The bridge plugin calls this from the
 * class's companion initializer and from the module-load hook.
 *
 * A host installs `__bridgeRegister` before it loads guest modules. Without one — a bare
 * Kotlin/JS runtime, such as a unit test, or any JS consumer of a bridged module — there is
 * nothing to register with, and this is a no-op rather than a `ReferenceError` at class
 * initialization: registration only matters when a host is there to receive the objects.
 *
 * This lives in `zipline-bridge-annotations` because the plugin resolves it while compiling each
 * bridged module, so the symbol has to be on that module's own compile classpath. The annotations
 * are the one artifact every bridged module declares; the runtime module is not (it reaches them
 * transitively, as an implementation dependency, which Kotlin/JS keeps off a consumer's compile
 * classpath).
 */
public fun registerBridge(fqn: String, jsClass: JsClass<*>) {
  // globalThis explicitly: the host installs __bridgeRegister as a property of the global object,
  // and a bare reference can be shadowed by this module's own declaration of the same name.
  if (js("typeof globalThis.__bridgeRegister === 'function'") as Boolean) {
    __bridgeRegister(fqn, jsClass)
  }
}

// @JsName pins the JS name: an internal/private declaration is mangled otherwise (see the
// plugin's own external declaration, which carries the same annotation).
@JsName("__bridgeRegister")
private external fun __bridgeRegister(fqn: String, jsClass: JsClass<*>)
