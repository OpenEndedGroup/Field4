# PowerShell script to run the JVM target using Gradle Wrapper
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $scriptDir
./gradlew runJvm
