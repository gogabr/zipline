/*
 * Copyright (C) 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package app.cash.zipline.bridge.kotlin

import com.tschuchort.compiletesting.JvmCompilationResult
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.SourceFile
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.junit.Test

/** Confirm the bridge compiler plugin generates C bridge files for @WithJS2HostBridge classes. */
@OptIn(ExperimentalCompilerApi::class)
class ZiplineBridgeKotlinPluginTest {

  @Test
  fun `cOutputDir option is accepted and compilation succeeds`() {
    val result = compileWithArgs(
      sourceFile = SourceFile.kotlin(
        "Test.kt",
        """
        package com.example

        class Greeting {
          fun sayHello(name: String): String = "Hello, ${'$'}name!"
        }
        """,
      ),
      args = listOf(
        "-P",
        "plugin:app.cash.zipline.bridge.kotlin:cOutputDir=/tmp/test-output",
      ),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `compilation succeeds without cOutputDir option`() {
    val result = compile(
      sourceFile = SourceFile.kotlin(
        "Test.kt",
        """
        package com.example

        class Greeting {
          fun sayHello(name: String): String = "Hello, ${'$'}name!"
        }
        """,
      ),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `annotated class generates bridge file with toJavaObject and bridge_init`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Bridged.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class Bridged {
            val name: String = "test"
            val age: Int = 42
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_Bridged.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Includes
      assertTrue(content.contains("#include \"bridge_dispatch.h\""))

      // toJavaObject function signature (no JNIEXPORT, no jniBridge)
      assertTrue(content.contains("static jobject com_example_Bridged_toJavaObject("))
      assertTrue(content.contains("JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsObj"))
      // No JNIEXPORT / jniBridge
      assertFalse(content.contains("JNIEXPORT"), "Should not contain JNIEXPORT")
      assertFalse(content.contains("jniBridge"), "Should not contain jniBridge")

      // Field extraction from JS object
      assertTrue(content.contains("jsObj.asObject(rt).getProperty(rt, \"name\")"))
      assertTrue(content.contains("jsObj.asObject(rt).getProperty(rt, \"age\")"))

      // String field conversion
      assertTrue(content.contains("std::string str_name = js_name.asString(rt).utf8(rt);"))
      assertTrue(content.contains("NewStringUTF(str_name.c_str())"))

      // Int field conversion
      assertTrue(content.contains("jsi_value_get_int(js_age)"))

      // Class and constructor lookup
      assertTrue(content.contains("FindClass"))
      assertTrue(content.contains("GetMethodID"))
      assertTrue(content.contains("\"()V\""))

      // Object construction
      assertTrue(content.contains("NewObject"))

      // Field setting
      assertTrue(content.contains("GetFieldID"))
      assertTrue(content.contains("SetObjectField(result, _fld_name, java_name)"))
      assertTrue(content.contains("SetIntField(result, _fld_age, java_age)"))

      // Return
      assertTrue(content.contains("return result;"))

      // bridge_init replaced by per-class __attribute__((constructor)) with addBridgeEntry
      assertTrue(content.contains("com_example_Bridged_bridge_register("))
      assertTrue(content.contains("addBridgeInit(com_example_Bridged_init)"))
      assertTrue(content.contains("addBridgeEntry(\"com.example.Bridged\""))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `per-class constructor registers in shared bridgeTable`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "TwoClasses.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class Alpha {
            val x: Int = 1
          }

          @WithJS2HostBridge
          class Beta {
            val y: String = "hello"
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val alphaFile = outputDir.resolve("com_example_Alpha.cpp").toFile()
      assertTrue(alphaFile.exists(), "Expected per-class C file for Alpha")
      val alphaContent = alphaFile.readText()
      assertTrue(alphaContent.contains("__attribute__((used, constructor))"))
      assertTrue(alphaContent.contains("addBridgeEntry(\"com.example.Alpha\""))

      val betaFile = outputDir.resolve("com_example_Beta.cpp").toFile()
      assertTrue(betaFile.exists(), "Expected per-class C file for Beta")
      val betaContent = betaFile.readText()
      assertTrue(betaContent.contains("__attribute__((used, constructor))"))
      assertTrue(betaContent.contains("addBridgeEntry(\"com.example.Beta\""))

      // No aggregate init file - per-class constructors handle registration.
      val initFile = outputDir.resolve("bridge_module_init.cpp").toFile()
      assertFalse(initFile.exists(), "bridge_module_init.cpp should not be generated")
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `no per-class files when no annotated classes`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Plain.kt",
          """
          package com.example

          class Plain {
            val name: String = "test"
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFiles = outputDir.toFile().listFiles { f -> f.name.endsWith(".cpp") } ?: emptyArray()
      assertEquals(0, cFiles.size, "No .cpp bridge files when no annotated classes")
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `derived class includes inherited fields from annotated parent`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Animal.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          open class Animal {
            val species: String = ""
          }

          @WithJS2HostBridge
          class Dog : Animal() {
            val breed: String = ""
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_Dog.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Both inherited and own fields are extracted
      assertTrue(
        content.contains("jsObj.asObject(rt).getProperty(rt, \"species\")"),
        "Should extract inherited 'species' field",
      )
      assertTrue(
        content.contains("jsObj.asObject(rt).getProperty(rt, \"breed\")"),
        "Should extract own 'breed' field",
      )

      // Both fields are set after construction
      assertTrue(
        content.contains("SetObjectField(result, _fld_species, java_species)"),
        "Should set inherited 'species' field",
      )
      assertTrue(
        content.contains("SetObjectField(result, _fld_breed, java_breed)"),
        "Should set own 'breed' field",
      )

      // toJavaObject function targets Dog, not Animal
      assertTrue(
        content.contains("com_example_Dog_toJavaObject"),
        "toJavaObject should target com.example.Dog",
      )
      assertTrue(
        content.contains("com_example_Dog_bridge_register"),
        "constructor should target com.example.Dog",
      )
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `object fields use bridge_dispatch pattern`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Containing.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class Nested {
            val value: Int = 1
          }

          @WithJS2HostBridge
          class Container {
            val label: String = ""
            val child: Nested = Nested()
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_Container.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // String field still extracted directly
      assertTrue(content.contains("jsObj.asObject(rt).getProperty(rt, \"label\")"))
      assertTrue(content.contains("js_label.asString(rt).utf8(rt)"))

      // Nested object field uses bridge_dispatch pointer lookup
      assertTrue(content.contains("jsObj.asObject(rt).getProperty(rt, \"child\")"))
      assertTrue(content.contains("jsi_get_bridge_dispatch(rt, js_child)"))

      // Dispatch pointer read and call
      assertTrue(content.contains("JniBridgeDispatch *disp_child = (JniBridgeDispatch *)_bridge_ptr_child;"))
      assertTrue(content.contains("disp_child->toJavaObject(env, rt, js_child);"))

      // Object field uses SetObjectField with proper descriptor
      assertTrue(content.contains("SetObjectField(result, _fld_child, java_child)"))
      assertTrue(
        content.contains("\"Lcom/example/Nested;\""),
        "Should use proper JNI field descriptor for Nested type",
      )
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `primitive array field generates typed JNI array code`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Scores.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class Report {
            val scores: IntArray = intArrayOf(1, 2, 3)
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_Report.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Per-field array converter helper
      assertTrue(content.contains("static jobject conv_scores(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal)"))

      // Typed JNI array creation
      assertTrue(content.contains("NewIntArray"))
      assertTrue(content.contains("GetIntArrayElements"))
      assertTrue(content.contains("ReleaseIntArrayElements"))
      assertTrue(content.contains("jintArray arr"))
      assertTrue(content.contains("jint* elems"))

      // Loop with element extraction
      assertTrue(content.contains("jsi_value_get_int(elem)"))

      // JNI field descriptor
      assertTrue(content.contains("[I"))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `string array field generates NewObjectArray with string elements`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Names.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class NameList {
            val names: Array<String> = arrayOf("alice", "bob")
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_NameList.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Element converter and array converter helpers
      assertTrue(content.contains("static jobject conv_names_element(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal)"))
      assertTrue(content.contains("jsVal.asString(rt).utf8(rt)"))
      assertTrue(content.contains("static jobject conv_names(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal)"))

      // String array creation
      assertTrue(content.contains("NewObjectArray"))
      assertTrue(content.contains("SetObjectArrayElement"))
      assertTrue(content.contains("DeleteLocalRef"))

      // JNI field descriptor for object array
      assertTrue(content.contains("[Ljava/lang/String;"))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `array of bridge objects uses dispatch per element`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Items.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class Item {
            val x: Int = 0
          }

          @WithJS2HostBridge
          class ItemList {
            val items: Array<Item> = arrayOf(Item())
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_ItemList.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Per-element converter dispatches via bridge_dispatch
      assertTrue(content.contains("static jobject conv_items_element(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal)"))
      assertTrue(content.contains("jsi_get_bridge_dispatch(rt, jsVal)"))
      assertTrue(content.contains("disp->toJavaObject(env, rt, jsVal)"))

      // Array converter creates an object array
      assertTrue(content.contains("static jobject conv_items(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal)"))
      assertTrue(content.contains("NewObjectArray"))
      assertTrue(content.contains("SetObjectArrayElement"))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `array field generates per-element converter`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Strings.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class MixedBag {
            val values: Array<String> = arrayOf("hello", "world")
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_MixedBag.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Per-element converter and array converter are generated
      assertTrue(content.contains("static jobject conv_values_element(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal)"))
      assertTrue(content.contains("static jobject conv_values(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal)"))
      assertTrue(content.contains("NewObjectArray"))

      // Field descriptor for Array<String>
      assertTrue(content.contains("[Ljava/lang/String;"))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `array of any field is rejected`() {
    val result = compileWithCOutputDir(
      sourceFile = SourceFile.kotlin(
        "AnyArr.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class MixedBag {
          val values: Array<Any> = arrayOf(1, "hello", true)
        }
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    // A collection must have a good element type; Array<Any> is not allowed as a field.
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertTrue(result.messages.contains("field 'MixedBag.values' has type 'kotlin.Array' that cannot cross the bridge"), result.messages)
  }

  // -- CLV-88: bridge safety subset --

  @Test
  fun `good field types compile`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "Good.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Payload(
          val i: Int,
          val s: String,
          val b: Boolean,
          val f: Float,
          val ints: List<Int>,
          val map: Map<String, Int>,
          val bytes: ByteArray,
          val nested: Payload2,
        )

        @WithJS2HostBridge
        class Payload2(val x: Int)
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `bad field type is rejected`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "Bad.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Bag {
          val stamp: java.util.UUID = java.util.UUID.randomUUID()
        }
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertTrue(result.messages.contains("bridge: field 'Bag.stamp' has type 'java.util.UUID' that cannot cross the bridge"), result.messages)
  }

  @Test
  fun `unannotated class is untouched`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "Plain.kt",
        """
        package com.example

        class Plain {
          val stamp: java.util.Date = java.util.Date()
        }
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `any field with good initializer compiles`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "AnyGood.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Slot {
          val value: Any? = 42
          val text: Any? = "hi"
          val list: Any? = listOf(1, 2, 3)
        }
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `any field assigned bad value is rejected`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "AnyBad.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Slot {
          var value: Any? = null
        }

        fun fill(s: Slot) {
          s.value = java.util.Date()
        }
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertTrue(result.messages.contains("value of type 'java.util.Date' assigned to property 'value' of 'Slot' cannot cross the bridge"), result.messages)
  }

  @Test
  fun `any field assigned any value is rejected`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "Chain.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Chain {
          val a: Any? = "hello"
          val b: Any? = a
        }
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertTrue(result.messages.contains("value of type 'kotlin.Any' assigned to field 'Chain.b' cannot cross the bridge"), result.messages)
  }

