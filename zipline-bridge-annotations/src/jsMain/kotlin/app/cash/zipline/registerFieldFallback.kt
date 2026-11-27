package app.cash.zipline

import kotlin.js.JsClass

/**
 * Installs a prototype accessor for [current] that reads the pre-rename property [legacy] when the
 * instance has no own property called [current].
 *
 * The host builds the JS counterpart of a `@WithHost2JSBridge` payload itself: an object whose
 * prototype is the guest class prototype, with one own data property per field under its current
 * name. A host shipped before a field rename writes the old name instead, and the guest reading the
 * current name then sees `undefined`, because the value sits under the name the new guest no longer
 * knows. This accessor closes that gap: a field renamed with `@HostName` stays readable from a host
 * of any earlier vintage, in both directions.
 *
 * Only own properties are consulted on both names, so the accessor cannot loop through the other
 * alias installed by [registerFieldAlias] on the same prototype; an instance that carries neither
 * name reads `undefined`, as it would without the accessor.
 *
 * This lives in `zipline-bridge-annotations` for the same reason [registerFieldAlias] does: the
 * bridge plugin resolves it while compiling each bridged module, so the symbol has to be on that
 * module's own compile classpath.
 */
public fun registerFieldFallback(jsClass: JsClass<*>, current: String, legacy: String) {
  js(
    """
    (function () {
      var proto = jsClass.prototype;
      if (!proto) return;
      if (Object.getOwnPropertyDescriptor(proto, current)) return;
      function has(instance, name) {
        return Object.getOwnPropertyDescriptor(instance, name) !== undefined;
      }
      function read(instance, name) {
        var descriptor = Object.getOwnPropertyDescriptor(instance, name);
        if (!descriptor) return undefined;
        return descriptor.get ? descriptor.get.call(instance) : descriptor.value;
      }
      Object.defineProperty(proto, current, {
        get: function () {
          return has(this, current) ? read(this, current) : read(this, legacy);
        },
        set: function (value) {
          Object.defineProperty(this, current, {
            value: value,
            writable: true,
            enumerable: true,
            configurable: true
          });
        },
        enumerable: false,
        configurable: true
      });
    })();
    """,
  )
}
