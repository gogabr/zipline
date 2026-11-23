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
import kotlin.test.assertNotEquals
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
      // Not `NewStringUTF`: JNI's encoding is Java's modified UTF-8 while Hermes produced standard
      // UTF-8 one line above, which truncates at a NUL and mangles anything above U+FFFF. The
      // conversion goes through UTF-16, so the generated C must not contain the raw call.
      assertTrue(content.contains("zipline::utf8ToJniString(env, str_name)"))
      assertFalse(content.contains("NewStringUTF"))

      // Int field conversion
      assertTrue(content.contains("jsi_value_get_int(js_age)"))

      // Class and constructor lookup
      assertTrue(content.contains("FindClass"))
      assertTrue(content.contains("jsiGetMethodIdQuiet"))
      assertTrue(content.contains("\"()V\""))

      // Object construction
      assertTrue(content.contains("NewObject"))

      // Field setting
      assertTrue(content.contains("jsiGetFieldIdQuiet"))
      assertTrue(content.contains("SetObjectField(result, _fld_name, java_name)"))
      assertTrue(content.contains("SetIntField(result, _fld_age, java_age)"))

      // Host-missing body field: a NULL cached field ID (GetFieldID failed at load) throws the
      // mismatch before any JS read, so structural skew surfaces as a catchable exception rather
      // than a silent default or a Hermes abort. The guard precedes the JS property read.
      assertTrue(
        content.contains("if (_fld_name == NULL) {"),
        "missing host-field guard, content was: $content",
      )
      assertTrue(
        content.contains("jsiThrowHostFieldMissing(env, \"com.example.Bridged\", \"name\")"),
        "missing host-field throw, content was: $content",
      )
      val guardIdx = content.indexOf("if (_fld_name == NULL) {")
      val readIdx = content.indexOf("getProperty(rt, \"name\")")
      assertTrue(
        guardIdx in 0 until readIdx,
        "guard must precede the JS property read (guard=$guardIdx read=$readIdx)",
      )

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
  fun `array of any generates full runtime dispatch`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Mixed.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class MixedBag {
            val values: Array<Any> = arrayOf(1, "hello", true)
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val cFile = outputDir.resolve("com_example_MixedBag.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")

      val content = cFile.readText()

      // Per-element converter delegates to the generic boxer (full runtime dispatch)
      assertTrue(content.contains("static jobject conv_values_element(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal)"))
      assertTrue(content.contains("jsi_value_to_boxed(env, rt, jsVal)"))

      // Array converter creates an object array
      assertTrue(content.contains("static jobject conv_values(JNIEnv *env, jsi::Runtime &rt, const jsi::Value &jsVal)"))
      assertTrue(content.contains("NewObjectArray"))

      // Field descriptor for Array<Any>
      assertTrue(content.contains("[Lkotlin/Any;"))
    } finally {
      outputDir.toFile().deleteRecursively()
    }
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

      // C type is jstring (same as non-null String); the `= null` default makes it a defaulted
      // field, so the local is placeholder-initialized and the SetField is skipped when undefined.
      assertTrue(content.contains("jstring java_name = NULL;"))

      // Null check wrapping string extraction
      assertTrue(content.contains("!js_name.isUndefined() && !js_name.isNull()"))
      assertTrue(content.contains("std::string str_name = js_name.asString(rt).utf8(rt);"))
      // Not `NewStringUTF`: JNI's encoding is Java's modified UTF-8 while Hermes produced standard
      // UTF-8 one line above, which truncates at a NUL and mangles anything above U+FFFF. The
      // conversion goes through UTF-16, so the generated C must not contain the raw call.
      assertTrue(content.contains("zipline::utf8ToJniString(env, str_name)"))
      assertFalse(content.contains("NewStringUTF"))

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

      // C type is jobject (not jint) for nullable primitive; the `= null` default makes it a
      // defaulted field, so the local is placeholder-initialized.
      assertTrue(content.contains("jobject java_age = NULL;"))

      // Pre-lookup of boxed class and constructor
      assertTrue(content.contains("jsiFindClassQuiet(env, \"java/lang/Integer\")"))
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

      // Label is non-nullable: it must not carry a null-tolerance check. (It is defaulted via
      // `= ""`, so it does get the undefined-skip guard, but a present null still fails the tag
      // check — the loud invariant holds.)
      assertTrue(
        content.indexOf("js_label.isNull()") == -1,
        "Non-nullable label field should not have a null check",
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
      assertTrue(content.contains("jsiFindClassQuiet(env, \"com/example/protocol/TargetImpl\")"), content)
      assertFalse(content.contains("FindClass(\"com.example.protocol.TargetImpl\")"))
      assertTrue(content.contains("JNIEXPORT jlong JNICALL Java_com_example_protocol_TargetImpl_convertToJs"), content)

      // The bridge key is the resolved target FQN, dotted - the same name the guest passes to
      // __bridgeRegister at module load. Keying the JNI table or the retained prototype on the
      // annotated class's own FQN would leave the two directions looking each other up under
      // different names.
      assertTrue(content.contains("addBridgeEntry(\"com.example.protocol.TargetImpl\""), content)
      assertFalse(content.contains("addBridgeEntry(\"com.example.RemappedHolder\""))
      assertTrue(content.contains("jsiHost2JsNewObject(env, rt, \"com.example.protocol.TargetImpl\")"), content)

      // ... and to the guest's own create-factory for that same resolved name, so the guest's
      // constructor is what builds the payload. The factory name is derived from the resolved FQN, the
      // same name the guest registered the prototype under.
      assertTrue(
        content.contains(
          "jsiHost2JsCallCreateFactory(env, rt, \"com.example.protocol.TargetImpl\", " +
            "\"create_com_example_protocol_TargetImpl\", result)",
        ),
        content,
      )
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

      // Then the object is handed to the guest's own factory, unconditionally: there is no stamped
      // fallback, so a guest that publishes no factory is reported rather than served a lookalike.
      assertFalse(content.contains("jsiHost2JsHasCreateFactory"), content)
      assertTrue(
        content.contains(
          "jsiHost2JsCallCreateFactory(env, rt, \"com.example.Bridged\", \"create_com_example_Bridged\", result)",
        ),
        content,
      )

      // A host2js-only class has no JS-to-host converter, so it is not in the dispatch table.
      assertFalse(content.contains("addBridgeEntry("), content)
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `same simple name in another nesting level gets its own C bridge`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "SameSimpleName.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithHost2JSBridge
          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          @WithHost2JSBridge
          data class Alignment(val value: Int)

          class LineHeightStyle {
            @WithJS2HostBridge
            @WithHost2JSBridge
            data class Alignment(val value: Int)
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      // One file per class, named after the FQN, so the two never share a converter.
      val topLevel = outputDir.resolve("com_example_Alignment.cpp").toFile()
      val nested = outputDir.resolve("com_example_LineHeightStyle_Alignment.cpp").toFile()
      assertTrue(topLevel.exists(), "Expected ${topLevel.absolutePath}")
      assertTrue(nested.exists(), "Expected ${nested.absolutePath}")

      val topContent = topLevel.readText()
      val nestedContent = nested.readText()

      // Distinct bridge keys: the guest registers under the same FQN, so a shared key would make
      // one class's payloads decode as the other.
      assertTrue(topContent.contains("addBridgeEntry(\"com.example.Alignment\""), topContent)
      assertTrue(
        nestedContent.contains("addBridgeEntry(\"com.example.LineHeightStyle.Alignment\""),
        nestedContent,
      )

      // FindClass needs the JVM internal name: '$' for the nested class, never the dotted FQN.
      assertTrue(
        topContent.contains("jsiFindClassQuiet(env, \"com/example/Alignment\")"),
        topContent,
      )
      assertTrue(
        nestedContent.contains(
          "jsiFindClassQuiet(env, \"com/example/LineHeightStyle\$Alignment\")",
        ),
        nestedContent,
      )

      // The two generated C files must not define the same symbol, or the linker keeps one of them
      // and the other class's native method never binds.
      assertTrue(topContent.contains("Java_com_example_Alignment_convertToJs"), topContent)
      assertTrue(
        nestedContent.contains("Java_com_example_LineHeightStyle_00024Alignment_convertToJs"),
        nestedContent,
      )
      assertTrue(topContent.contains("com_example_Alignment_bridge_register("), topContent)
      assertTrue(
        nestedContent.contains("com_example_LineHeightStyle_Alignment_bridge_register("),
        nestedContent,
      )
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  /**
   * A defaulted constructor parameter makes the JVM emit a synthetic masked constructor; the host
   * reaches it by descriptor and passes a flag per parameter, so a guest that omitted a defaulted
   * field still gets the host's own default instead of a failed read.
   */
  @Test
  fun `defaulted ctor param generates masked ctor call`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Dflt.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class Dflt(val a: Int, val b: String = "dflt")
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val content = outputDir.resolve("com_example_Dflt.cpp").toFile().readText()

      // The synthetic defaulted ctor is looked up with the mask + marker signature.
      assertTrue(
        content.contains(
          "_ctor_default = jsiGetMethodIdQuiet(env, _cls, \"<init>\", " +
            "\"(ILjava/lang/String;ILkotlin/jvm/internal/DefaultConstructorMarker;)V\")",
        ),
        content,
      )
      // The defaulted field's undefined-ness is captured in a use_default bool.
      assertTrue(content.contains("bool use_default_b = js_b.isUndefined();"), content)
      // The mask is built from the use_default bools.
      assertTrue(content.contains("int _default_mask0 = (use_default_b ? 2 : 0);"), content)
      // The masked ctor is selected only when a flag is actually set.
      assertTrue(content.contains("if ((_default_mask0) != 0 && _ctor_default == NULL) {"), content)
      assertTrue(
        content.contains(
          "env->NewObject(_cls, _ctor_default, java_a, java_b, _default_mask0, (jobject)NULL)",
        ),
        content,
      )
      // The full ctor is still used for the all-present case.
      assertTrue(content.contains("env->NewObject(_cls, _ctor, java_a, java_b)"), content)
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  /**
   * A defaulted body var's SetField is guarded by `!isUndefined()`: when the guest omits the
   * field, the host's own initializer survives instead of being clobbered with a placeholder.
   */
  @Test
  fun `defaulted body var skips SetField when undefined`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "BodyDflt.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class BodyDflt {
            var a: Int = 0
            var extra: String = "bx"
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val content = outputDir.resolve("com_example_BodyDflt.cpp").toFile().readText()

      assertTrue(
        content.contains("if (!js_a.isUndefined()) {\n        env->SetIntField(result, _fld_a, java_a);"),
        content,
      )
      assertTrue(
        content.contains(
          "if (!js_extra.isUndefined()) {\n        env->SetObjectField(result, _fld_extra, java_extra);",
        ),
        content,
      )
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  /**
   * A non-defaulted field keeps the loud use-time failure: the tag check rejects undefined and
   * there is no flag machinery around it.
   */
  @Test
  fun `non-defaulted field keeps loud throw`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "NoDflt.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class NoDflt(val a: Int, val b: String)
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val content = outputDir.resolve("com_example_NoDflt.cpp").toFile().readText()

      // No masked-ctor machinery for a class with no defaults.
      assertFalse(content.contains("_ctor_default"), content)
      assertFalse(content.contains("use_default_"), content)
      assertFalse(content.contains("_default_mask"), content)
      // The tag check rejects undefined (C tag 3) for the non-defaulted field.
      assertTrue(
        content.contains(
          "if (tag_b != 4) {\n            jsiThrowFieldMismatch(env, \"com.example.NoDflt\", \"b\", \"a string\", tag_b);",
        ),
        content,
      )
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  /**
   * Six defaulted parameters used to fail the build outright. Nothing in the bridge counts them:
   * both the C host and the native host generate for any count, and neither emits a diagnostic
   * about how many defaults a class declares.
   */
  @Test
  fun `six defaulted ctor params build on every host`() {
    val cDir = createTempDirectory("zipline-bridge-c")
    val nativeDir = createTempDirectory("zipline-bridge-native")
    try {
      val six = SourceFile.kotlin(
        "Six.kt",
        """
        package com.example

        import app.cash.zipline.bridge.support.WithJS2HostBridge

        @WithJS2HostBridge
        class Six(
          val a: Int = 1,
          val b: Int = 2,
          val c: Int = 3,
          val d: Int = 4,
          val e: Int = 5,
          val f: Int = 6,
        )
        """,
      )

      val cResult = compileWithCOutputDir(sourceFile = six, cOutputDir = cDir.toString())
      assertEquals(KotlinCompilation.ExitCode.OK, cResult.exitCode, cResult.messages)
      assertFalse(cResult.messages.contains("defaulted constructor"), cResult.messages)
      val cContent = cDir.resolve("com_example_Six.cpp").toFile().readText()
      assertTrue(cContent.contains("int _default_mask0 = (use_default_a ? 1 : 0)"), cContent)

      val nativeResult = compileWithNativeOutputDir(
        sourceFile = six,
        nativeOutputDir = nativeDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, nativeResult.exitCode, nativeResult.messages)
      assertFalse(nativeResult.messages.contains("defaulted constructor"), nativeResult.messages)
    } finally {
      cDir.toFile().deleteRecursively()
      nativeDir.toFile().deleteRecursively()
    }
  }

  /**
   * A class with more defaulted parameters than one flag word holds is not a special case: the
   * JVM's masked constructor takes one `int` per 32 parameters, so the bridge emits (and passes)
   * one flag word per 32, with the overflown parameter landing in the second word's low bit.
   */
  @Test
  fun `defaults beyond one flag word get their own word`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val params = (0 until 40).joinToString(", ") { "val p$it: Int = $it" }
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Wide.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          class Wide($params)
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      // 40 defaulted parameters: no diagnostic about counts, and the build simply succeeds.
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val content = outputDir.resolve("com_example_Wide.cpp").toFile().readText()

      // 40 parameters + 2 flag words, matching the descriptor the JVM generated.
      val wideSig = "(" + "I".repeat(40) + "I".repeat(2) +
        "Lkotlin/jvm/internal/DefaultConstructorMarker;)V"
      assertTrue(
        content.contains("_ctor_default = jsiGetMethodIdQuiet(env, _cls, \"<init>\", \"$wideSig\")"),
        content,
      )
      // Parameter 34 sits in the second word at bit 2: a shift by its raw index would overflow.
      assertTrue(content.contains("bool use_default_p34 = js_p34.isUndefined();"), content)
      assertTrue(content.contains("(use_default_p34 ? 4 : 0)"), content)
      assertTrue(content.contains("int _default_mask0 = "), content)
      assertTrue(content.contains("int _default_mask1 = (use_default_p32 ? 1 : 0)"), content)
      assertTrue(
        content.contains("(_default_mask0 | _default_mask1) != 0 && _ctor_default == NULL"),
        content,
      )
      assertTrue(
        content.contains("_default_mask0, _default_mask1, (jobject)NULL)"),
        content,
      )
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `mutable constructor property on a host2js enum fails compilation`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "MutableEnum.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithHost2JSBridge

          @WithHost2JSBridge
          enum class MutableEnum(var count: Int) { A(1) }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
      assertTrue(result.messages.contains("MutableEnum"), result.messages)
      assertTrue(result.messages.contains("count"), result.messages)
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `mutable body property on a js2host enum fails compilation`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "BodyVarEnum.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          enum class BodyVarEnum { A; var label: String = "" }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.COMPILATION_ERROR, result.exitCode, result.messages)
      assertTrue(result.messages.contains("BodyVarEnum"), result.messages)
      assertTrue(result.messages.contains("label"), result.messages)
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `abstract-member enum generates a native bridge`() {
    val outputDir = createTempDirectory("zipline-bridge-native-test")
    try {
      val result = compileWithNativeOutputDir(
        sourceFile = SourceFile.kotlin(
          "Op.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          enum class Op {
            PLUS { override fun apply(a: Int, b: Int) = a + b },
            MINUS { override fun apply(a: Int, b: Int) = a - b };
            abstract fun apply(a: Int, b: Int): Int
          }
          """,
        ),
        nativeOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
      val bridgeFile = outputDir.resolve("com_example_Op_bridge_native.kt").toFile()
      assertTrue(bridgeFile.exists(), "Expected native bridge file at ${bridgeFile.absolutePath}")
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `abstract-member host2js enum generates a C bridge`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Op.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithHost2JSBridge

          @WithHost2JSBridge
          enum class Op {
            PLUS { override fun apply(a: Int, b: Int) = a + b },
            MINUS { override fun apply(a: Int, b: Int) = a - b };
            abstract fun apply(a: Int, b: Int): Int
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
      val cFile = outputDir.resolve("com_example_Op.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `parameterised enum generates no field reads in C`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "ParamEnum.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithJS2HostBridge

          @WithJS2HostBridge
          enum class ParamEnum(
            private val secret: String,
            val tags: List<String>,
            val action: () -> Unit,
          ) {
            A("s", listOf("x"), {}),
          }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
      val cFile = outputDir.resolve("com_example_ParamEnum.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")
      val content = cFile.readText()
      // An enum crosses as an ordinal, never as a field bag: none of the entry's properties are read.
      assertFalse(content.contains("getProperty(rt, \"secret\")"), content)
      assertFalse(content.contains("getProperty(rt, \"tags\")"), content)
      assertFalse(content.contains("getProperty(rt, \"action\")"), content)
      assertFalse(content.contains("secret_1"), content)
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `host2js enum generates ordinal-based convertToJs`() {
    val outputDir = createTempDirectory("zipline-bridge-test")
    try {
      val result = compileWithCOutputDir(
        sourceFile = SourceFile.kotlin(
          "Color.kt",
          """
          package com.example

          import app.cash.zipline.bridge.support.WithHost2JSBridge

          @WithHost2JSBridge
          enum class Color { RED, GREEN, BLUE }
          """,
        ),
        cOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)
      val cFile = outputDir.resolve("com_example_Color.cpp").toFile()
      assertTrue(cFile.exists(), "Expected C file at ${cFile.absolutePath}")
      val content = cFile.readText()
      // The ordinal method is cached, called on the entry, and handed to the guest's create_<fqn>
      // factory — never a field-stamped lookalike.
      assertTrue(content.contains("_ordinalMethod = jsiGetMethodIdQuiet(env, _cls, \"ordinal\", \"()I\")"), content)
      assertTrue(content.contains("env->CallIntMethod(self, _ordinalMethod)"), content)
      assertTrue(
        content.contains(
          "jsiHost2JsCallCreateFactory(env, rt, \"com.example.Color\", " +
            "\"create_com_example_Color\", jsi::Value(static_cast<int>(ordinal)))",
        ),
        content,
      )
      assertFalse(content.contains("is an enum"), content)
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

  /**
   * The native host constructs through the generated companion factory rather than calling the
   * constructor itself, because the default expression for a parameter belongs to the code that
   * declares the class — and on a native host, that is the host. The bridge's job shrinks to saying
   * which parameters arrived.
   */
  @Test
  fun `a defaulted constructor parameter is constructed through bridgeCreate`() {
    val content = generatedBridge(
      "Defaults.kt",
      "@WithJS2HostBridge class Defaults(val a: Int = 1, val b: String = \"x\", val c: Int)",
      "Defaults",
    )

    // One factory call per bridge, and nothing else constructs the class.
    assertEquals(1, Regex("\\.bridgeCreate\\(").findAll(content).count(), content)
    assertTrue(content.contains("_bridge.bridgeCreate(_mask0, a = a, b = b, c = c)"), content)

    // The flags come from what the guest sent. A defaulted parameter's bit is its position within
    // the word, and it is SET WHEN THE PARAMETER DID NOT ARRIVE: a set bit is the factory's
    // instruction to evaluate the declared default. A required parameter has no bit at all.
    assertTrue(
      content.contains("val _mask0 = (if (aSent) 0 else 1) or (if (bSent) 0 else 2)"),
      content,
    )
    assertTrue(
      content.contains("val bSent = HermesBridge_getValueTag(ctx, bRef) != TAG_UNDEFINED"),
      content,
    )

    // Absence is not a type error for a defaulted parameter, so the guard fires only when something
    // arrived — and a wrong type still names the field, in the same words the C decoder uses.
    assertTrue(
      content.contains(
        "if (aTag != TAG_UNDEFINED && aTag != 1 && aTag != 2) bridgeThrowFieldMismatch",
      ),
      content,
    )
    // A required parameter keeps the loud read: nothing arrived is still nothing acceptable.
    assertTrue(
      content.contains("if (cTag != 1 && cTag != 2) bridgeThrowFieldMismatch"),
      content,
    )
    // A defaulted reference decodes to a null placeholder, which the factory ignores.
    assertTrue(
      content.contains("val b = if (HermesBridge_getValueTag(ctx, bRef) == TAG_UNDEFINED"),
      content,
    )
  }

  /** No `when (mask)` tree and no re-expanded defaults: the factory holds them, the bridge does not. */
  @Test
  fun `the native bridge branches on presence instead of expanding defaults`() {
    val content = generatedBridge(
      "Defaults.kt",
      "@WithJS2HostBridge class Defaults(val a: Int = 1, val b: String = \"x\")",
      "Defaults",
    )
    assertFalse(content.contains("when (_mask"), content)
    assertFalse(content.contains("when (mask"), content)
    // The default expressions are not in the generated file at all: only the factory evaluates them.
    assertFalse(content.contains("\"x\""), content)
  }

  /** Past 32 parameters the factory takes a second flag word, and parameter 32 lands in it. */
  @Test
  fun `a signature wider than one flag word passes two mask words to the factory`() {
    val params = (0 until 40).joinToString(", ") { "val p$it: Int = $it" }
    val content = generatedBridge("Wide.kt", "@WithJS2HostBridge class Wide($params)", "Wide")
    assertTrue(content.contains("val _bridge = Wide.Bridge"), content)
    assertTrue(content.contains("_bridge.bridgeCreate(_mask0, _mask1, p0 = p0,"), content)
    assertTrue(content.contains("val _mask1 = (if (p32Sent) 0 else 1) or (if (p33Sent) 0 else 2)"), content)
  }

  /**
   * A host converter builds its value through the `Bridge` holder, which exists only in the compilation
   * that compiles the class. When a host target resolves a declaring module built without the plugin --
   * which is what an iOS build of a Redwood generated module did -- the generated file fails with a bare
   * "unresolved reference 'Bridge'", and nothing in that message says what to change. So the file says
   * it, on the lines directly above the statement that fails, where the compiler and Xcode will show it.
   */
  @Test
  fun `the generated converter explains a holder that does not resolve`() {
    val content = generatedBridge(
      "Bordered.kt",
      "@WithJS2HostBridge class Bordered(val color: Int = 1, val shape: Int = 2)",
      "Bordered",
    )

    val lines = content.lines()
    // The file imports the class, so the lookup is unqualified: assert on what is emitted, not on
    // what the package would allow.
    val call = lines.indexOfFirst { it.contains("val _bridge = ") && it.contains("Bordered.Bridge") }
    assertTrue(call > 0, "no holder lookup in:\n$content")
    val hint = lines.indexOfFirst { it.contains("unresolved reference 'Bridge'") }
    assertTrue(hint > 0, "no explanation of the failure in:\n$content")
    assertTrue(
      lines.subList(maxOf(0, call - 8), call).any { it.contains("without the zipline bridge compiler plugin") },
      "the explanation must sit directly above the statement that fails:\n$content",
    )
    // The remedy has to name the thing to go and change, not just the symptom.
    assertTrue(
      lines[hint - 1].contains("com.example.Bordered"),
      "the hint must name the class whose module needs the plugin: ${lines[hint - 1]}",
    )
  }

  /**
   * The factories object is signed with the package of the module's first source file, so a fixed name
   * makes two modules that share a package declare one signature -- which is how `:wb:live`'s guest
   * bundle failed to link two Clive modules that both declare in `ru.wildberries.clive.compose`:
   * "IrClassSymbolImpl is already bound". A stable per-module key is the whole fix, so it is pinned.
   */
  @Test
  fun `the runtime factories object is named for its module`() {
    val main = runtimeFactoriesClassName(":wb:nodes:main:common")
    val designSystem = runtimeFactoriesClassName(":wb:nodes:design-system:common")
    assertNotEquals(main, designSystem, "modules sharing a package must not share the signature")
    assertEquals(main, runtimeFactoriesClassName(":wb:nodes:main:common"), "the name must be stable")
    assertTrue(
      Regex("^[A-Za-z_][A-Za-z0-9_]*$").matches(main),
      "must be a legal JS identifier, was: $main",
    )
    // Two modules named `common` in different places still differ.
    assertNotEquals(
      runtimeFactoriesClassName(":core:nodes:foundation:common"),
      runtimeFactoriesClassName(":wb:features:shelfs:common"),
    )
  }

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
    assertTrue(content.contains("bridgeJsString(ctx, nameRef)"))
    assertTrue(content.contains("val name = nameStr!!"))

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
       assertTrue(content.contains("bridgeJsString(ctx, nameRef)"))
       assertTrue(content.contains("bridgeJsString(ctx, "))

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
       assertTrue(content.contains("bridgeJsString(ctx, "))
       // The helper owns the buffer it mallocs and frees, so the generated file must not free it
       // too: doing that again would be a double free, and the old code freed it here because the
       // conversion was inlined.
       assertFalse(content.contains("posix.free("))
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
       assertTrue(content.contains("bridgeJsString(ctx, element)"))
       assertTrue(content.contains("bridgeJsString(ctx, "))
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
      assertTrue(content.contains("host bridge: ordinal \$ordinal is out of range for com.example.Color"))
     } finally {
       outputDir.toFile().deleteRecursively()
     }
  }

  @Test
  fun `parameterised enum generates no field reads in native bridge`() {
    val outputDir = createTempDirectory("zipline-bridge-native-test")
    try {
      val result = compileWithNativeOutputDir(
        sourceFile = SourceFile.kotlin(
          "ParamEnum.kt",
          """
          package com.example
          import app.cash.zipline.bridge.support.WithJS2HostBridge
          @WithJS2HostBridge
          enum class ParamEnum(
            private val secret: String,
            val tags: List<String>,
            val action: () -> Unit,
          ) {
            A("s", listOf("x"), {}),
          }
          """,
        ),
        nativeOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val ktFile = outputDir.resolve("com_example_ParamEnum_bridge_native.kt").toFile()
      assertTrue(ktFile.exists())

      val content = ktFile.readText()

      // Enum ordinal extraction, and none of the entry's properties are read: the receiver resolves
      // its own singleton, which already holds the values its own revision initialized.
      assertTrue(content.contains("jsEnumOrdinal(ctx, jsValHandle)"))
      assertTrue(content.contains("ParamEnum.entries.getOrNull(ordinal)"))
      assertFalse(content.contains("HermesBridge_createHandle(ctx, jsValHandle, \"secret_1\")"), content)
      assertFalse(content.contains("HermesBridge_createHandle(ctx, jsValHandle, \"tags\")"), content)
      assertFalse(content.contains("HermesBridge_createHandle(ctx, jsValHandle, \"action\")"), content)
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }

  @Test
  fun `same simple name in another nesting level gets its own native bridge`() {
    val outputDir = createTempDirectory("zipline-bridge-native-test")
    try {
      val result = compileWithNativeOutputDir(
        sourceFile = SourceFile.kotlin(
          "SameSimpleName.kt",
          """
          package com.example
          import app.cash.zipline.bridge.support.WithJS2HostBridge
          @WithJS2HostBridge
          data class Alignment(val value: Int)
          class LineHeightStyle {
            @WithJS2HostBridge
            data class Alignment(val value: Int)
          }
          """,
        ),
        nativeOutputDir = outputDir.toString(),
      )
      assertEquals(KotlinCompilation.ExitCode.OK, result.exitCode, result.messages)

      val topLevel = outputDir.resolve("com_example_Alignment_bridge_native.kt").toFile()
      val nested = outputDir.resolve("com_example_LineHeightStyle_Alignment_bridge_native.kt").toFile()
      assertTrue(topLevel.exists(), "Expected ${topLevel.absolutePath}")
      assertTrue(nested.exists(), "Expected ${nested.absolutePath}")

      val topContent = topLevel.readText()
      val nestedContent = nested.readText()

      // Distinct converter functions...
      assertTrue(topContent.contains("public fun com_example_Alignment_toKotlin("), topContent)
      assertTrue(nestedContent.contains("public fun com_example_LineHeightStyle_Alignment_toKotlin("), nestedContent)

      // ...registered under distinct FQNs, which is also the key the guest's registration uses.
      assertTrue(
        topContent.contains(
          "registerBridge(\"com.example.Alignment\", staticCFunction(::com_example_Alignment_toKotlin))",
        ),
        topContent,
      )
      assertTrue(
        nestedContent.contains(
          "registerBridge(\"com.example.LineHeightStyle.Alignment\", " +
            "staticCFunction(::com_example_LineHeightStyle_Alignment_toKotlin))",
        ),
        nestedContent,
      )

      // The nested class is built through its enclosing class name, which the import must bring in.
      assertTrue(nestedContent.contains("import com.example.LineHeightStyle"), nestedContent)
      assertTrue(nestedContent.contains("LineHeightStyle.Alignment("), nestedContent)
    } finally {
      outputDir.toFile().deleteRecursively()
    }
  }
}
