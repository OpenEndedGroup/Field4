@echo off
REM Windows batch file to run the JVM target using Gradle Wrapper
REM This can be double-clicked from Windows Explorer
setlocal
set SCRIPT_DIR=%~dp0
cd /d "%SCRIPT_DIR%"

echo ===================================
echo Starting Field4...
echo ===================================
echo.

if exist "%SCRIPT_DIR%field.ps1" (
    set AFIELD_FIELD_PS1_NO_PAUSE=1
    powershell.exe -ExecutionPolicy Bypass -File "%SCRIPT_DIR%field.ps1"
) else (
    call gradlew runDesktop
)

if errorlevel 1 (
    echo.
    echo ===================================
    echo Field4 exited with an error.
    echo ===================================
    pause
) else (
    echo.
    echo Field4 closed normally.
    pause
)
