cmake_minimum_required(VERSION 3.22)

# field_morse_smale: the discrete Morse–Smale complex of a 2D scalar grid, behind the flat C ABI in
# src/field_morse_smale.h that afield.geometry.topology.morsesmale.MorseSmaleNative binds with
# java.lang.foreign. No third-party dependencies.
#
#     python3 tools/build_morse_smale_native.py
#     ./gradlew buildMorseSmaleNative

project(field_morse_smale LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

add_library(field_morse_smale SHARED src/field_morse_smale.cpp)
target_include_directories(field_morse_smale PUBLIC src)
set_target_properties(field_morse_smale PROPERTIES
  CXX_VISIBILITY_PRESET hidden
  VISIBILITY_INLINES_HIDDEN ON)

# The minimal native test: synthetic fields with known critical points, run by ctest and by the
# build script.
enable_testing()
add_executable(field_morse_smale_test tests/test_morse_smale.cpp)
target_link_libraries(field_morse_smale_test PRIVATE field_morse_smale)
add_test(NAME field_morse_smale_test COMMAND field_morse_smale_test)

install(TARGETS field_morse_smale LIBRARY DESTINATION lib RUNTIME DESTINATION bin)
