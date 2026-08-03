cmake_minimum_required(VERSION 3.22)

# The JVM-facing Ceres wrapper: one shared library exporting the `ken_ceres_*` C ABI that
# `afield.stdlib.ceres.FfmCeresBackend` binds with java.lang.foreign.
#
# Two ways to get a Ceres to link against:
#
#   * KEN_CERES_SYSTEM_CERES=ON (default) — find_package(Ceres). On macOS that is
#     `brew install ceres-solver`, which ships a Ceres configured with CERES_USE_EIGEN_SPARSE,
#     so SPARSE_NORMAL_CHOLESKY and CGNR are available. This builds in seconds and is what you
#     want while iterating on the wrapper.
#
#   * KEN_CERES_SYSTEM_CERES=OFF — link a static libceres.a you built yourself, named by
#     KEN_CERES_STATIC_LIB, with headers under KEN_CERES_INCLUDE_DIRS. Self-contained, and
#     therefore the mode a redistributable artifact wants; slower to set up.
#
# Either way the result is a single dylib/so; nothing about the ABI differs between them.

project(ken_ceres_jvm LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

option(KEN_CERES_SYSTEM_CERES "Link the system Ceres found by find_package instead of a static one" ON)
set(KEN_CERES_STATIC_LIB "" CACHE FILEPATH "Path to a static libceres.a (KEN_CERES_SYSTEM_CERES=OFF)")
set(KEN_CERES_INCLUDE_DIRS "" CACHE STRING "Extra include dirs (KEN_CERES_SYSTEM_CERES=OFF)")

find_package(Threads REQUIRED)

add_library(ken_ceres_jvm SHARED src/ken_ceres.cpp)
target_include_directories(ken_ceres_jvm PRIVATE src)

if(KEN_CERES_SYSTEM_CERES)
  find_package(Ceres REQUIRED)
  target_link_libraries(ken_ceres_jvm PRIVATE Ceres::ceres Threads::Threads)
  message(STATUS "ken_ceres_jvm: system Ceres ${Ceres_VERSION}")
else()
  if(NOT KEN_CERES_STATIC_LIB)
    message(FATAL_ERROR "KEN_CERES_SYSTEM_CERES=OFF requires -DKEN_CERES_STATIC_LIB=/path/to/libceres.a")
  endif()
  # A static Ceres built with -DMINIGLOG=ON has no exported CERES_EXPORT attributes.
  target_compile_definitions(ken_ceres_jvm PRIVATE CERES_EXPORT=)
  target_include_directories(ken_ceres_jvm PRIVATE ${KEN_CERES_INCLUDE_DIRS})
  target_link_libraries(ken_ceres_jvm PRIVATE "${KEN_CERES_STATIC_LIB}" Threads::Threads)
  message(STATUS "ken_ceres_jvm: static Ceres at ${KEN_CERES_STATIC_LIB}")
endif()

set_target_properties(ken_ceres_jvm PROPERTIES
  OUTPUT_NAME "ken_ceres_jvm"
  BUILD_RPATH "@loader_path"
  INSTALL_RPATH "@loader_path")
