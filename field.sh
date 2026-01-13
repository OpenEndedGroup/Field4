#!/bin/sh
# Unix shell script to run the JVM target using Gradle Wrapper
DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$DIR"
./gradlew runJvm
