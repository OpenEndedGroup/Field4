# field-fdpass: a tiny JNI shim (one function) letting the JVM send a raw fd to another process
# via SCM_RIGHTS over a Unix domain socket. See src/fdpass.c for why this exists — Java has no
# standard-library sendmsg()/ancillary-data support, and /proc/<pid>/fd/<fd> duplication (which
# would avoid needing this) doesn't work for device-backed fds like NVIDIA's Vulkan opaque-fd
# export. Used by afield.four.texture.UnixFdPass (JVM) and native/field-encode-linux (receiver).
#
# The native-declaring Java class (java/fieldfdpass/UnixFdPassNative.java) is deliberately built
# here as its own standalone jar rather than compiled into Field4's jvmMain module — a class with
# a `native` method breaks Field4's box/REPL execution if it's ever visible on that classpath (see
# the "field4-jni-repl-bug" memory note). UnixFdPass.kt loads this jar via an isolated
# URLClassLoader at runtime instead.
cmake_minimum_required(VERSION 3.16)
project(field-fdpass C)

set(CMAKE_C_STANDARD 11)
if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE Release)
endif()

find_package(JNI REQUIRED)
find_package(Java REQUIRED COMPONENTS Development)

add_library(fieldfdpass SHARED src/fdpass.c)
target_include_directories(fieldfdpass PRIVATE ${JNI_INCLUDE_DIRS})
set_target_properties(fieldfdpass PROPERTIES PREFIX "lib")

set(JAVA_SRC "${CMAKE_CURRENT_SOURCE_DIR}/java/fieldfdpass/UnixFdPassNative.java")
set(JAVA_CLASSES_DIR "${CMAKE_CURRENT_BINARY_DIR}/classes")
set(JAVA_JAR "${CMAKE_CURRENT_BINARY_DIR}/fieldfdpass-native.jar")

add_custom_command(
    OUTPUT "${JAVA_JAR}"
    COMMAND "${CMAKE_COMMAND}" -E make_directory "${JAVA_CLASSES_DIR}"
    COMMAND "${Java_JAVAC_EXECUTABLE}" -d "${JAVA_CLASSES_DIR}" "${JAVA_SRC}"
    COMMAND "${Java_JAR_EXECUTABLE}" cf "${JAVA_JAR}" -C "${JAVA_CLASSES_DIR}" fieldfdpass
    DEPENDS "${JAVA_SRC}"
    COMMENT "Compiling fieldfdpass-native.jar (isolated JNI class, kept off Field4's jvmMain classpath)"
)
add_custom_target(fieldfdpass-native-jar ALL DEPENDS "${JAVA_JAR}")
