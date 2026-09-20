package app.cash.zipline

import kotlin.js.JsClass

/**
 * Installs an accessor alias for a renamed bridged field: reading or writing `alias` on an instance
 * of [jsClass] reads or writes [target].
 *
 * The guest bundle and the host binary of a bridged class are deployed independently, so after a
 * field rename one side keeps the old JS property name. The host reads an already-shipped guest's
 * fields by name, and that name is baked into the host's generated C when the host was built — an
 * accessor on the guest class prototype keeps the old name readable for any already-shipped host,
 * of any Zipline vintage, with no version negotiation and no runtime metadata.
 *
 * An accessor rather than a copied value, so the two names stay in sync for `var` fields; the
 * existing own-property descriptor is never replaced, so a real property of that name (and a second
 * install after a hot reload) is left alone.
 *
 * This lives in `zipline-bridge-annotations` for the same reason [registerBridge] does: the bridge
 * plugin resolves it while compiling each bridged module, so the symbol has to be on that module's
 * own compile classpath.
 */
public fun registerFieldAlias(jsClass: JsClass<*>, alias: String, target: String) {
  js(
    """
    (function () {
      var proto = jsClass.prototype;
      if (!proto) return;
      if (Object.getOwnPropertyDescriptor(proto, alias)) return;
      Object.defineProperty(proto, alias, {
        get: function () { return this[target]; },
        set: function (value) { this[target] = value; },
        enumerable: true,
        configurable: true
      });
    })();
    """,
  )
}
