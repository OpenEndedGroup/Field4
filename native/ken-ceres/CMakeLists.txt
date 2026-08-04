cmake_minimum_required(VERSION 3.22)

# The JVM-facing Ceres wrapper: one shared library exporting the `ken_ceres_*` C ABI that
# `afield.stdlib.ceres.FfmCeresBackend` binds with java.lang.foreign.
#
# Two ways to get a Ceres to link against:
#
#   * KEN_CERES_SYSTEM_CERES=ON (default) — find_package(Ceres). On macOS that is
#     `brew install ceres-solver`, on Debian/Ubuntu `apt install libceres-dev`, both of which ship a
#     Ceres configured with a sparse backend, so SPARSE_NORMAL_CHOLESKY and CGNR are available. This
#     builds in seconds and is what you want while iterating on the wrapper. There is no equivalent
#     on Windows, so this mode is Unix-only in practice.
#
#   * KEN_CERES_SYSTEM_CERES=OFF — link a static libceres built from pinned source, named by
#     KEN_CERES_STATIC_LIB, with headers under KEN_CERES_INCLUDE_DIRS. Self-contained, and therefore
#     the mode a redistributable artifact wants; slower to set up. superbuild/CMakeLists.txt drives
#     this end to end and is the only path that works on all three platforms.
#
# Either way the result is a single dylib/so/dll; nothing about the ABI differs between them.

project(ken_ceres_jvm LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

option(KEN_CERES_SYSTEM_CERES "Link the system Ceres found by find_package instead of a static one" ON)
set(KEN_CERES_STATIC_LIB "" CACHE FILEPATH "Path to a static libceres (KEN_CERES_SYSTEM_CERES=OFF)")
set(KEN_CERES_INCLUDE_DIRS "" CACHE STRING "Extra include dirs (KEN_CERES_SYSTEM_CERES=OFF)")

find_package(Threads REQUIRED)

add_library(ken_ceres_jvm SHARED src/ken_ceres.cpp)
target_include_directories(ken_ceres_jvm PRIVATE src)

# Tells src/ken_ceres_export.h to mark the ten entry points dllexport rather than dllimport. A no-op
# off Windows; without it on Windows the DLL builds, loads, and exports nothing.
target_compile_definitions(ken_ceres_jvm PRIVATE KEN_CERES_BUILDING_SHARED)

if(KEN_CERES_SYSTEM_CERES)
  # 2.1 is a floor, not a preference: the wrapper uses ceres::Manifold and ceres::SphereManifold,
  # which do not exist before it. Ubuntu 22.04's libceres-dev is 2.0.0, so without this the Linux
  # fast path fails as a page of template errors instead of one sentence.
  find_package(Ceres 2.1 REQUIRED)
  target_link_libraries(ken_ceres_jvm PRIVATE Ceres::ceres Threads::Threads)
  # A system Ceres logs through real glog, whose warnings bypass Solver::Options::logging_type and
  # land on stderr. Tell the wrapper it can silence them. A MINIGLOG static build has no such
  # symbols and no such problem.
  find_package(glog QUIET)
  if(glog_FOUND)
    target_compile_definitions(ken_ceres_jvm PRIVATE KEN_CERES_HAVE_GLOG GLOG_USE_GLOG_EXPORT)
    target_link_libraries(ken_ceres_jvm PRIVATE glog::glog)
    message(STATUS "ken_ceres_jvm: glog found, library logging silenced")
  endif()
  message(STATUS "ken_ceres_jvm: system Ceres ${Ceres_VERSION}")
else()
  if(NOT KEN_CERES_STATIC_LIB)
    message(FATAL_ERROR "KEN_CERES_SYSTEM_CERES=OFF requires -DKEN_CERES_STATIC_LIB=/path/to/libceres")
  endif()
  # A static Ceres built with -DMINIGLOG=ON has no exported CERES_EXPORT attributes.
  target_compile_definitions(ken_ceres_jvm PRIVATE CERES_EXPORT=)
  target_include_directories(ken_ceres_jvm PRIVATE ${KEN_CERES_INCLUDE_DIRS})
  target_link_libraries(ken_ceres_jvm PRIVATE "${KEN_CERES_STATIC_LIB}" Threads::Threads)
  message(STATUS "ken_ceres_jvm: static Ceres at ${KEN_CERES_STATIC_LIB}")
endif()

set_target_properties(ken_ceres_jvm PROPERTIES OUTPUT_NAME "ken_ceres_jvm")

if(APPLE)
  set_target_properties(ken_ceres_jvm PROPERTIES
    BUILD_RPATH "@loader_path"
    INSTALL_RPATH "@loader_path")
elseif(UNIX)
  # The ELF spelling of the same thing. Belt-and-braces for the static build, which has no sibling
  # libraries to find, and load-bearing for the system-Ceres build only if someone relocates it next
  # to its dependencies.
  set_target_properties(ken_ceres_jvm PROPERTIES
    BUILD_RPATH "$ORIGIN"
    INSTALL_RPATH "$ORIGIN")
endif()

if(MSVC)
  # Static CRT, so the DLL does not require the Visual C++ redistributable on the target machine.
  # This is the Windows half of the property the `otool -L` assertion protects on macOS: a DLL that
  # needs MSVCP140.dll is not redistributable in the sense we mean. CMP0091 (CMake 3.15+) is what
  # makes MSVC_RUNTIME_LIBRARY authoritative instead of the old flag munging.
  cmake_policy(SET CMP0091 NEW)
  set_target_properties(ken_ceres_jvm PROPERTIES
    MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
  # Ceres' and Eigen's templates generate more sections than a COFF object can hold by default.
  target_compile_options(ken_ceres_jvm PRIVATE /bigobj /EHsc)
  # windows.h arrives through Ceres' threading headers and defines min/max as macros, which Eigen
  # and <limits> both trip over.
  target_compile_definitions(ken_ceres_jvm PRIVATE NOMINMAX WIN32_LEAN_AND_MEAN _USE_MATH_DEFINES)
elseif(UNIX AND NOT APPLE)
  # Same goal as the MSVC static CRT: no libstdc++.so.6 / libgcc_s.so.1 version floor on the target
  # machine. tools/check_native_selfcontained.py fails the build if either shows up anyway.
  target_link_options(ken_ceres_jvm PRIVATE -static-libstdc++ -static-libgcc)
endif()
