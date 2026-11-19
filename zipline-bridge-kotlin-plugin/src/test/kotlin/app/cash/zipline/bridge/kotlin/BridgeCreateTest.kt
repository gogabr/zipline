package app.cash.zipline.bridge.kotlin

import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import java.lang.reflect.Method
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.junit.Test

/**
 * The construction factory a bridged class is given: flag words, then the constructor's own
 * parameters, and the body runs the class's real default expressions.
 */
@OptIn(ExperimentalCompilerApi::class)
class BridgeCreateTest {

  /**
   * The signature is the contract with the generated bridges, so it is read off the compiled class
   * rather than asserted against the generator's own text: one `int` per flag word, then every
   * constructor parameter with the type written on it -- a reference type that declares a default
   * widened so `null` can carry an absent argument, a primitive left alone because for it the flag
   * bit carries the absence.
   */
  @Test
  fun `bridgeCreate takes a flag word then the constructor parameters`() {
    val result = compile(
      SourceFile.kotlin(
        "Bridged.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Bridged(val a: Int, val name: String = "dflt", val flag: Boolean = true)
        """,
      ),
      plugin = BridgeCreateTestRegistrar(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

    val clazz = result.classLoader.loadClass("com.example.Bridged")
    val factory = factoryOf(clazz)
    assertEquals(
      listOf(
        Int::class.javaPrimitiveType,
        Int::class.javaPrimitiveType,
        String::class.java,
        Boolean::class.javaPrimitiveType,
      ),
      factory.parameterTypes.asList(),
      "bridgeCreate${factory.parameterTypes.toList()}",
    )

    // Every bit clear: the arguments arrive exactly as the caller passed them.
    val supplied = factory.invoke(holderOf(clazz), 0, 7, "given", false)
    assertEquals(listOf(7, "given", false), readBack(clazz, supplied, "getA", "getName", "getFlag"))

    // Bits for `name` and `flag` set: the host's own defaults run instead of those arguments.
    val defaulted = factory.invoke(holderOf(clazz), 0b110, 7, "ignored", false)
    assertEquals(listOf(7, "dflt", true), readBack(clazz, defaulted, "getA", "getName", "getFlag"))
  }

  /**
   * A default expression may call a `private` top-level function of the class's file or read a
   * `private` companion member; nothing outside the class sees both, which is why the factory is a
   * companion member and not a top-level function. This also covers a class that already has a
   * companion: the factory joins it instead of a second one being generated.
   */
  @Test
  fun `the defaults keep the access rights of the annotated class`() {
    val result = compile(
      SourceFile.kotlin(
        "Seeded.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        private fun seedFromFile(): Int = 7

        @WithJS2HostBridge
        class Seeded(val seed: Int = seedFromFile(), val label: String = FALLBACK) {
          companion object {
            private const val FALLBACK = "fb"
          }
        }
        """,
      ),
      plugin = BridgeCreateTestRegistrar(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

    val clazz = result.classLoader.loadClass("com.example.Seeded")
    val instance = factoryOf(clazz).invoke(holderOf(clazz), 0b11, 0, "")
    assertEquals(listOf(7, "fb"), readBack(clazz, instance, "getSeed", "getLabel"))
  }

  /**
   * A default may mention an earlier parameter. The copied expression has to read the *supplied*
   * argument, so the copy remaps the constructor's parameters onto the factory's: `b` here is 42,
   * not 1.
   */
  @Test
  fun `a default naming an earlier parameter reads the argument the caller supplied`() {
    val result = compile(
      SourceFile.kotlin(
        "Dependent.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Dependent(val a: Int, val b: Int = a + 1)
        """,
      ),
      plugin = BridgeCreateTestRegistrar(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

    val clazz = result.classLoader.loadClass("com.example.Dependent")
    val instance = factoryOf(clazz).invoke(holderOf(clazz), 0b10, 41, 0)
    assertEquals(listOf(41, 42), readBack(clazz, instance, "getA", "getB"))
  }

  /**
   * The factory's name belongs to the plugin. A bridged class that declares a `bridgeCreate` of its
   * own gets the build failed with the class and the offending member named: keeping both would let a
   * generated host bridge call the user's function and construct a wrong value, and preferring ours
   * would quietly delete a member the source declares.
   */
  @Test
  fun `a bridgeCreate of the user's own companion is left alone`() {
    val result = compile(
      SourceFile.kotlin(
        "Neighbour.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Neighbour(val a: Int, val b: String = "d") {
          companion object {
            fun bridgeCreate(what: String): String = what
          }
        }
        """,
      ),
      plugin = BridgeCreateTestRegistrar(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

    // Both members exist, in different classifiers, and neither shadows the other: the factory is in
    // the generated `Bridge`, the author's function stays in the companion. This is the shape Redwood
    // needs -- its payload classes carry @Serializable, whose plugin owns `Companion`.
    val clazz = result.classLoader.loadClass("com.example.Neighbour")
    // The plugin's factory is in the generated `Bridge` and it builds the object: bit 1 set, so `b`
    // takes the declared default rather than the argument the caller passed.
    val built = factoryOf(clazz).invoke(holderOf(clazz), 0b10, 7, "ignored")
    assertEquals(listOf(7, "d"), readBack(clazz, built, "getA", "getB"))
    // The author's member is untouched, in the classifier the source put it in.
    val companionClass = clazz.classes.single { it.simpleName == "Companion" }
    val companion = clazz.getField("Companion").get(null)
    val kept = companionClass.getMethod("bridgeCreate", String::class.java).invoke(companion, "mine")
    assertEquals("mine", kept)
  }

  /**
   * The one clash that cannot be resolved by putting the factory somewhere else: a `Bridge` the source
   * declares itself. Silently skipping would leave the generated bridge calling the author's `Bridge`
   * through a name it believes the plugin owns, so the build fails and names the class.
   */
  @Test
  fun `a Bridge of the user's own fails the build naming the class`() {
    val result = compile(
      SourceFile.kotlin(
        "Conflict.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Conflict(val a: Int, val b: String = "d") {
          object Bridge {
            val mine = 1
          }
        }
        """,
      ),
      plugin = BridgeCreateTestRegistrar(),
    )
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertTrue("com.example.Conflict" in result.messages, result.messages)
    assertTrue("Bridge" in result.messages, result.messages)
  }

  /** Forty defaulted parameters need two flag words, and the second word's bits must be honoured. */
  @Test
  fun `a signature wider than one flag word carries a second mask parameter`() {
    val parameters = (0 until 40).joinToString(", ") { "val p$it: Int = $it" }
    val result = compile(
      SourceFile.kotlin(
        "Wide.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Wide($parameters)
        """,
      ),
      plugin = BridgeCreateTestRegistrar(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

    val clazz = result.classLoader.loadClass("com.example.Wide")
    val factory = factoryOf(clazz)
    assertEquals(42, factory.parameterTypes.size, "two mask words plus 40 parameters")
    assertEquals(Int::class.javaPrimitiveType, factory.parameterTypes[1])

    // All flags set, every argument a placeholder zero: each parameter must come back as its own
    // default, including p39, whose bit lives in the second word.
    val args = Array<Any?>(42) { index -> if (index < 2) -1 else 0 }
    val instance = factory.invoke(holderOf(clazz), *args)
    assertEquals(listOf(17, 39), readBack(clazz, instance, "getP17", "getP39"))
  }

  /**
   * When an earlier parameter takes its own default, a later default that mentions it must see that
   * default and not the placeholder the caller passed for it: `b` is 6, not 1. This is why the body
   * holds each parameter's chosen value in a local instead of inlining the choice into the call.
   */
  @Test
  fun `a default sees the default an earlier parameter took`() {
    val result = compile(
      SourceFile.kotlin(
        "Chained.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Chained(val a: Int = 5, val b: Int = a + 1)
        """,
      ),
      plugin = BridgeCreateTestRegistrar(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

    val clazz = result.classLoader.loadClass("com.example.Chained")
    val factory = factoryOf(clazz)
    val companion = holderOf(clazz)

    // Both flags set with placeholder arguments: Kotlin's own rule gives b from the chosen a.
    val chained = factory.invoke(companion, 0b11, 0, 0)
    assertEquals(listOf(5, 6), readBack(clazz, chained, "getA", "getB"))

    // Only `b` defaulted: it sees the 41 the caller supplied for `a`.
    val supplied = factory.invoke(companion, 0b10, 41, 0)
    assertEquals(listOf(41, 42), readBack(clazz, supplied, "getA", "getB"))
  }
}

/** The factory the plugin generated, reached through the nested object that holds it. */
private fun factoryOf(clazz: Class<*>): Method = holderClassOf(clazz).methods.single { it.name == "bridgeCreate" }

/** The generated `Bridge` the factory lives in, as a JVM class. */
private fun holderClassOf(clazz: Class<*>): Class<*> = clazz.classes.single { it.simpleName == "Bridge" }

/** An `object` is exposed through INSTANCE; only a companion object is exposed under its own name. */
private fun holderOf(clazz: Class<*>): Any = holderClassOf(clazz).getField("INSTANCE").get(null)

private fun readBack(clazz: Class<*>, instance: Any, vararg getters: String): List<Any?> = getters.map { clazz.getMethod(it).invoke(instance) }
