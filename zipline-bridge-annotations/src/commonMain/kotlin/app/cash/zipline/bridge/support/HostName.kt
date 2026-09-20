package app.cash.zipline.bridge.support

/**
 * The JS property name this property had in older versions of the guest bundle and the host
 * binary.
 *
 * A bridged class is compiled from the same source twice — into the guest (Kotlin/JS) bundle and
 * into the host (JNI or Kotlin/Native) — and the two artifacts are deployed independently. When a
 * field is renamed the two sides disagree on its JS property name, so both directions of the
 * bridge stop finding the value.
 *
 * Annotating the renamed property with the name it used to carry keeps both names readable:
 *
 * - an already-shipped host keeps reading the old name, because the guest installs an accessor
 *   alias for it on the class prototype,
 * - an already-shipped guest keeps reading the old name, because the host defines the same alias
 *   on the payload objects it builds, and falls back to the old name when reading,
 * - the host's own Kotlin property, JVM/Kotlin-Native field name and constructor parameter are
 *   untouched.
 *
 * Only the JS name is aliased: the Kotlin declaration keeps the new name, so the parameter list,
 * `copy`/`equals`/`componentN` and the host's own field names stay as they are.
 *
 * An empty value is a compile error; a value equal to the property's current name is a no-op.
 *
 * @param value the JS property name this property carried before it was renamed.
 */
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.PROPERTY)
public annotation class HostName(
  val value: String,
)
