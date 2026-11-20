cmake_minimum_required(VERSION 3.22)

# The JVM-facing openNURBS wrapper: one shared library exporting the `field_opennurbs_*` C ABI that
# `afield.geometry.rhino.OpenNurbsNative` binds with java.lang.foreign.
#
# openNURBS is McNeel's reader and writer for Rhino's .3dm files. Nothing is vendored: a pinned,
# checksummed source tarball is downloaded by `tools/build_opennurbs_native.py`, and
# FIELD_OPENNURBS_SOURCE_DIR points at it. Its own CMakeLists builds a static library, which is
# linked whole into the wrapper so the JVM loads one file with no dependencies of its own.
#
#     python3 tools/build_opennurbs_native.py
#     ./gradlew buildOpenNurbsNative

project(field_opennurbs_jvm LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
# The static openNURBS library ends up inside a shared one, so every object in it must be
# position-independent. Set before add_subdirectory so it reaches openNURBS's targets too.
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

set(FIELD_OPENNURBS_SOURCE_DIR "" CACHE PATH "Unpacked openNURBS source (opennurbs.h, CMakeLists.txt)")
if(NOT FIELD_OPENNURBS_SOURCE_DIR OR NOT EXISTS "${FIELD_OPENNURBS_SOURCE_DIR}/opennurbs.h")
  message(FATAL_ERROR
    "FIELD_OPENNURBS_SOURCE_DIR must point at an unpacked openNURBS source tree. "
    "Run tools/build_opennurbs_native.py, which downloads and checksums one.")
endif()

# EXCLUDE_FROM_ALL: openNURBS also defines a shared library (a framework on macOS) and its
# examples; only `opennurbsStatic`, which the wrapper links, gets built.
add_subdirectory("${FIELD_OPENNURBS_SOURCE_DIR}" opennurbs EXCLUDE_FROM_ALL)

add_library(field_opennurbs_jvm SHARED src/field_opennurbs.cpp src/field_opennurbs_brep.cpp)
target_include_directories(field_opennurbs_jvm PRIVATE src "${FIELD_OPENNURBS_SOURCE_DIR}")
target_compile_definitions(field_opennurbs_jvm PRIVATE FIELD_OPENNURBS_BUILDING_SHARED)
set(FIELD_OPENNURBS_VERSION "" CACHE STRING "The openNURBS release tag being built, for field_opennurbs_version()")
if(FIELD_OPENNURBS_VERSION)
  target_compile_definitions(field_opennurbs_jvm PRIVATE FIELD_OPENNURBS_VERSION="${FIELD_OPENNURBS_VERSION}")
endif()
if(APPLE)
  # The same runtime defines openNURBS compiles itself with; its headers key struct layouts off them.
  target_compile_definitions(field_opennurbs_jvm PRIVATE ON_COMPILER_CLANG ON_RUNTIME_APPLE RHINO_APPLE=1 _GNU_SOURCE)
endif()
if(MSVC)
  target_compile_definitions(field_opennurbs_jvm PRIVATE UNICODE _UNICODE)
else()
  target_compile_options(field_opennurbs_jvm PRIVATE -Wno-inconsistent-missing-override -Wno-deprecated-declarations)
endif()
target_link_libraries(field_opennurbs_jvm PRIVATE opennurbsStatic)
set_target_properties(field_opennurbs_jvm PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON)

# The writer, field_opennurbs_write.h, lives in its own file.
target_sources(field_opennurbs_jvm PRIVATE src/field_opennurbs_write.cpp)

install(TARGETS field_opennurbs_jvm LIBRARY DESTINATION lib RUNTIME DESTINATION bin)
