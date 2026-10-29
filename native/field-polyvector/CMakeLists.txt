cmake_minimum_required(VERSION 3.22)

# field_polyvector: line-drawing vectorization by polyvector fields (Bessmeltsev & Solomon 2019),
# headless, behind the flat C ABI in src/field_polyvector.h that
# afield.geometry.vectorize.polyvector.PolyvectorNative binds with java.lang.foreign.
#
# The algorithm is the authors' MIT-licensed implementation, fetched at a pinned commit and patched
# by tools/build_native_geometry.py (FIELD_POLYVECTOR_SOURCE_DIR points at the patched tree). It needs
# OpenCV (core, imgproc), Boost (graph headers) and Eigen:
#     macOS:  brew install opencv boost eigen
#     Debian: apt install libopencv-dev libboost-dev libeigen3-dev
#
#     python3 tools/build_native_geometry.py polyvector
#     ./gradlew buildPolyvectorNative

project(field_polyvector LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
if(NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
  set(CMAKE_BUILD_TYPE Release CACHE STRING "" FORCE)
endif()

set(FIELD_POLYVECTOR_SOURCE_DIR "" CACHE PATH "Patched PolyVectorization src/ directory")
if(NOT EXISTS "${FIELD_POLYVECTOR_SOURCE_DIR}/Optimizer.cpp")
  message(FATAL_ERROR "FIELD_POLYVECTOR_SOURCE_DIR must point at the patched PolyVectorization src/. "
    "Run tools/build_native_geometry.py polyvector, which fetches and patches it.")
endif()

# Only the modules the algorithm uses (no GUI): core, imgproc, and on OpenCV 5 geometry, where
# pointPolygonTest moved.
find_package(OpenCV REQUIRED COMPONENTS core imgproc OPTIONAL_COMPONENTS geometry imgcodecs)
find_package(Eigen3 REQUIRED NO_MODULE)
find_package(Boost REQUIRED)

# Every algorithm file of the reference; its GUI, SVG writer and main() are left out.
set(PV ${FIELD_POLYVECTOR_SOURCE_DIR})
set(PV_SOURCES
  AlmostReebGraph.cpp bilinearInterpCoeff.cpp ChainDecomposition.cpp chooseRoot.cpp chopFakeEnds.cpp
  CircularSegment.cpp ContractDeg2.cpp ContractLoops.cpp ContractLoops2.cpp FillHole.cpp FindRoots.cpp
  findSingularities.cpp graph_typedefs.cpp greedyTrace.cpp intersections.cpp IsLoopContractible.cpp
  l2_regularizer.cpp Optimizer.cpp polynomial_energy.cpp RemoveShortBranches.cpp ScanConvert.cpp
  Simplify.cpp Smooth.cpp SplitEmUp.cpp stdafx.cpp TopoGraphEmbedding.cpp TotalEnergy.cpp traceAuto.cpp
  typedefs.cpp)
list(TRANSFORM PV_SOURCES PREPEND "${PV}/")

add_library(field_polyvector SHARED src/field_polyvector.cpp ${PV_SOURCES})
target_include_directories(field_polyvector PRIVATE src ${PV} ${Boost_INCLUDE_DIRS})
set(PV_OPENCV opencv_core opencv_imgproc)
if(TARGET opencv_geometry)
  list(APPEND PV_OPENCV opencv_geometry)
endif()
target_link_libraries(field_polyvector PRIVATE ${PV_OPENCV} Eigen3::Eigen)
# the reference narrows doubles in initialiser lists, which current clang rejects
target_compile_options(field_polyvector PRIVATE -Wno-c++11-narrowing -include ${CMAKE_CURRENT_SOURCE_DIR}/src/omp_compat.h)
# the reference's own printf progress output, silenced in its sources only
set_source_files_properties(${PV_SOURCES} PROPERTIES COMPILE_OPTIONS "-include;${CMAKE_CURRENT_SOURCE_DIR}/src/quiet.h")
set_target_properties(field_polyvector PROPERTIES CXX_VISIBILITY_PRESET hidden VISIBILITY_INLINES_HIDDEN ON)

enable_testing()
add_executable(field_polyvector_test tests/test_polyvector.cpp)
target_link_libraries(field_polyvector_test PRIVATE field_polyvector opencv_core opencv_imgproc opencv_imgcodecs)
target_include_directories(field_polyvector_test PRIVATE src)
# the pinned upstream tree, for its sample drawing
get_filename_component(PV_UPSTREAM "${FIELD_POLYVECTOR_SOURCE_DIR}" DIRECTORY)
add_test(NAME field_polyvector_test COMMAND field_polyvector_test "${PV_UPSTREAM}")