  @Test
  fun `constructor parameter backed any field is not a false error`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "CtorGood.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Holder(val value: Any?)

        fun make(): Holder = Holder(42)
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `constructor argument with any type is rejected`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "CtorBad.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Holder(val value: Any?)

        fun make(x: Any?): Holder = Holder(x)
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertTrue(
      result.messages.contains("value of type 'kotlin.Any' assigned to constructor parameter 'value' of 'Holder' cannot cross the bridge"),
      result.messages,
    )
  }

  @Test
  fun `suppress bridge unsafe on enclosing function allows any argument`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "SuppressFn.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Holder(val value: Any?)

        @Suppress("BRIDGE_UNSAFE")
        fun make(x: Any?): Holder = Holder(x)
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `suppress bridge unsafe on source variable allows any argument`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "SuppressVar.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Holder(val value: Any?)

        fun decode(): Any? = null

        fun make(): Holder {
          @Suppress("BRIDGE_UNSAFE")
          val value: Any? = decode()
          return Holder(value)
        }
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `suppress bridge unsafe does not silence field type checks`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "SuppressField.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @Suppress("BRIDGE_UNSAFE")
        @WithJS2HostBridge
        class Holder(val value: java.util.UUID)
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertTrue(result.messages.contains("field 'Holder.value' has type 'java.util.UUID' that cannot cross the bridge"), result.messages)
  }

  @Test
  fun `enum field crosses js2host only`() {
    val js2host = compileWithCOutputDir(
      SourceFile.kotlin(
        "EnumJs.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        enum class Color { RED }

        @WithJS2HostBridge
        class Paint(val color: Color)
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, js2host.exitCode, js2host.messages)

    val host2js = compileWithCOutputDir(
      SourceFile.kotlin(
        "EnumHost.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithHost2JSBridge

        enum class Color { RED }

        @WithHost2JSBridge
        class Paint(val color: Color)
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, host2js.exitCode, host2js.messages)
    assertTrue(
      host2js.messages.contains("field 'Paint.color' has enum type 'com.example.Color' that cannot cross the host-to-JS bridge as a value"),
      host2js.messages,
    )
  }

  @Test
  fun `non-final unannotated class field is rejected`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "Open.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        open class Base

        @WithJS2HostBridge
        class Sub(val base: Base)
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    // Only a bridge-annotated class (own or inherited) is good; a non-final unannotated
    // static type is no longer waved through.
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertTrue(result.messages.contains("field 'Sub.base' has type 'com.example.Base' that cannot cross the bridge"), result.messages)
  }

  @Test
  fun `list of any field is rejected`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "ListAny.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Bag(val items: List<Any?>)
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertTrue(result.messages.contains("field 'Bag.items' has type 'kotlin.collections.List' that cannot cross the bridge"), result.messages)
  }

  @Test
  fun `map with any value field is rejected`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "MapAny.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Bag(val entries: Map<String, Any?>)
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertTrue(result.messages.contains("field 'Bag.entries' has type 'kotlin.collections.Map' that cannot cross the bridge"), result.messages)
  }

  @Test
  fun `function field is allowed`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "Fn.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Actions(
          val onClick: (() -> Unit)?,
          val onKey: ((Int) -> Unit)?,
        )
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `subclass of annotated parent is checked`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "Sticky.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        open class Base

        class Sub(val stamp: java.util.UUID) : Base()
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    // Stickiness: Sub inherits @WithJS2HostBridge, so its own fields are validated.
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
    assertTrue(result.messages.contains("field 'Sub.stamp' has type 'java.util.UUID' that cannot cross the bridge"), result.messages)
  }

  @Test
  fun `sticky host2js subclass inherits enum warning`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "StickyHost.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithHost2JSBridge

        enum class Color { RED }

        @WithHost2JSBridge
        open class Base

        class Paint(val color: Color) : Base()
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
    assertTrue(
      result.messages.contains("field 'Paint.color' has enum type 'com.example.Color' that cannot cross the host-to-JS bridge as a value"),
      result.messages,
    )
  }

  @Test
  fun `unbounded type parameter field is checked at writes`() {
    val ok = compileWithCOutputDir(
      SourceFile.kotlin(
        "GenGood.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Box<T>(val value: T)

        fun make(): Box<String> = Box("hi")
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, ok.exitCode, ok.messages)

    val bad = compileWithCOutputDir(
      SourceFile.kotlin(
        "GenBad.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Box<T>(val value: T)

        fun make(x: Any?): Box<Any?> = Box(x)
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    // An unbounded type parameter behaves like Any?: the field is allowed, the write is not.
    assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, bad.exitCode, bad.messages)
    assertTrue(
      bad.messages.contains("value of type 'kotlin.Any' assigned to constructor parameter 'value' of 'Box' cannot cross the bridge"),
      bad.messages,
    )
  }

  @Test
  fun `value class over primitive crosses js2host`() {
    val result = compileWithCOutputDir(
      SourceFile.kotlin(
        "Value.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @JvmInline
        value class Wrapped(val v: Int)

        @WithJS2HostBridge
        class Box(val w: Wrapped)
        """,
      ),
      cOutputDir = createTempDirectory("zipline-bridge-safety").toString(),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `nullable string field generates null check`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Nullable.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class OptString {
            val name: String? = null
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_OptString.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // C type is jstring (same as non-null String)
      assertTrue(content.contains("jstring java_name;"))

      // Null check wrapping string extraction
      assertTrue(content.contains("!js_name.isUndefined() && !js_name.isNull()"))
      assertTrue(content.contains("std::string str_name = js_name.asString(rt).utf8(rt);"))
      assertTrue(content.contains("NewStringUTF(str_name.c_str())"))

      // Null branch sets to NULL
      assertTrue(content.contains("java_name = NULL;"))

      // JNI field descriptor unchanged
      assertTrue(content.contains("\"Ljava/lang/String;\""))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `nullable int field generates boxing code`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "NullableInt.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class OptInt {
            val age: Int? = null
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_OptInt.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // C type is jobject (not jint) for nullable primitive
      assertTrue(content.contains("jobject java_age;"))

      // Pre-lookup of boxed class and constructor
      assertTrue(content.contains("FindClass(\"java/lang/Integer\")"))
      assertTrue(content.contains("_boxed_age"))
      assertTrue(content.contains("_boxedCtor_age"))

      // Null check
      assertTrue(content.contains("!js_age.isUndefined() && !js_age.isNull()"))

      // Boxing via NewObject
      assertTrue(content.contains("NewObject(_boxed_age, _boxedCtor_age, (jint)jsi_value_get_int(js_age))"))

      // Null branch
      assertTrue(content.contains("java_age = NULL;"))

      // JNI field descriptor uses boxed type
      assertTrue(content.contains("\"Ljava/lang/Integer;\""))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `nullable object field generates null check`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "NullableObj.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class Nested {
            val value: Int = 1
          }

          @WithJS2HostBridge
          class OptContainer {
            val label: String = ""
            val child: Nested? = null
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_OptContainer.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Null check for child field
      assertTrue(content.contains("!js_child.isUndefined() && !js_child.isNull()"))

      // bridge_dispatch lookup inside null check
      assertTrue(content.contains("jsi_get_bridge_dispatch(rt, js_child)"))
      assertTrue(content.contains("disp_child->toJavaObject(env, rt, js_child);"))

      // Null branch
      assertTrue(content.contains("java_child = NULL;"))

      // Label (non-null string) does NOT get null check
      assertTrue(
        content.indexOf("js_label.isUndefined()") == -1,
        "Non-nullable label field should not have null check",
      )
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `nullable array field generates null check`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "NullableArray.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class OptArray {
            val scores: IntArray? = null
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_OptArray.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Null check for array field
      assertTrue(content.contains("!js_scores.isUndefined() && !js_scores.isNull()"))

      // Array extraction inside null check
      assertTrue(content.contains("conv_scores(env, rt, js_scores)"))
      assertTrue(content.contains("NewIntArray"))
      assertTrue(content.contains("GetIntArrayElements"))

      // Null branch
      assertTrue(content.contains("java_scores = NULL;"))

      // JNI field descriptor unchanged for nullable array
      assertTrue(content.contains("[I"))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `nullable string array field generates element converter`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "NullableNames.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class NullableNames {
            val names: Array<String?> = arrayOf("alice", null, "bob")
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_NullableNames.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Element converter converts each element to a JNI string
      assertTrue(content.contains("static jobject conv_names_element(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal)"))
      assertTrue(content.contains("jsVal.asString(rt).utf8(rt)"))

      // Array converter builds a NewObjectArray
      assertTrue(content.contains("static jobject conv_names(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal)"))
      assertTrue(content.contains("NewObjectArray"))
      assertTrue(content.contains("SetObjectArrayElement"))
      assertTrue(content.contains("DeleteLocalRef"))

      // JNI field descriptor for Array<String?>
      assertTrue(content.contains("[Ljava/lang/String;"))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `plugin compiles simple class successfully`() {
    val result = compile(
      sourceFile = SourceFile.kotlin(
        "Test.kt",
        """
        package com.example

        class Greeting {
          fun sayHello(name: String): String = "Hello, ${'$'}name!"
        }
        """,
      ),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `plugin compiles interface with properties and functions`() {
    val result = compile(
      sourceFile = SourceFile.kotlin(
        "Service.kt",
        """
        package com.example

        interface Calculator {
          fun add(a: Int, b: Int): Int
          fun subtract(a: Int, b: Int): Int
        }

        class SimpleCalculator : Calculator {
          override fun add(a: Int, b: Int): Int = a + b
          override fun subtract(a: Int, b: Int): Int = a - b
        }
        """,
      ),
    )
    assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
  }

  @Test
  fun `host2js annotated class gets convertToJs member on JVM`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Bridged.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithHost2JSBridge

          @WithHost2JSBridge
          class Bridged {
            val name: String = "test"
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      // The member the host-side converter dispatch calls: it must exist on the class itself
      // (so bridgeAnyToJs can find it) with the handle-in/handle-out signature.
      val clazz = result.classLoader.loadClass("com.example.Bridged")
      val convertToJs = clazz.getDeclaredMethod("convertToJs", Long::class.javaPrimitiveType)
      assertEquals(Long::class.javaPrimitiveType, convertToJs.returnType)
      assertTrue(
        convertToJs.modifiers and java.lang.reflect.Modifier.NATIVE != 0,
        "convertToJs should be native (its body is the generated C)",
      )
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `remapped target FQN uses internal name for JNI, dotted name for the bridge key`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "RemappedHolder.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithHost2JSBridge
          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge("com.example.protocol.TargetImpl")
          @WithHost2JSBridge
          class RemappedHolder {
            val name: String = "test"
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.toFile().listFiles { f -> f.extension == "cpp" }!!.single()
      val content = cFile.readText()

      // JNI takes the internal class name; a dotted one is an illegal class name on ART.
      assertTrue(content.contains("FindClass(\"com/example/protocol/TargetImpl\")"), content)
      assertFalse(content.contains("FindClass(\"com.example.protocol.TargetImpl\")"))
      assertTrue(content.contains("JNIEXPORT jlong JNICALL Java_com_example_protocol_TargetImpl_convertToJs"), content)

      // The bridge key is the resolved target FQN, dotted - the same name the guest passes to
      // __bridgeRegister at module load. Keying the JNI table or the retained prototype on the
      // annotated class's own FQN would leave the two directions looking each other up under
      // different names.
      assertTrue(content.contains("addBridgeEntry(\"com.example.protocol.TargetImpl\""), content)
      assertFalse(content.contains("addBridgeEntry(\"com.example.RemappedHolder\""))
      assertTrue(content.contains("jsiHost2JsNewObject(env, rt, \"com.example.protocol.TargetImpl\")"), content)
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `host2js annotated class generates convertToJs JNI impl`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Bridged.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithHost2JSBridge

          @WithHost2JSBridge
          class Bridged {
            val name: String = "test"
            val age: Int = 42
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_Bridged.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // JNI implementation of the injected external member, prototype-based object creation.
      // extern "C" is what makes the JVM's short-name lookup find the symbol: this file is
      // compiled as C++, so without it the name is mangled and the native method cannot bind.
      assertTrue(
        content.contains(
          "extern \"C\" JNIEXPORT jlong JNICALL Java_com_example_Bridged_convertToJs(JNIEnv *env, jobject self, jlong rtPtr)",
        ),
        content,
      )
      assertTrue(content.contains("jsiHost2JsNewObject(env, rt, \"com.example.Bridged\")"), content)

      // Every field is defined as an own data property on the new instance.
      assertTrue(content.contains("jsiHost2JsDefineProperty(rt, result, \"name\", "), content)
      assertTrue(content.contains("jsiHost2JsDefineProperty(rt, result, \"age\", "), content)

      // A host2js-only class has no JS-to-host converter, so it is not in the dispatch table.
      assertFalse(content.contains("addBridgeEntry("), content)
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }
}

@ExperimentalCompilerApi
fun compile(
  sourceFiles: List<SourceFile>,
  plugin: CompilerPluginRegistrar = ZiplineBridgeCompilerPluginRegistrar(),
): JvmCompilationResult {
  return KotlinCompilation().apply {
    sources = sourceFiles
    compilerPluginRegistrars = listOf(plugin)
    inheritClassPath = true
  }.compile()
}

@ExperimentalCompilerApi
fun compile(
  sourceFile: SourceFile,
  plugin: CompilerPluginRegistrar = ZiplineBridgeCompilerPluginRegistrar(),
): JvmCompilationResult {
  return compile(listOf(sourceFile), plugin)
}

@ExperimentalCompilerApi
fun compileWithArgs(
  sourceFiles: List<SourceFile>,
  args: List<String>,
  plugin: CompilerPluginRegistrar = ZiplineBridgeCompilerPluginRegistrar(),
): JvmCompilationResult {
  return KotlinCompilation().apply {
    sources = sourceFiles
    compilerPluginRegistrars = listOf(plugin)
    commandLineProcessors = listOf(ZiplineBridgeCommandLineProcessor())
    inheritClassPath = true
    kotlincArguments = args
  }.compile()
}

@ExperimentalCompilerApi
fun compileWithArgs(
  sourceFile: SourceFile,
  args: List<String>,
  plugin: CompilerPluginRegistrar = ZiplineBridgeCompilerPluginRegistrar(),
): JvmCompilationResult {
  return compileWithArgs(listOf(sourceFile), args, plugin)
}

/**
 * Compiles with a custom [CompilerPluginRegistrar] that directly injects [cOutputDir]
 * into the IR extension, bypassing the CLI argument / [CommandLineProcessor] path.
 *
 * This is needed because [KotlinCompilation] with [compilerPluginRegistrars] does not
 * properly route [kotlincArguments] through [org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor].
 */
@ExperimentalCompilerApi
fun compileWithCOutputDir(
  sourceFile: SourceFile,
  cOutputDir: String,
): JvmCompilationResult {
  val customRegistrar = object : CompilerPluginRegistrar() {
    override val pluginId: String get() = BuildConfig.KOTLIN_PLUGIN_ID
    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
      IrGenerationExtension.registerExtension(
        ZiplineBridgeIrGenerationExtension(cOutputDir),
      )
    }
  }
  return KotlinCompilation().apply {
    sources = listOf(sourceFile)
    compilerPluginRegistrars = listOf(customRegistrar)
    inheritClassPath = true
  }.compile()
}

/**
 * Compiles with a custom [CompilerPluginRegistrar] that directly injects [nativeOutputDir]
 * into the IR extension, triggering Kotlin/Native bridge generation.
 */
@ExperimentalCompilerApi
fun compileWithNativeOutputDir(
  sourceFile: SourceFile,
  nativeOutputDir: String,
): JvmCompilationResult {
  val customRegistrar = object : CompilerPluginRegistrar() {
    override val pluginId: String get() = BuildConfig.KOTLIN_PLUGIN_ID
    override val supportsK2: Boolean get() = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
      IrGenerationExtension.registerExtension(
        ZiplineBridgeIrGenerationExtension(cOutputDir = null, nativeOutputDir = nativeOutputDir),
      )
    }
  }
  return KotlinCompilation().apply {
    sources = listOf(sourceFile)
    compilerPluginRegistrars = listOf(customRegistrar)
    inheritClassPath = true
  }.compile()
}

// -- Kotlin/Native bridge tests --
@OptIn(ExperimentalCompilerApi::class)
class ZiplineBridgeNativePluginTest {

  /** Compiles [source] and returns the generated native bridge for [className]. */
  private fun generatedBridge(fileName: String, source: String, className: String): String {
    val outputDir = createTempDirectory("zipline-bridge-native-test")
    try {
      val result = compileWithNativeOutputDir(
        sourceFile = SourceFile.kotlin(
          fileName,
          """
          package com.example
          import app.cash.zipline.bridge.support.WithJS2HostBridge
          $source
          """,
        ),
        nativeOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      // No centralized retain file: each class self-registers.
      assertFalse(
        outputDir.resolve("_BridgeRetainAll.kt").toFile().exists(),
        "_BridgeRetainAll.kt should not be generated",
      )

      val ktFile = outputDir.resolve("com_example_${className}_bridge_native.kt").toFile()
      assertTrue(ktFile.exists(), "Expected native bridge file at ${ktFile.absolutePath}")
      return ktFile.readText()
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `simple class generates native bridge`() {
    val content = generatedBridge(
      "Simple.kt",
      "@WithJS2HostBridge class Simple(val name: String, val age: Int)",
      "Simple",
    )

    // Takes a Hermes context and value handle; returns the object as a StableRef pointer.
    assertTrue(content.contains("public fun com_example_Simple_toKotlin("))
    assertTrue(content.contains("jsValHandle: Int,"))
    assertTrue(content.contains("): COpaquePointer? {"))

    // Imports
    assertTrue(content.contains("import kotlinx.cinterop.*"))
    assertTrue(content.contains("import app.cash.zipline.hermes.*"))
    assertFalse(content.contains("quickjs"), "Should not reference QuickJS")

    // Field extraction from the JS object
    assertTrue(content.contains("HermesBridge_createHandle(ctx, jsValHandle, \"name\")"))
    assertTrue(content.contains("HermesBridge_createHandle(ctx, jsValHandle, \"age\")"))

    // String conversion frees the C string
    assertTrue(content.contains("HermesBridge_getValueString(ctx, nameRef)"))
    assertTrue(content.contains("toKStringFromUtf8()?.also { platform.posix.free(nameStr) }"))

    // Int extraction
    assertTrue(content.contains("HermesBridge_getValueDouble(ctx, ageRef).toInt()"))

    // Every field handle is freed
    assertTrue(content.contains("HermesBridge_freeHandle(ctx, nameRef)"))
    assertTrue(content.contains("HermesBridge_freeHandle(ctx, ageRef)"))

    // Constructor call with params, returned as a StableRef
    assertTrue(content.contains("Simple(name = name, age = age)"))
    assertTrue(content.contains("return StableRef.create(_obj).asCPointer()"))
     val outputDir = createTempDirectory("zipline-bridge-native-test")
     try {
       val result = compileWithNativeOutputDir(
         sourceFile = SourceFile.kotlin(
           "Simple.kt",
           """
           package com.example
           import app.cash.zipline.bridge.support.WithJS2HostBridge
           @WithJS2HostBridge
           class Simple(val name: String, val age: Int)
           """,
         ),
         nativeOutputDir = outputDir.toString(),
       )
       assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

       val ktFile = outputDir.resolve("com_example_Simple_bridge_native.kt").toFile()
       assertTrue(ktFile.exists(), "Expected native bridge file at ${ktFile.absolutePath}")

       val content = ktFile.readText()

       // Function signature returns the bridge handle (a COpaquePointer StableRef).
       assertTrue(content.contains("public fun com_example_Simple_toKotlin("))
       assertTrue(content.contains("): COpaquePointer? {"))

       // Imports
       assertTrue(content.contains("import kotlinx.cinterop.*"))
       assertTrue(content.contains("import app.cash.zipline.hermes.*"))

       // Field extraction from JS object
       assertTrue(content.contains("HermesBridge_createHandle(ctx, jsValHandle, \"name\")"))
       assertTrue(content.contains("HermesBridge_createHandle(ctx, jsValHandle, \"age\")"))

       // String conversion
       assertTrue(content.contains("HermesBridge_getValueString(ctx, nameRef)"))
       assertTrue(content.contains("toKStringFromUtf8"))

       // Int extraction
       assertTrue(content.contains("HermesBridge_getValueDouble(ctx, ageRef).toInt()"))

       // Handle cleanup
       assertTrue(content.contains("HermesBridge_freeHandle(ctx,"))

       // Constructor call with params
       assertTrue(content.contains("Simple(name = name, age = age)"))

       // Return as a StableRef handle (the engine's registerBridge takes a CFunction pointer)
       assertTrue(content.contains("return StableRef.create(_obj).asCPointer()"))
       assertTrue(content.contains("registerBridge(\"com.example.Simple\", staticCFunction(::com_example_Simple_toKotlin))"))
     } finally {
       outputDir.toFile().deleteRecursively()
     }
  }

  @Test
   fun `object field uses StableRef dispatch`() {
     val outputDir = createTempDirectory("zipline-bridge-native-test")
     try {
       val result = compileWithNativeOutputDir(
         sourceFile = SourceFile.kotlin(
           "Nested.kt",
           """
           package com.example
           import app.cash.zipline.bridge.support.WithJS2HostBridge
           @WithJS2HostBridge
           class Child(val x: Int)
           @WithJS2HostBridge
           class Parent(val label: String, val child: Child)
           """,
         ),
         nativeOutputDir = outputDir.toString(),
       )
       assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

       val ktFile = outputDir.resolve("com_example_Parent_bridge_native.kt").toFile()
       assertTrue(ktFile.exists(), "Expected native bridge file for Parent")

       val content = ktFile.readText()

       // Object field gets bridge_dispatch lookup
       assertTrue(content.contains("HermesBridge_createHandle(ctx, jsValHandle, \"child\")"))
       assertTrue(content.contains("HermesBridge_getBridgeDispatch(ctx, childRef)"))

       // The registered converter is called through the dispatch pointer; an object without one
       // (a plain JS object) falls back to bridgeForAny.
       assertTrue(content.contains("childDispPtr.toCPointer<CFunction<(COpaquePointer?, Int) -> COpaquePointer?>>()!!"))
       assertTrue(content.contains("childDispatchFn(ctx, childRef)!!.asStableRef<Any>().get() as Child"))
       assertTrue(content.contains("bridgeForAny(ctx, childRef) as Child"))

       // Handle cleanup
       assertTrue(content.contains("HermesBridge_freeHandle(ctx, childRef)"))
     } finally {
       outputDir.toFile().deleteRecursively()
     }
  }

  @Test
  fun `nullable object field has null check before dispatch`() {
     val outputDir = createTempDirectory("zipline-bridge-native-test")
     try {
       val result = compileWithNativeOutputDir(
         sourceFile = SourceFile.kotlin(
           "NullableObj.kt",
           """
           package com.example
           import app.cash.zipline.bridge.support.WithJS2HostBridge
           @WithJS2HostBridge
           class Inner(val v: Int)
           @WithJS2HostBridge
           class Outer(val inner: Inner?)
           """,
         ),
         nativeOutputDir = outputDir.toString(),
       )
       assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

       val ktFile = outputDir.resolve("com_example_Outer_bridge_native.kt").toFile()
       assertTrue(ktFile.exists())

       val content = ktFile.readText()

       // Null check wrapping the dispatch: no dispatch pointer (null/undefined) decodes to null.
       assertTrue(content.contains("val inner = if (innerDispPtr == 0L) null else {"))
       assertTrue(content.contains("innerDispatchFn(ctx, innerRef)?.asStableRef<Any>()?.get() as? Inner"))
       assertTrue(content.contains("HermesBridge_freeHandle(ctx, innerRef)"))
     } finally {
       outputDir.toFile().deleteRecursively()
     }
  }

  @Test
  fun `nullable string field has null check`() {
     val outputDir = createTempDirectory("zipline-bridge-native-test")
     try {
       val result = compileWithNativeOutputDir(
         sourceFile = SourceFile.kotlin(
           "OptStr.kt",
           """
           package com.example
           import app.cash.zipline.bridge.support.WithJS2HostBridge
           @WithJS2HostBridge
           class OptStr(val name: String?)
           """,
         ),
         nativeOutputDir = outputDir.toString(),
       )
       assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

       val ktFile = outputDir.resolve("com_example_OptStr_bridge_native.kt").toFile()
       assertTrue(ktFile.exists())

       val content = ktFile.readText()

       // Null check: JS null/undefined is guarded BEFORE the string conversion, since
       // String(null) is "null"
       assertTrue(
         content.contains(
           "val name = if (HermesBridge_getValueTag(ctx, nameRef) == TAG_UNDEFINED || " +
             "HermesBridge_getValueTag(ctx, nameRef) == TAG_NULL) null else",
         ),
       )
       assertTrue(content.contains("toKStringFromUtf8"))
       assertTrue(content.contains("platform.posix.free(nameStr)"))
     } finally {
       outputDir.toFile().deleteRecursively()
     }
  }

  @Test
   fun `list field uses the guest-driven collection decoder`() {
     val outputDir = createTempDirectory("zipline-bridge-native-test")
     try {
       val result = compileWithNativeOutputDir(
         sourceFile = SourceFile.kotlin(
           "WithList.kt",
           """
           package com.example
           import app.cash.zipline.bridge.support.WithJS2HostBridge
           @WithJS2HostBridge
           class WithList(val items: List<String>)
           """,
         ),
         nativeOutputDir = outputDir.toString(),
       )
       assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

       val ktFile = outputDir.resolve("com_example_WithList_bridge_native.kt").toFile()
       assertTrue(ktFile.exists())

       val content = ktFile.readText()

       // List extraction: the guest drives the iteration and identifies the collection kind
       // (Kotlin/JS mangles the stdlib member names), and the list is unwrapped from the
       // array-backed ArrayList before the decode.
       assertTrue(content.contains("HermesBridge_createHandle(ctx, itemsRef, \"array_1\")"))
       assertTrue(content.contains("conv_items(ctx, itemsArrRef)"))
       assertTrue(content.contains("jsCollectionToKotlin(ctx, jsArrHandle, CollectionKind.LIST)"))
       assertTrue(content.contains("HermesBridge_getValueString(ctx, element)"))
       assertTrue(content.contains("toKStringFromUtf8"))
     } finally {
       outputDir.toFile().deleteRecursively()
     }
  }

  @Test
  fun `per-class bridge self-registers via EagerInitialization`() {
     val outputDir = createTempDirectory("zipline-bridge-native-test")
     try {
       val result = compileWithNativeOutputDir(
         sourceFile = SourceFile.kotlin(
           "Foo.kt",
           """
           package com.example
           import app.cash.zipline.bridge.support.WithJS2HostBridge
           @WithJS2HostBridge
           class Foo(val x: Int)
           """,
         ),
         nativeOutputDir = outputDir.toString(),
       )
       assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

       // Per-class file has @EagerInitialization self-registration
       val bridgeFile = outputDir.resolve("com_example_Foo_bridge_native.kt").toFile()
       assertTrue(bridgeFile.exists(), "Expected per-class bridge file")
       val bridgeContent = bridgeFile.readText()
       assertTrue(bridgeContent.contains("@kotlin.native.EagerInitialization"))
       // Converter names come from the FQN, so same-simple-name classes cannot collide.
       assertTrue(
         bridgeContent.contains(
           "registerBridge(\"com.example.Foo\", staticCFunction(::com_example_Foo_toKotlin))",
         ),
       )

       // No centralized retain file - each class self-registers
       val retainFile = outputDir.resolve("_BridgeRetainAll.kt").toFile()
       assertFalse(retainFile.exists(), "_BridgeRetainAll.kt should not be generated")
     } finally {
       outputDir.toFile().deleteRecursively()
     }
  }

  @Test
  fun `enum generates ordinal extraction`() {
     val outputDir = createTempDirectory("zipline-bridge-native-test")
     try {
       val result = compileWithNativeOutputDir(
         sourceFile = SourceFile.kotlin(
           "Color.kt",
           """
           package com.example
           import app.cash.zipline.bridge.support.WithJS2HostBridge
           @WithJS2HostBridge
           enum class Color { RED, GREEN, BLUE }
           """,
         ),
         nativeOutputDir = outputDir.toString(),
       )
       assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

       val ktFile = outputDir.resolve("com_example_Color_bridge_native.kt").toFile()
       assertTrue(ktFile.exists())

       val content = ktFile.readText()

       // Enum ordinal extraction: the guest reports it (Kotlin/JS mangles the ordinal field), and
       // an out-of-range ordinal fails loudly instead of throwing an index exception.
       assertTrue(content.contains("jsEnumOrdinal(ctx, jsValHandle)"))
       assertTrue(content.contains("Color.entries.getOrNull(ordinal)"))
       assertTrue(content.contains("host bridge: ordinal \$ordinal is out of range for Color"))
     } finally {
       outputDir.toFile().deleteRecursively()
     }
  }
}
