cmake_minimum_required(VERSION 3.22)

# The JVM-facing TetGen wrapper: one shared library exporting the `field_tetgen_*` C ABI that
# `afield.tetgen.TetgenNative` binds with java.lang.foreign.
#
# Unlike ken-ceres there is no system package to find. TetGen is two source files and no
# dependencies, so this compiles them straight into the wrapper from a pinned source tree that
# `tools/build_tetgen_native.py` downloads and checksums. FIELD_TETGEN_SOURCE_DIR points at it.
#
#     python3 tools/build_tetgen_native.py
#     ./gradlew buildTetgenNative

project(field_tetgen_jvm LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

set(FIELD_TETGEN_SOURCE_DIR "" CACHE PATH "Unpacked TetGen source (tetgen.cxx, predicates.cxx, tetgen.h)")
if(NOT FIELD_TETGEN_SOURCE_DIR OR NOT EXISTS "${FIELD_TETGEN_SOURCE_DIR}/tetgen.cxx")
  message(FATAL_ERROR
    "FIELD_TETGEN_SOURCE_DIR must point at an unpacked TetGen source tree. "
    "Run tools/build_tetgen_native.py, which downloads and checksums one.")
endif()

# TETLIBRARY makes TetGen throw an int on a fatal condition instead of calling exit(). Without it a
# bad surface takes the whole JVM down with no Java stack, which is the single most important
# compile definition here.
add_library(tetgen_core OBJECT "${FIELD_TETGEN_SOURCE_DIR}/tetgen.cxx")
target_compile_definitions(tetgen_core PRIVATE TETLIBRARY)
target_include_directories(tetgen_core PUBLIC "${FIELD_TETGEN_SOURCE_DIR}")

# Shewchuk's exact predicates must be compiled WITHOUT optimisation. Upstream's own makefile says so
# (PREDCXXFLAGS = -O0) and the reason is that the arithmetic depends on operations not being
# reassociated or kept at extended precision in a register. Built at -O2 the predicates return
# wrong signs on nearly-degenerate input, which does not crash — it silently produces an invalid
# tetrahedralization. This is the one flag in this file that cannot be relaxed.
add_library(tetgen_predicates OBJECT "${FIELD_TETGEN_SOURCE_DIR}/predicates.cxx")
target_compile_definitions(tetgen_predicates PRIVATE TETLIBRARY)
target_include_directories(tetgen_predicates PUBLIC "${FIELD_TETGEN_SOURCE_DIR}")
target_compile_options(tetgen_predicates PRIVATE -O0)

add_library(field_tetgen_jvm SHARED
  src/field_tetgen.cpp
  $<TARGET_OBJECTS:tetgen_core>
  $<TARGET_OBJECTS:tetgen_predicates>)
target_include_directories(field_tetgen_jvm PRIVATE src "${FIELD_TETGEN_SOURCE_DIR}")
target_compile_definitions(field_tetgen_jvm PRIVATE TETLIBRARY FIELD_TETGEN_BUILDING_SHARED)
set_target_properties(field_tetgen_jvm PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON)

install(TARGETS field_tetgen_jvm LIBRARY DESTINATION lib RUNTIME DESTINATION bin)
