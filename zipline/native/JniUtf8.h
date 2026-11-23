#ifndef JNI_UTF8_H
#define JNI_UTF8_H

#include <jni.h>

#include <cstdint>
#include <string>

// JNI's GetStringUTFChars/NewStringUTF use Java MODIFIED UTF-8 (surrogate
// pairs for supplementary characters, two-byte encoding for NUL), while
// Hermes's jsi::String::createFromUtf8/utf8() use standard UTF-8. Passing
// one to the other mangles non-BMP characters and corrupts JSON payloads.
// These helpers convert through UTF-16, which both sides agree on.

namespace zipline {

inline void appendUtf8(std::string& out, uint32_t cp) {
  if (cp < 0x80) {
    out += static_cast<char>(cp);
  } else if (cp < 0x800) {
    out += static_cast<char>(0xC0 | (cp >> 6));
    out += static_cast<char>(0x80 | (cp & 0x3F));
  } else if (cp < 0x10000) {
    out += static_cast<char>(0xE0 | (cp >> 12));
    out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
    out += static_cast<char>(0x80 | (cp & 0x3F));
  } else {
    out += static_cast<char>(0xF0 | (cp >> 18));
    out += static_cast<char>(0x80 | ((cp >> 12) & 0x3F));
    out += static_cast<char>(0x80 | ((cp >> 6) & 0x3F));
    out += static_cast<char>(0x80 | (cp & 0x3F));
  }
}

// Converts a Java string to standard UTF-8. Unpaired surrogates are
// replaced with U+FFFD.
// JNI forbids almost every call while a Java exception is pending: ART's checked JNI aborts the
// process -- "JNI DETECTED ERROR IN APPLICATION: JNI NewObjectV called with pending exception" --
// and the trace it prints names this conversion, not the code that threw. Both helpers here are
// reached straight after JavaScript has called back into Java, which is exactly when an exception
// can be pending, so each one refuses and lets the pending exception reach Kotlin on its own.
inline std::string jniStringToUtf8(JNIEnv* env, jstring javaString) {
  if (!javaString) return std::string();
  if (env->ExceptionCheck()) return std::string();
  const jchar* chars = env->GetStringChars(javaString, nullptr);
  if (!chars) return std::string();
  const jsize length = env->GetStringLength(javaString);
  std::string out;
  out.reserve(static_cast<size_t>(length));
  for (jsize i = 0; i < length; i++) {
    uint32_t c = chars[i];
    if (c >= 0xD800 && c <= 0xDBFF && i + 1 < length) {
      uint32_t lo = chars[i + 1];
      if (lo >= 0xDC00 && lo <= 0xDFFF) {
        appendUtf8(out, 0x10000 + ((c - 0xD800) << 10) + (lo - 0xDC00));
        i++;
        continue;
      }
    }
    if (c >= 0xD800 && c <= 0xDFFF) {
      appendUtf8(out, 0xFFFD);
    } else {
      appendUtf8(out, c);
    }
  }
  env->ReleaseStringChars(javaString, chars);
  return out;
}

// Converts standard UTF-8 to a Java string. Invalid byte sequences are
// replaced with U+FFFD.
inline jstring utf8ToJniString(JNIEnv* env, const std::string& utf8) {
  if (env->ExceptionCheck()) return nullptr;
  // char16_t, not jchar: jchar is a typedef for uint16_t, and std::char_traits is only
  // defined by the standard for char, wchar_t, char8_t, char16_t and char32_t. libc++ used
  // to provide a non-standard base template for other types; NDK r28 (libc++ 19) removed it,
  // which made this header uncompilable for any consumer using that NDK.
  std::u16string utf16;
  utf16.reserve(utf8.size());
  size_t i = 0;
  while (i < utf8.size()) {
    unsigned char c = static_cast<unsigned char>(utf8[i]);
    uint32_t cp;
    size_t extra;
    if (c < 0x80) {
      cp = c;
      extra = 0;
    } else if ((c & 0xE0) == 0xC0) {
      cp = c & 0x1F;
      extra = 1;
    } else if ((c & 0xF0) == 0xE0) {
      cp = c & 0x0F;
      extra = 2;
    } else if ((c & 0xF8) == 0xF0) {
      cp = c & 0x07;
      extra = 3;
    } else {
      cp = 0xFFFD;
      extra = 0;
    }
    if (extra > 0) {
      bool valid = i + extra < utf8.size();
      for (size_t j = 1; valid && j <= extra; j++) {
        unsigned char cc = static_cast<unsigned char>(utf8[i + j]);
        if ((cc & 0xC0) != 0x80) {
          valid = false;
        } else {
          cp = (cp << 6) | (cc & 0x3F);
        }
      }
      if (!valid) {
        cp = 0xFFFD;
        extra = 0;
      }
    }
    i += extra + 1;
    if (cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
      cp = 0xFFFD;
    }
    if (cp < 0x10000) {
      utf16 += static_cast<char16_t>(cp);
    } else {
      cp -= 0x10000;
      utf16 += static_cast<char16_t>(0xD800 + (cp >> 10));
      utf16 += static_cast<char16_t>(0xDC00 + (cp & 0x3FF));
    }
  }
  return env->NewString(
      reinterpret_cast<const jchar*>(utf16.data()), static_cast<jsize>(utf16.size()));
}

} // namespace zipline

#endif // JNI_UTF8_H
