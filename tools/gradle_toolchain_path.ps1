# Discover the cached Field JDK without downloading or changing the bootstrap JVM.
param([Parameter(Mandatory=$true)][string]$RepoRoot)
$ErrorActionPreference = 'Stop'
$build = Get-Content -LiteralPath (Join-Path $RepoRoot 'build.gradle.kts') -Raw
$pin = [regex]::Match($build, '(?m)^val graalvmVersion = "([^"]+)"').Groups[1].Value
if (-not $pin) { exit 0 }
$cache = Join-Path $HOME '.field4/toolchains'
if (-not (Test-Path -LiteralPath $cache)) { exit 0 }
foreach ($jdk in (Get-ChildItem -LiteralPath $cache -Directory | Sort-Object Name)) {
    if ($jdk.Name -notlike "graalvm-community-*$pin*") { continue }
    $release = Join-Path $jdk.FullName 'release'
    if (-not (Test-Path -LiteralPath (Join-Path $jdk.FullName 'bin/java.exe'))) { continue }
    if (-not (Test-Path -LiteralPath $release)) { continue }
    $found = [regex]::Match((Get-Content -LiteralPath $release -Raw), '(?m)^GRAALVM_VERSION="([^"]+)"').Groups[1].Value
    if ($found -eq $pin) { Write-Output $jdk.FullName; exit 0 }
}
