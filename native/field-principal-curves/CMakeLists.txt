cmake_minimum_required(VERSION 3.22)

# field_principal_curves: elastic principal graphs (ported from ElPiGraph, GPL-3; Field is AGPL-3),
# behind the flat C ABI in src/field_principal_curves.h that
# afield.geometry.curves.principal.PrincipalCurvesNative binds with java.lang.foreign. No third-party
# dependencies.
#
#     python3 tools/build_native_geometry.py principal-curves
#     ./gradlew buildPrincipalCurvesNative

project(field_principal_curves LANGUAGES CXX)
find_package(Threads REQUIRED)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

add_library(field_principal_curves SHARED src/field_principal_curves.cpp)
target_include_directories(field_principal_curves PUBLIC src)
target_link_libraries(field_principal_curves PRIVATE Threads::Threads)
set_target_properties(field_principal_curves PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON)

# The minimal native test: synthetic point clouds with known structure, run by ctest and by the
# build script.
enable_testing()
add_executable(field_principal_curves_test tests/test_principal_curves.cpp)
target_link_libraries(field_principal_curves_test PRIVATE field_principal_curves)
add_test(NAME field_principal_curves_test COMMAND field_principal_curves_test)

install(TARGETS field_principal_curves LIBRARY DESTINATION lib RUNTIME DESTINATION bin)
