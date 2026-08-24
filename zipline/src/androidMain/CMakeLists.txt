cmake_minimum_required(VERSION 3.4.1)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 11)

set(MI_OVERRIDE OFF)
set(MI_BUILD_STATIC ON)
set(MI_BUILD_SHARED OFF)
set(MI_BUILD_OBJECT OFF)
set(MI_BUILD_TESTS OFF)

file(GLOB_RECURSE sources CONFIGURE_DEPENDS "../../native/*.c" "../../native/*.cpp")
add_library(quickjs SHARED ${sources})
target_link_libraries(quickjs PRIVATE log)
target_link_options(quickjs PRIVATE "-Wl,-z,max-page-size=16384")
# Prevent gc-sections from stripping unreferenced bridge registry symbols
# that are called from other .so files at runtime.
target_link_options(quickjs PRIVATE "-Wl,--undefined=addBridgeEntry")
target_link_options(quickjs PRIVATE "-Wl,--undefined=addBridgeInit")
target_link_options(quickjs PRIVATE "-Wl,--undefined=init_all")
target_link_options(quickjs PRIVATE "-Wl,--undefined=register_all")
target_link_options(quickjs PRIVATE "-Wl,--undefined=bridgeForAny")
target_link_options(quickjs PRIVATE "-Wl,--undefined=bridgeTryUnwrapLong")
# Export QuickJS internal symbols so libredwood-bridge can link against them
set_target_properties(quickjs PROPERTIES C_VISIBILITY_PRESET default)
