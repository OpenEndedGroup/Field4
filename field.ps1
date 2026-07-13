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

function Ensure-LaunchSpec {
    $argsFile = "build\field-launch\args"
    $javaFile = "build\field-launch\java"
    $needs = (-not (Test-Path $argsFile)) -or (-not (Test-Path $javaFile)) -or ($env:FIELD_REFRESH -eq "1")
    if (-not $needs -and ((Get-Item "build.gradle.kts").LastWriteTime -gt (Get-Item $argsFile).LastWriteTime)) {
        $needs = $true
    }
    if ($needs) {
        Write-Host "field: resolving launch spec via Gradle (set FIELD_REFRESH=1 to redo)..." -ForegroundColor DarkCyan
        & .\gradlew.bat -q dumpFieldLaunch
    }
}

function Stop-Tree {
    param([System.Diagnostics.Process] $Proc)
    if ($null -eq $Proc -or $Proc.HasExited) { return }
    # /T kills the whole child tree (backend spawns native/UI children); /F forces it.
    & taskkill.exe /PID $Proc.Id /T /F 2>$null | Out-Null
}

function Run-Field {
    Ensure-SemanticSidecar
    Ensure-LaunchSpec

    if (-not $env:PORT) { $env:PORT = "9092" }
    if (-not $env:AFIELD_PORT_FALLBACK_COUNT) { $env:AFIELD_PORT_FALLBACK_COUNT = "1" }
    $port = $env:PORT

    $javaBin = (Get-Content "build\field-launch\java" -Raw).Trim()
    $argsFileAbs = (Resolve-Path "build\field-launch\args").Path

    # Backend shares THIS console (-NoNewWindow) so its stdout stays a real TTY and the pinned console
    # (startup ribbon, fps/upload rows, render spinner) renders. Electron (a GUI, no tty needed) runs
    # alongside — this is how the two coexist. runDesktop can't, because the Gradle daemon has no console.
    $backend = Start-Process -FilePath $javaBin -ArgumentList (@("@$argsFileAbs") + $args) -NoNewWindow -PassThru

    # Trust the dev server's self-signed cert for the health/app-url probes (works on PS 5.1 and 7).
    try {
        [System.Net.ServicePointManager]::ServerCertificateValidationCallback = { $true }
        [System.Net.ServicePointManager]::SecurityProtocol = [System.Net.ServicePointManager]::SecurityProtocol -bor [System.Net.SecurityProtocolType]::Tls12
    } catch {}

    $electron = $null
    try {
        # Wait (~120s) for the backend to come up, then open the Electron shell against it.
        $deadline = (Get-Date).AddSeconds(120)
        while ((Get-Date) -lt $deadline -and -not $backend.HasExited) {
            try {
                $h = Invoke-RestMethod -Uri "https://localhost:$port/health" -TimeoutSec 2 -ErrorAction Stop
                if ("$h" -match "ok") { break }
            } catch {}
            Start-Sleep -Milliseconds 250
        }

        $electronBin = if (Test-Path "build\field-launch\electron") { (Get-Content "build\field-launch\electron" -Raw).Trim() } else { "" }
        if (-not $backend.HasExited -and $electronBin -and (Test-Path $electronBin)) {
            $appUrl = "https://localhost:$port/index.html"
            try {
                $u = Invoke-RestMethod -Uri "https://localhost:$port/app-url" -TimeoutSec 2 -ErrorAction Stop
                if ("$u".Trim()) { $appUrl = "$u".Trim() }
            } catch {}
            $env:APP_URL = $appUrl
            if (-not $env:WITH_DEVTOOLS) { $env:WITH_DEVTOOLS = "0" }
            $electronDir = (Get-Content "build\field-launch\electron-dir" -Raw).Trim()
            $eArgs = @()
            if (Test-Path "build\field-launch\electron-args") { $eArgs = @(Get-Content "build\field-launch\electron-args" | Where-Object { $_ -ne "" }) }
            $electron = Start-Process -FilePath $electronBin -ArgumentList ($eArgs + ".") -WorkingDirectory $electronDir -PassThru
        }

        # Wait until either exits, then stop the other.
        while (-not $backend.HasExited -and ($null -eq $electron -or -not $electron.HasExited)) {
            Start-Sleep -Milliseconds 500
        }
    } finally {
        Stop-Tree $electron
        Stop-Tree $backend
        # Guarantee the terminal is restored (drop scroll region + leave the alternate screen) even if
        # the backend was hard-killed and its JVM shutdown hook never ran. No-op if not in the alt buffer.
        if (-not [Console]::IsOutputRedirected) {
            $esc = [char]27
            [Console]::Out.Write("$esc[r$esc[?1049l")
        }
    }

    if (-not $backend.HasExited) { $backend.WaitForExit() }
    return $backend.ExitCode
}

Write-Host "===================================" -ForegroundColor Cyan
Write-Host "Starting Field4..." -ForegroundColor Cyan
Write-Host "===================================" -ForegroundColor Cyan
Write-Host ""

$exitCode = Run-Field @args

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
