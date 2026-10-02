# Builds the combined JNI dylib that merges zipline's Hermes glue into
# libhermesvm.so (Android) / libhermesvm.dylib (macOS).
#
# macOS configure example (called from host-build.sh):
#   cmake -S . -B build -G Ninja \
#         -DHERMES_SRC=../hermes \
#         -DHERMES_STATIC_BUILD_DIR=../hermes/build_macos_static \
#         -DJAVA_HOME=$(/usr/libexec/java_home)
#   cmake --build build --target hermesvm
#
# Android: AGP invokes this directly.

cmake_minimum_required(VERSION 3.21)
project(zipline_hermes_jni C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

if(NOT DEFINED HERMES_SRC)
  set(HERMES_SRC "${CMAKE_CURRENT_SOURCE_DIR}/../hermes")
endif()
if(NOT EXISTS "${HERMES_SRC}/CMakeLists.txt")
  message(FATAL_ERROR "HERMES_SRC=${HERMES_SRC} does not look like the Hermes source tree.")
endif()

# Locate jni.h. We don't use find_package(JNI) because:
#   - On Apple, the toolchain already provides jni.h via the sysroot.
#   - On Android NDK r27+, the toolchain's FindJNI fails because jni_md.h
#     was removed and the AWT/JVM libraries aren't bundled.
if(NOT JAVA_HOME AND DEFINED ENV{JAVA_HOME})
  set(JAVA_HOME "$ENV{JAVA_HOME}")
endif()

if(ANDROID)
  set(JNI_INCLUDE_DIRS "")
elseif(CMAKE_SYSTEM_NAME STREQUAL "iOS")
  set(JNI_INCLUDE_DIRS "")
elseif(JAVA_HOME AND EXISTS "${JAVA_HOME}/include/jni.h")
  set(JNI_INCLUDE_DIRS "${JAVA_HOME}/include")
  # The macOS JDK uses include/darwin (for macOS hosts) and include/linux
  # (for Linux hosts). When cross-compiling from macOS to Linux the JDK
  # we invoke is still the macOS one, so accept whichever directory exists
  # (darwin first, linux second) so the jni_md.h macro-only header resolves.
  if(APPLE AND EXISTS "${JAVA_HOME}/include/darwin")
    list(APPEND JNI_INCLUDE_DIRS "${JAVA_HOME}/include/darwin")
  elseif(EXISTS "${JAVA_HOME}/include/linux")
    list(APPEND JNI_INCLUDE_DIRS "${JAVA_HOME}/include/linux")
  elseif(EXISTS "${JAVA_HOME}/include/darwin")
    # Cross-compilation from macOS to Linux: the JDK's own headers are
    # platform-agnostic macros, so the darwin variant works too.
    list(APPEND JNI_INCLUDE_DIRS "${JAVA_HOME}/include/darwin")
  endif()
else()
  find_package(JNI REQUIRED)
endif()
if(NOT ANDROID AND NOT CMAKE_SYSTEM_NAME STREQUAL "iOS" AND NOT JNI_INCLUDE_DIRS)
  message(FATAL_ERROR "JNI include dirs not found. Set JAVA_HOME or fix the toolchain.")
endif()

# Build a lean Hermes without the JS compiler. Saves ~1MB per ABI.
# The compile() JNI method will throw UnsupportedOperationException when lean.
# Set -DHERMESVM_LEAN=TRUE to enable.
set(HERMESVM_LEAN OFF CACHE BOOL "Build lean Hermes without the JS compiler")

# Build a single iOS static archive that bundles Hermes + zipline glue.
# When ON, the iOS branch produces libhermesvm.a (suitable for embedding
# in a Kotlin/Native klib via cinterop staticLibraries) instead of a
# dynamic hermesvm.framework.
set(HERMES_IOS_STATIC OFF CACHE BOOL "Build iOS as a merged static archive")

# Glue sources shared by all platforms (engine facade, base context,
# intrinsics). Platform lists below extend this; do not duplicate entries.
set(GLUE_COMMON_SOURCES
  ${HERMES_SRC}/../hermes-core.cpp
  ${HERMES_SRC}/../ContextBase.cpp
  ${HERMES_SRC}/../CdpSession.cpp
  ${HERMES_SRC}/../CdpSession.h
  ${HERMES_SRC}/../hermes-ios/hermes-ios.cpp
  ${HERMES_SRC}/../InboundCallChannel.cpp
  ${HERMES_SRC}/../common/JsIntrinsics.cpp
)

# CompileJS.cpp provides hermes::compileJS() for on-device JS compilation.
# Exclude it when building lean (compile() won't be available).
if(NOT HERMESVM_LEAN)
  list(APPEND GLUE_COMMON_SOURCES ${HERMES_SRC}/API/hermes/CompileJS.cpp)
endif()

# Core glue source files (shared across JNI platforms, but NOT JNI-specific).
# These are used for macOS host builds.
set(GLUE_CORE_SOURCES
  ${GLUE_COMMON_SOURCES}
  ${HERMES_SRC}/../OutboundCallChannel.cpp
  ${HERMES_SRC}/../ExceptionThrowers.cpp
)

# JNI-specific glue source files (Android only - not needed for iOS/macOS).
set(GLUE_JNI_SOURCES
  ${HERMES_SRC}/../CdpJni.cpp
  ${HERMES_SRC}/../CdpJni.h
  ${HERMES_SRC}/../ContextJni.cpp
  ${HERMES_SRC}/../ContextJni.h
  ${HERMES_SRC}/../OutboundCallChannelJni.cpp
  ${HERMES_SRC}/../hermes-jni.cpp
)

# iOS-specific glue source files (no JNI dependencies).
set(GLUE_IOS_SOURCES
  ${GLUE_COMMON_SOURCES}
)

# Base include dirs for glue sources.
set(_GLUE_INCLUDES
  "${HERMES_SRC}/API"
  "${HERMES_SRC}/API/jsi"
  "${HERMES_SRC}/public"
  "${HERMES_SRC}/include"
  "${HERMES_SRC}/external/llvh/include"
  "${HERMES_SRC}/external/llvh/gen/include"
  "${HERMES_SRC}/../"
  "${HERMES_SRC}/../hermes-ios/"
  ${JNI_INCLUDE_DIRS}
)

if(ANDROID)
  # ---- Android build ----
  set(HERMES_IS_ANDROID TRUE)
  set(ANDROID_STL "c++_static")
  set(HERMES_ENABLE_TEST_SUITE OFF)
  # The debugger (incl. the CDP agent in API/hermes/cdp) is enabled so Kotlin/JS
  # code can be debugged from Chrome DevTools. Lean (prod) builds exclude the JS
  # parser the debugger needs, so it is disabled there. Gradle passes
  # -DHERMES_ENABLE_DEBUGGER explicitly based on -PhermesProd; this is the
  # fallback default when it is not passed.
  if(NOT DEFINED HERMES_ENABLE_DEBUGGER)
    if(HERMESVM_LEAN)
      set(HERMES_ENABLE_DEBUGGER OFF)
    else()
      set(HERMES_ENABLE_DEBUGGER ON)
    endif()
  endif()
  set(HERMES_ENABLE_TOOLS OFF)
  set(HERMES_ENABLE_INTL FALSE)
  set(HERMES_UNICODE_LITE TRUE)
  set(HERMES_BUILD_SHARED_JSI OFF)
  set(HERMESVM_HEAP_HV_MODE "HEAP_HV_PREFER32")
  set(HERMES_STATIC_LINK_INTL TRUE)
  set(JSI_DIR "${HERMES_SRC}/API/jsi")
  set(HERMES_ENABLE_UNICODE_REGEXP_PROPERTY_ESCAPES OFF)

  if(NOT DEFINED IMPORT_HOST_COMPILERS)
    message(FATAL_ERROR
      "IMPORT_HOST_COMPILERS not set. The Gradle preBuild task should have "
      "produced native/hermes/build_host_hermesc/ImportHostCompilers.cmake.")
  endif()
  file(TO_CMAKE_PATH "${IMPORT_HOST_COMPILERS}" IMPORT_HOST_COMPILERS_CMAKE)
  set(IMPORT_HOST_COMPILERS "${IMPORT_HOST_COMPILERS_CMAKE}" CACHE FILEPATH "")

  set(_hermes_subdir "${CMAKE_CURRENT_BINARY_DIR}/_hermes")
  file(MAKE_DIRECTORY "${_hermes_subdir}")
  add_subdirectory("${HERMES_SRC}" "${_hermes_subdir}")

  # Add glue sources to hermesvm via a static lib, then link it in.
  # Android includes both core and JNI sources.
  add_library(zipline_glue STATIC ${GLUE_CORE_SOURCES} ${GLUE_JNI_SOURCES})
  target_include_directories(zipline_glue PRIVATE
    ${_GLUE_INCLUDES}
    "${_hermes_subdir}/external/llvh/include")
  if(HERMESVM_LEAN)
    target_compile_definitions(zipline_glue PRIVATE HERMESVM_LEAN=1)
    # Hermes CMake links libhermesvmlean_a.a with --whole-archive into
    # hermesvmlean, so Hermes symbols are included. We just need to add
    # zipline_glue with --whole-archive so all JNI symbols are included.
    target_link_libraries(hermesvmlean PRIVATE
      "-Wl,--whole-archive"
      zipline_glue
      "-Wl,--no-whole-archive")
  else()
    # Full: link hermesvm_a (includes JIT, parser, optimizer).
    # Link zipline_glue directly with --whole-archive so all JNI symbols
    # are included.
    target_link_libraries(hermesvm PRIVATE
      "-Wl,--whole-archive"
      zipline_glue
      "-Wl,--no-whole-archive"
      "${_hermes_subdir}/jsi/libjsi.a"
      "${_hermes_subdir}/external/boost/boost_1_86_0/libs/context/libboost_context.a")
  endif()

  if(HERMES_ENABLE_DEBUGGER)
    # Glue sources include AsyncDebuggerAPI.h / CDP headers whose declarations
    # change based on this define; keep it in sync with the Hermes build.
    target_compile_definitions(zipline_glue PRIVATE HERMES_ENABLE_DEBUGGER=1)
    if(HERMESVM_LEAN AND TARGET hermesParser_obj)
      # The CDP agent (part of hermesapi_obj inside hermesvmlean_a) parses CDP
      # JSON messages with hermesParser, which lean builds otherwise exclude.
      target_link_libraries(hermesvmlean PRIVATE hermesParser_obj)
    endif()
  endif()

  # Set page size for Android Hermes builds
  if(ANDROID)
    set_target_properties(hermesvmlean PROPERTIES
      LINK_FLAGS "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
    # Prevent gc-sections from stripping unreferenced bridge registry symbols
    # that are called from other .so files at runtime.
    target_link_options(hermesvmlean PRIVATE "-Wl,--undefined=addBridgeEntry")
    target_link_options(hermesvmlean PRIVATE "-Wl,--undefined=addBridgeInit")
    target_link_options(hermesvmlean PRIVATE "-Wl,--undefined=init_all")
    target_link_options(hermesvmlean PRIVATE "-Wl,--undefined=register_all")
    if(NOT HERMESVM_LEAN)
      set_target_properties(hermesvm PROPERTIES
        LINK_FLAGS "-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")
      target_link_options(hermesvm PRIVATE "-Wl,--undefined=addBridgeEntry")
      target_link_options(hermesvm PRIVATE "-Wl,--undefined=addBridgeInit")
      target_link_options(hermesvm PRIVATE "-Wl,--undefined=init_all")
      target_link_options(hermesvm PRIVATE "-Wl,--undefined=register_all")
    endif()
  endif()

  # Exclude the unused shared lib so only the needed target is built.
  if(HERMESVM_LEAN)
    if(TARGET hermesvm)
      set_target_properties(hermesvm PROPERTIES EXCLUDE_FROM_ALL TRUE)
    endif()
  else()
    if(TARGET hermesvmlean)
      set_target_properties(hermesvmlean PROPERTIES EXCLUDE_FROM_ALL TRUE)
    endif()
  endif()

elseif(CMAKE_SYSTEM_NAME STREQUAL "iOS")
  # ---- iOS build (device + simulator) ----
  set(HERMES_IS_MOBILE_BUILD TRUE)
  set(HERMES_ENABLE_TEST_SUITE OFF)
  # The CDP debugger (incl. the CDP agent in API/hermes/cdp) is enabled for
  # non-lean builds only: lean excludes the JS parser, which the debugger
  # needs both for Runtime.evaluate and for parsing CDP JSON. CDP debugging
  # therefore requires the full engine (-PhermesProd=false). Gradle passes
  # -DHERMES_ENABLE_DEBUGGER explicitly based on -PhermesProd; this is the
  # fallback default when it is not passed.
  if(NOT DEFINED HERMES_ENABLE_DEBUGGER)
    if(HERMESVM_LEAN)
      set(HERMES_ENABLE_DEBUGGER OFF)
    else()
      set(HERMES_ENABLE_DEBUGGER ON)
    endif()
  endif()
  set(HERMES_ENABLE_TOOLS OFF)
  set(HERMES_ENABLE_INTL FALSE)
  set(HERMES_UNICODE_LITE TRUE)
  set(HERMES_BUILD_SHARED_JSI OFF)
  # When producing a static archive we do not need an Apple framework.
  if(HERMES_IOS_STATIC)
    set(HERMES_BUILD_APPLE_FRAMEWORK OFF)
    # compile()/evaluate() must work on iOS (dev-mode loadJsModule and the
    # host test suite compile JS on-device), so the static archive is built
    # non-lean. Set -DHERMESVM_LEAN=ON to trade that for ~1 MB smaller klibs.
  else()
    set(HERMES_BUILD_APPLE_FRAMEWORK ON)
  endif()
  set(JSI_DIR "${HERMES_SRC}/API/jsi")
  set(HERMES_ENABLE_UNICODE_REGEXP_PROPERTY_ESCAPES OFF)

  if(NOT DEFINED IMPORT_HOST_COMPILERS)
    set(_hermes_host_dir "${CMAKE_CURRENT_SOURCE_DIR}/../hermes/build_host_hermesc")
    if(EXISTS "${_hermes_host_dir}/ImportHostCompilers.cmake")
      set(IMPORT_HOST_COMPILERS "${_hermes_host_dir}/ImportHostCompilers.cmake" CACHE FILEPATH "")
    endif()
  else()
    file(TO_CMAKE_PATH "${IMPORT_HOST_COMPILERS}" IMPORT_HOST_COMPILERS_CMAKE)
    set(IMPORT_HOST_COMPILERS "${IMPORT_HOST_COMPILERS_CMAKE}" CACHE FILEPATH "")
  endif()

  # Build Hermes from source with the current iOS toolchain settings.
  set(_hermes_subdir "${CMAKE_CURRENT_BINARY_DIR}/_hermes")
  file(MAKE_DIRECTORY "${_hermes_subdir}")
  add_subdirectory("${HERMES_SRC}" "${_hermes_subdir}")

  # iOS glue: only iOS-specific sources (no JNI dependencies).
  add_library(zipline_ios_glue STATIC ${GLUE_IOS_SOURCES})
  target_include_directories(zipline_ios_glue PRIVATE
    ${_GLUE_INCLUDES}
    "${_hermes_subdir}/external/llvh/include")
  if(HERMESVM_LEAN)
    target_compile_definitions(zipline_ios_glue PRIVATE HERMESVM_LEAN=1)
  endif()
  if(HERMES_ENABLE_DEBUGGER)
    # Glue sources include AsyncDebuggerAPI.h / CDP headers whose declarations
    # change based on this define; keep it in sync with the Hermes build.
    target_compile_definitions(zipline_ios_glue PRIVATE HERMES_ENABLE_DEBUGGER=1)
  endif()

  if(HERMES_IOS_STATIC)
    # Merge the zipline glue and the Hermes static archives into a single
    # umbrella archive. This archive is embedded in the iOS Kotlin/Native
    # klib via cinterop staticLibraries, so consumers do not need a separate
    # hermesvm.framework.

    if(TARGET hermesvm)
      set_target_properties(hermesvm PROPERTIES EXCLUDE_FROM_ALL TRUE)
    endif()
    if(TARGET hermesvmlean)
      set_target_properties(hermesvmlean PROPERTIES EXCLUDE_FROM_ALL TRUE)
    endif()

    if(HERMESVM_LEAN)
      set(_HERMES_VM_A      "${_hermes_subdir}/lib/libhermesvmlean_a.a")
      set(_HERMES_VM_TARGET hermesvmlean_a)
    else()
      set(_HERMES_VM_A      "${_hermes_subdir}/lib/libhermesvm_a.a")
      set(_HERMES_VM_TARGET hermesvm_a)
    endif()

    set(_HERMES_IOS_STATIC_OUT "${CMAKE_CURRENT_BINARY_DIR}/libhermesvm.a")
    set(_JSI_A            "${_hermes_subdir}/jsi/libjsi.a")
    set(_BOOST_CTX_A      "${_hermes_subdir}/external/boost/boost_1_86_0/libs/context/libboost_context.a")
    set(_HERMES_EXPORTS   "${CMAKE_CURRENT_SOURCE_DIR}/../hermes-ios.exports")
    set(_PRELINKED_O      "${CMAKE_CURRENT_BINARY_DIR}/libhermesvm_prelinked.o")

    # Newer Xcode ld requires an explicit -platform_version for -r links.
    if(CMAKE_OSX_SYSROOT MATCHES "[Ss]imulator")
      set(_LD_PLATFORM "ios-simulator")
    else()
      set(_LD_PLATFORM "ios")
    endif()

    # Prelink all archives into a single relocatable object that keeps only
    # the public C API (hermes-ios.exports) global, then strip local symbols.
    # This folds duplicated COMDAT code and slims the symbol table, bringing
    # the umbrella archive from ~5 MB down to ~2.6 MB (dylib is ~1.8 MB).
    add_custom_command(
      OUTPUT "${_HERMES_IOS_STATIC_OUT}"
      COMMAND ld -r -arch ${CMAKE_OSX_ARCHITECTURES} -all_load
        -platform_version ${_LD_PLATFORM} ${CMAKE_OSX_DEPLOYMENT_TARGET} ${CMAKE_OSX_DEPLOYMENT_TARGET}
        -exported_symbols_list "${_HERMES_EXPORTS}"
        -o "${_PRELINKED_O}"
        "$<TARGET_FILE:zipline_ios_glue>"
        "${_HERMES_VM_A}"
        "${_JSI_A}"
        "${_BOOST_CTX_A}"
      COMMAND strip -u -x -S "${_PRELINKED_O}"
      COMMAND libtool -static -o "${_HERMES_IOS_STATIC_OUT}" "${_PRELINKED_O}"
      DEPENDS zipline_ios_glue ${_HERMES_VM_TARGET} jsi boost_context "${_HERMES_EXPORTS}"
      COMMENT "Merging + prelinking iOS static archive for embedding in klib"
      VERBATIM
    )
    add_custom_target(hermesvm_static ALL DEPENDS "${_HERMES_IOS_STATIC_OUT}")
  else()
    # Traditional framework build: force-load the glue into the shared Hermes
    # framework so all JNI/Native entry points are exported.
    if(HERMESVM_LEAN)
      target_link_libraries(hermesvmlean PRIVATE
        "-Wl,-force_load"
        zipline_ios_glue)
    else()
      target_link_libraries(hermesvm PRIVATE
        "-Wl,-force_load"
        zipline_ios_glue
        "-Wl,-force_load"
        "${_hermes_subdir}/jsi/libjsi.a"
        "-Wl,-force_load"
        "${_hermes_subdir}/external/boost/boost_1_86_0/libs/context/libboost_context.a")
    endif()

    # Exclude the unused hermesvm target so we only build the one we need.
    if(HERMESVM_LEAN)
      if(TARGET hermesvm)
        set_target_properties(hermesvm PROPERTIES EXCLUDE_FROM_ALL TRUE)
      endif()
    else()
      if(TARGET hermesvmlean)
        set_target_properties(hermesvmlean PROPERTIES EXCLUDE_FROM_ALL TRUE)
      endif()
    endif()
  endif()

elseif(DEFINED HERMES_STATIC_BUILD_DIR)
  # ---- macOS/Linux host build with prebuilt static libs ----
  set(HERMES_BUILD_SHARED_JSI OFF)
  set(HERMES_ENABLE_TEST_SUITE OFF)
  # Keep in sync with the -DHERMES_ENABLE_DEBUGGER flag used to produce the
  # prebuilt static libs (see buildHermes*Static Gradle tasks).
  set(HERMES_ENABLE_DEBUGGER ON)
  set(HERMES_ENABLE_TOOLS OFF)
  set(HERMES_ENABLE_INTL FALSE)
  set(HERMES_UNICODE_LITE TRUE)

  # Point Hermes CMake at the prebuilt host compilers so it doesn't try
  # to build hermesc (needed for InternalJavaScript step).
  set(_hermes_host_dir "${HERMES_STATIC_BUILD_DIR}/../build_host_hermesc")
  if(EXISTS "${_hermes_host_dir}/ImportHostCompilers.cmake")
    set(IMPORT_HOST_COMPILERS "${_hermes_host_dir}/ImportHostCompilers.cmake" CACHE FILEPATH "")
  endif()

  # Thin fat static archives to current arch.
  if(APPLE AND CMAKE_OSX_ARCHITECTURES)
    set(_fat_archs "${CMAKE_OSX_ARCHITECTURES}")
  else()
    set(_fat_archs "${CMAKE_SYSTEM_PROCESSOR}")
  endif()
  list(LENGTH _fat_archs _n)
  if(_n GREATER 1)
    message(FATAL_ERROR
      "This glue build only supports a single -arch (got '${_fat_archs}'). "
      "Build twice with -DCMAKE_OSX_ARCHITECTURES=<one-arch> each, then lipo the outputs.")
  endif()
  set(_arch "${_fat_archs}")
  set(_thin_dir "${CMAKE_CURRENT_BINARY_DIR}/_thin_${_arch}")
  file(MAKE_DIRECTORY "${_thin_dir}")

  set(_JSI_A "${HERMES_STATIC_BUILD_DIR}/jsi/libjsi.a")
  set(_HERMES_VM_A "${HERMES_STATIC_BUILD_DIR}/lib/libhermesvm_a.a")
  set(_BOOST_CTX_A
    "${HERMES_STATIC_BUILD_DIR}/external/boost/boost_1_86_0/libs/context/libboost_context.a")
  foreach(f _JSI_A _HERMES_VM_A _BOOST_CTX_A)
    if(NOT EXISTS "${${f}}")
      message(FATAL_ERROR "Missing ${${f}}. Re-run host-build.sh to produce the static build dir.")
    endif()
  endforeach()

  if(APPLE)
    # lipo -thin is a Mach-O only tool; on Linux we use the archives
    # directly (they are already single-arch).
    set(_thin_outputs
        "${_thin_dir}/libhermesvm_a.a"
        "${_thin_dir}/libjsi.a"
        "${_thin_dir}/libboost_context.a")
    foreach(src_dst
        "${_HERMES_VM_A}|${_thin_dir}/libhermesvm_a.a"
        "${_JSI_A}|${_thin_dir}/libjsi.a"
        "${_BOOST_CTX_A}|${_thin_dir}/libboost_context.a")
      string(REPLACE "|" ";" parts "${src_dst}")
      list(GET parts 0 src)
      list(GET parts 1 dst)
      add_custom_command(OUTPUT "${dst}"
        COMMAND lipo -thin "${_arch}" "${src}" -output "${dst}"
        DEPENDS "${src}"
        COMMENT "lipo -thin ${_arch} ${src}"
        VERBATIM)
    endforeach()
    add_custom_target(_thin_libs ALL DEPENDS ${_thin_outputs})
    set(_THIN_HERMES_VM_A "${_thin_dir}/libhermesvm_a.a")
    set(_THIN_JSI_A "${_thin_dir}/libjsi.a")
    set(_THIN_BOOST_CTX_A "${_thin_dir}/libboost_context.a")
  else()
    # On Linux the static libs are already single-arch ELF archives; just
    # reference them in place (no thinning step needed).
    set(_THIN_HERMES_VM_A "${_HERMES_VM_A}")
    set(_THIN_JSI_A "${_JSI_A}")
    set(_THIN_BOOST_CTX_A "${_BOOST_CTX_A}")
  endif()

  # Process Hermes CMake without building hermesvm. We only need it for
  # header outputs (LLVM generated includes). Hermes itself comes from
  # the prebuilt static libs.
  set(_hermes_subdir "${CMAKE_CURRENT_BINARY_DIR}/_hermes")
  file(MAKE_DIRECTORY "${_hermes_subdir}")
  add_subdirectory("${HERMES_SRC}" "${_hermes_subdir}" EXCLUDE_FROM_ALL)

  # Create our own combined dylib using prebuilt static libs + our glue.
  # macOS host needs both core sources (HermesRuntime API) and JNI sources (for JVM).
  set(_macos_frameworks)
  if(APPLE)
    find_library(FOUNDATION_FRAMEWORK Foundation)
    find_library(COREFOUNDATION_FRAMEWORK CoreFoundation)
    find_library(APPKIT_FRAMEWORK AppKit)
    find_library(CORESERVICES_FRAMEWORK CoreServices)
    find_library(SECURITY_FRAMEWORK Security)
    list(APPEND _macos_frameworks
      ${FOUNDATION_FRAMEWORK}
      ${COREFOUNDATION_FRAMEWORK}
      ${APPKIT_FRAMEWORK}
      ${CORESERVICES_FRAMEWORK}
      ${SECURITY_FRAMEWORK})
  endif()

  add_library(hermesvm_jni SHARED ${GLUE_CORE_SOURCES} ${GLUE_JNI_SOURCES})
  target_include_directories(hermesvm_jni PRIVATE
    ${_GLUE_INCLUDES}
    "${_hermes_subdir}/external/llvh/include")
  if(HERMES_ENABLE_DEBUGGER)
    target_compile_definitions(hermesvm_jni PRIVATE HERMES_ENABLE_DEBUGGER=1)
  endif()
  target_link_libraries(hermesvm_jni PRIVATE
    "-Wl,-force_load,${_THIN_HERMES_VM_A}"
    "-Wl,-force_load,${_THIN_JSI_A}"
    "-Wl,-force_load,${_THIN_BOOST_CTX_A}"
    ${_macos_frameworks}
  )
  set_target_properties(hermesvm_jni PROPERTIES
    OUTPUT_NAME hermesvm
    LINK_FLAGS "-Wl,-undefined,dynamic_lookup")
  if(APPLE)
    add_dependencies(hermesvm_jni _thin_libs)
  endif()

endif()

# Custom target so `cmake --build build --target hermesvm` works.
add_custom_target(hermesvm_build ALL)
