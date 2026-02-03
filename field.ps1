# PowerShell script to run the JVM target using Gradle Wrapper
# This can be double-clicked or run from PowerShell
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
Set-Location $scriptDir

Write-Host "===================================" -ForegroundColor Cyan
Write-Host "Starting Field4..." -ForegroundColor Cyan
Write-Host "===================================" -ForegroundColor Cyan
Write-Host ""

./gradlew runJvm

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "===================================" -ForegroundColor Red
    Write-Host "Field4 exited with an error." -ForegroundColor Red
    Write-Host "===================================" -ForegroundColor Red
    Write-Host ""
    Write-Host "Press any key to close this window..."
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
} else {
    Write-Host ""
    Write-Host "Field4 closed normally." -ForegroundColor Green
    Write-Host "Press any key to close this window..."
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
}
