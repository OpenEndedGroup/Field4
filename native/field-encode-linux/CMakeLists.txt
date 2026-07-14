# field-encode-linux: the Linux (NVENC) sender co-process for the visionOS stereo/flat docks.
# See ../field-encode/README.md and WIRE_FORMAT.md for the protocol both senders implement.
#
# Build/link surface is deliberately tiny: only libcuda.so.1/libnvidia-encode.so.1 (the driver's
# runtime libraries, present via `nvidia-utils`) are touched, and only via dlopen at runtime
# (see third_party/ffnvcodec/ — vendored dlopen-based headers, no CUDA toolkit / Video Codec SDK
# needed to build). No third-party libraries beyond libc/libdl.
cmake_minimum_required(VERSION 3.16)
project(field-encode-linux CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

add_executable(field-encode-linux
    src/main.cpp
)

target_link_libraries(field-encode-linux PRIVATE dl)
