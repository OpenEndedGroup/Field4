@echo off
REM Windows batch file to run the JVM target using Gradle Wrapper
setlocal
set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"
call gradlew runJvm
