# PowerShell script to run Field through the Gradle Wrapper.
# This can be double-clicked or run from PowerShell.

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$scriptDir = [System.IO.Path]::GetFullPath($scriptDir)
Set-Location $scriptDir

function Get-NormalizedPath {
    param([string] $Path)
    if ([string]::IsNullOrWhiteSpace($Path)) {
        return ""
    }
    return [System.IO.Path]::GetFullPath($Path).TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar)
}

function Get-SemanticSidecarPort {
    if (-not [string]::IsNullOrWhiteSpace($env:AFIELD_CODE_USE_SEMANTIC_SIDECAR_PORT)) {
        return [int] $env:AFIELD_CODE_USE_SEMANTIC_SIDECAR_PORT
    }

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($scriptDir)
    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $hash = $sha.ComputeHash($bytes)
    } finally {
        $sha.Dispose()
    }
    $value = [System.BitConverter]::ToUInt32($hash, 0)
    return 17841 + ($value % 1000)
}

function Get-SidecarStatus {
    param([string] $Url)

    if ([string]::IsNullOrWhiteSpace($Url)) {
        return "down"
    }

    $baseUrl = $Url.TrimEnd("/")
    if ($baseUrl.EndsWith("/analyze", [System.StringComparison]::OrdinalIgnoreCase)) {
        $baseUrl = $baseUrl.Substring(0, $baseUrl.Length - "/analyze".Length)
    }

    try {
        $payload = Invoke-RestMethod -Uri "$baseUrl/health" -TimeoutSec 1 -ErrorAction Stop
    } catch {
        return "down"
    }

    $reported = Get-NormalizedPath ([string] $payload.workspaceRoot)
    $expected = Get-NormalizedPath $scriptDir
    if ($reported -eq $expected) {
        return "ok"
    }
    return "wrong"
}

function Select-SemanticSidecarPort {
    $startPort = Get-SemanticSidecarPort
    for ($offset = 0; $offset -lt 25; $offset += 1) {
        $port = $startPort + $offset
        $status = Get-SidecarStatus "http://127.0.0.1:$port"
        if ($status -eq "ok" -or $status -eq "down") {
            return $port
        }
    }
    return $startPort
}

function Start-SemanticSidecar {
    param([int] $Port)

    $logDir = Join-Path $scriptDir "build\code-use-semantic-sidecar"
    $logFile = Join-Path $logDir "sidecar-launcher.log"
    New-Item -ItemType Directory -Force -Path $logDir | Out-Null

    Write-Host "Starting code-use semantic sidecar at http://127.0.0.1:$Port for $scriptDir" -ForegroundColor Cyan
    Write-Host "Sidecar log: $logFile" -ForegroundColor DarkCyan

    $workspaceArg = $scriptDir.Replace('"', '\"')
    $command = ".\gradlew.bat -p tools/code-use-semantic-sidecar run --args=`"--port $Port --workspace `"$workspaceArg`"`" >> `"$logFile`" 2>&1"
    Start-Process -FilePath "cmd.exe" -ArgumentList @("/c", $command) -WorkingDirectory $scriptDir -WindowStyle Minimized | Out-Null
}

function Ensure-SemanticSidecar {
    if ($env:AFIELD_CODE_USE_SEMANTIC_SIDECAR -eq "off" -or $env:AFIELD_CODE_USE_SEMANTIC_SIDECAR -eq "false") {
        return
    }

    $sidecarDir = Join-Path $scriptDir "tools\code-use-semantic-sidecar"
    if (-not (Test-Path -Path $sidecarDir -PathType Container)) {
        return
    }

    if (-not [string]::IsNullOrWhiteSpace($env:AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL)) {
        $status = Get-SidecarStatus $env:AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL
        if ($status -eq "ok") {
            return
        }
        Write-Host "Configured code-use semantic sidecar is $status for this checkout; starting a checkout-local sidecar." -ForegroundColor Yellow
    }

    $port = Select-SemanticSidecarPort
    $url = "http://127.0.0.1:$port"
    $env:AFIELD_CODE_USE_SEMANTIC_SIDECAR_URL = $url

    $status = Get-SidecarStatus $url
    if ($status -eq "ok") {
        Write-Host "Using code-use semantic sidecar at $url" -ForegroundColor Cyan
        return
    }

    Start-SemanticSidecar $port
}

function Run-Field {
    Ensure-SemanticSidecar
    & .\gradlew.bat runDesktop
    return $LASTEXITCODE
}

Write-Host "===================================" -ForegroundColor Cyan
Write-Host "Starting Field4..." -ForegroundColor Cyan
Write-Host "===================================" -ForegroundColor Cyan
Write-Host ""

$exitCode = Run-Field

if ($exitCode -ne 0) {
    Write-Host ""
    Write-Host "===================================" -ForegroundColor Red
    Write-Host "Field4 exited with an error." -ForegroundColor Red
    Write-Host "===================================" -ForegroundColor Red
} else {
    Write-Host ""
    Write-Host "Field4 closed normally." -ForegroundColor Green
}

if ($Host.Name -notmatch "ConsoleHost" -or $env:AFIELD_FIELD_PS1_NO_PAUSE -ne "1") {
    Write-Host "Press any key to close this window..."
    $null = $Host.UI.RawUI.ReadKey("NoEcho,IncludeKeyDown")
}

exit $exitCode
