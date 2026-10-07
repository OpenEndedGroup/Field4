$ErrorActionPreference = "Stop"

# Exercise the real Build-Field function against a native process with build output.
# A failed build must return one Boolean, rather than an array of log lines and false.
$launcher = Join-Path $PSScriptRoot "..\field.ps1"
$tokens = $null
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile($launcher, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -gt 0) { throw "field.ps1 parse errors: $parseErrors" }
$build = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq "Build-Field"
}, $true)
if ($null -eq $build) { throw "Build-Field was not found" }
Invoke-Expression $build.Extent.Text
function Get-GradleOfflineArgs { return @() }
$script:fieldToolchainArgs = @()
$script:serverOnly = $true
$previousSkip = $env:AFIELD_SKIP_BUILD
$previousLocation = Get-Location
$scratch = Join-Path ([System.IO.Path]::GetTempPath()) ("field-build-result-" + [guid]::NewGuid())
try {
    New-Item -ItemType Directory -Path $scratch | Out-Null
    Set-Location $scratch
    $env:AFIELD_SKIP_BUILD = "0"
    foreach ($exitCode in @(1, 0)) {
        if ($env:OS -eq "Windows_NT") {
            Set-Content -Encoding ASCII -Path "gradlew.bat" -Value "@echo off`r`necho Native build output`r`nexit /b $exitCode"
        } else {
            Set-Content -Encoding ASCII -Path "gradlew.bat" -Value "#!/bin/sh`necho Native build output`nexit $exitCode"
            & chmod +x gradlew.bat
        }
        $result = Build-Field
        if ($result -isnot [bool]) { throw "Build-Field returned logs instead of one Boolean: $result" }
        if ($result -ne ($exitCode -eq 0)) { throw "Build-Field returned the wrong result for exit $exitCode" }
    }
    Write-Host "PASS: failed native build stops launch; successful native build returns true"
} finally {
    Set-Location $previousLocation
    $env:AFIELD_SKIP_BUILD = $previousSkip
    if (Test-Path $scratch) { Remove-Item -Recurse -Force $scratch }
}
