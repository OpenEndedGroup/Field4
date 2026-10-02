cmake_minimum_required(VERSION 3.20)
project(field_opendlssnr LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
find_package(Python3 REQUIRED COMPONENTS Interpreter)
find_program(GLSLANG_VALIDATOR NAMES glslangValidator glslang REQUIRED)
foreach(root NR_ROOT HEADERS_ROOT VOLK_ROOT)
  if(NOT DEFINED ${root})
    message(FATAL_ERROR "Missing pinned source path: ${root}; use buildOpenDlssNrNative")
  endif()
endforeach()
add_library(nr_core OBJECT
  ${NR_ROOT}/src/vk_context.cpp ${NR_ROOT}/src/kernels.cpp ${NR_ROOT}/src/nr_model.cpp
  ${NR_ROOT}/src/nr_graph.cpp ${NR_ROOT}/src/reference.cpp ${VOLK_ROOT}/volk.c)
target_include_directories(nr_core PUBLIC ${NR_ROOT}/src ${VOLK_ROOT} ${HEADERS_ROOT}/include)
target_compile_definitions(nr_core PUBLIC VK_NO_PROTOTYPES VK_ENABLE_BETA_EXTENSIONS)
target_compile_options(nr_core PUBLIC -ffp-contract=off)
add_library(field_opendlssnr SHARED field_opendlssnr.cpp)
target_link_libraries(field_opendlssnr PRIVATE nr_core dl pthread)
add_executable(field_opendlssnr_reference ${NR_ROOT}/src/main.cpp ${NR_ROOT}/src/verify.cpp reference_selftest.cpp)
target_link_libraries(field_opendlssnr_reference PRIVATE nr_core dl pthread)
file(GLOB SHADERS CONFIGURE_DEPENDS ${NR_ROOT}/shaders/*.comp ${CMAKE_CURRENT_SOURCE_DIR}/shaders/*.comp)
file(GLOB INCLUDES CONFIGURE_DEPENDS ${NR_ROOT}/shaders/*.glsl ${CMAKE_CURRENT_SOURCE_DIR}/shaders/*.glsl)
foreach(shader ${SHADERS})
  get_filename_component(name ${shader} NAME_WE)
  set(output ${CMAKE_BINARY_DIR}/../shaders/${name}.spv)
  add_custom_command(OUTPUT ${output}
    COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_BINARY_DIR}/../shaders
    COMMAND ${GLSLANG_VALIDATOR} -V --target-env vulkan1.3 -I${NR_ROOT}/shaders ${shader} -o ${output}
    DEPENDS ${shader} ${INCLUDES} VERBATIM)
  list(APPEND SPIRV ${output})
endforeach()
file(GLOB PTX CONFIGURE_DEPENDS ${NR_ROOT}/scripts/ptx/*.py)
add_custom_command(OUTPUT ${CMAKE_BINARY_DIR}/../ptx/.generated
  COMMAND ${Python3_EXECUTABLE} ${CMAKE_CURRENT_SOURCE_DIR}/../../tools/opendlssnr/build_ptx.py ${CMAKE_BINARY_DIR}/../ptx ${NR_ROOT}/scripts/ptx
  COMMAND ${CMAKE_COMMAND} -E touch ${CMAKE_BINARY_DIR}/../ptx/.generated
  DEPENDS ${PTX} ${CMAKE_CURRENT_SOURCE_DIR}/../../tools/opendlssnr/build_ptx.py VERBATIM)
add_custom_target(kernels ALL DEPENDS ${SPIRV} ${CMAKE_BINARY_DIR}/../ptx/.generated)
add_dependencies(field_opendlssnr kernels)

add_dependencies(field_opendlssnr_reference kernels)
