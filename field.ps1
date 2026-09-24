# PowerShell script to run Field through the Gradle Wrapper.
# This can be double-clicked or run from PowerShell.

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$scriptDir = [System.IO.Path]::GetFullPath($scriptDir)
# Captured before Set-Location: a workspace or document path typed on the command line has to
# resolve against the directory the user is standing in, which the next line is about to leave.
$invokedFrom = (Get-Location).ProviderPath
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
    $offline = (Get-GradleOfflineArgs) -join " "
    $command = ".\gradlew.bat $offline -p tools/code-use-semantic-sidecar run --args=`"--port $Port --workspace `"$workspaceArg`"`" >> `"$logFile`" 2>&1"
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

# `--offline` (or AFIELD_OFFLINE=1) passes Gradle's --offline: build from the dependency cache and
# never touch the network. See the note beside decide_offline in field.sh; announced once.
$script:gradleOfflineArgs = $null
function Get-GradleOfflineArgs {
    if ($null -ne $script:gradleOfflineArgs) { return $script:gradleOfflineArgs }
    $script:gradleOfflineArgs = @()
    if ($env:AFIELD_OFFLINE -eq "1") {
        Write-Host "field: offline - Gradle will build from cached dependencies only" -ForegroundColor DarkCyan
        $script:gradleOfflineArgs = @("--offline")
    }
    return $script:gradleOfflineArgs
}

function Build-Field {
    # Always build before launching so a fresh clone (and every edit-and-relaunch) runs current code.
    # jvmJar compiles Kotlin/JVM, runs the JS webpack, and bundles afield.mjs into the served static/;
    # dumpFieldLaunch writes the launch spec. Incremental, so quick once built. Set AFIELD_SKIP_BUILD=1
    # to skip. Gradle output is shown: the first build compiles the whole project (a few minutes).
    if ($env:AFIELD_SKIP_BUILD -eq "1" -and (Test-Path "build\field-launch\args")) {
        Write-Host "field: AFIELD_SKIP_BUILD=1 - skipping build" -ForegroundColor DarkCyan
        return $true
    }
    Write-Host "field: building (JVM + JS; the first run compiles everything, a few minutes)..." -ForegroundColor DarkCyan
    $offline = @(Get-GradleOfflineArgs)
    & .\gradlew.bat @offline jvmJar dumpFieldLaunch
    if ($LASTEXITCODE -ne 0) {
        Write-Host "field: BUILD FAILED - fix the errors above, then re-run .\field.ps1" -ForegroundColor Red
        if ($offline.Count -gt 0) {
            Write-Host "field: (built --offline: a dependency that was never downloaded cannot be resolved until you are back online)" -ForegroundColor Red
        }
        return $false
    }
    return $true
}

function Stop-Tree {
    param([System.Diagnostics.Process] $Proc)
    if ($null -eq $Proc -or $Proc.HasExited) { return }
    # /T kills the whole child tree (backend spawns native/UI children); /F forces it.
    & taskkill.exe /PID $Proc.Id /T /F 2>$null | Out-Null
}

function Run-Field {
    Ensure-SemanticSidecar
    if (-not (Build-Field)) { return 1 }

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
            [Console]::Out.Write("$esc[?1000l$esc[?1006l$esc[r$esc[?1049l")
        }
    }

    if (-not $backend.HasExited) { $backend.WaitForExit() }
    return $backend.ExitCode
}

# --- launcher arguments ----------------------------------------------------------
#
# The mirror of field.sh's parser, and the long note at the top of that script explains why any
# of this happens in a launcher at all when the backend parses --workspace and --document itself.
# Three things can only be done out here: resolving a relative path against the directory the
# user is standing in, which Set-Location above has already left; rejecting a typo *before* the
# minutes of Gradle that run before the backend ever looks at its arguments; and printing which
# workspace a bare launch is about to open, because that choice is remembered once per machine,
# so any other clone or worktree on this machine may have been the one that recorded it.

# Where the backend records the workspace choice, per Server.kt's fieldUserConfigDir: Windows
# keeps it under %APPDATA%\AField, every other platform under ~/.afield. field.ps1 can be run by
# pwsh on those too, so both are honoured rather than the Windows one assumed - reading the wrong
# file would announce a workspace the backend is not about to open.
$onWindows = $true
if ($null -ne (Get-Variable -Name IsWindows -ErrorAction SilentlyContinue)) {
    $onWindows = $IsWindows
}
if ($onWindows) {
    $appData = $env:APPDATA
    if ([string]::IsNullOrWhiteSpace($appData)) {
        $appData = Join-Path (Join-Path $HOME "AppData") "Roaming"
    }
    $workspacePreferenceFile = Join-Path (Join-Path $appData "AField") "last-workspace.txt"
} else {
    $workspacePreferenceFile = Join-Path (Join-Path $HOME ".afield") "last-workspace.txt"
}
$defaultWorkspace = Join-Path (Join-Path $HOME "Documents") "AFieldWorkspace"

# field.sh says all of this on stderr, and these lines are diagnostics rather than output: only
# --print-workspace writes to stdout, so it stays pipeable.
function Write-FieldNote {
    param([string] $Message)
    [Console]::Error.WriteLine($Message)
}

function Show-FieldUsage {
    Write-Host @"
Usage: .\field.ps1 [<workspace>] [options] [backend arguments]

Builds Field, then launches the backend and the Electron window together.

  <workspace>              the directory holding your Field documents; a bare
                           first argument is taken as the workspace
  -w, --workspace <path>   the same, stated explicitly
  --workspace=<path>       the same again
  -d, --document <name>    open this document straight away instead of the
                           welcome page, and instead of whatever was open last
  --document=<name>        the same again
  --read-only              open documents without writing back to them
  --offline                build from Gradle's cached dependencies without
                           touching the network (also AFIELD_OFFLINE=1)
  --print-workspace        print the workspace this launch would use, then exit
  -h, --help               show this message

Use --read-only for a document that lives inside a repository. Opening one
normally writes into its folder - Field saves the editor's cursor position, the
box frames, a backup, history metrics, an operation transcript and a screenshot
- which turns the folder dirty in git just from looking at the document. With
--read-only Field loads it, runs it, and never writes back:

  .\field.ps1 dev_docs\agents\demos\midi_clip --read-only

A single document works too - a folder holding a metadata.edn, or that file
itself. Either as the workspace argument or after -d, that names the document to
open, and the workspace is the folder above it, which is where a document lives.

  .\field.ps1 -d myDocument                    in the remembered workspace
  .\field.ps1 C:\AFieldWorkspace -d myDocument
  .\field.ps1 C:\AFieldWorkspace\myDocument    the same, as one path

A document named here has to exist already: Field creates a document that does
not, so a typo would otherwise open an empty new one under that name.

With no workspace given, Field reopens the one it last used, recorded once per
machine in
  $workspacePreferenceFile
so a different clone or worktree of this repository may have been what last
wrote it. Naming a workspace here makes it the remembered one for next time.

Any other argument is passed through to the backend unchanged. See also
AFIELD_SKIP_BUILD=1 to skip the build check, and PORT to move off 9092.
"@
}

# Expand the way the user's shell would have, had they not quoted it, and resolve against the
# directory they were standing in rather than the checkout.
function Resolve-FieldArgumentPath {
    param([string] $Path)
    $trimmed = $Path.Trim()
    if ($trimmed -eq "~") { return $HOME }
    if ($trimmed.StartsWith("~\") -or $trimmed.StartsWith("~/")) {
        return [System.IO.Path]::GetFullPath((Join-Path $HOME $trimmed.Substring(2)))
    }
    if ([System.IO.Path]::IsPathRooted($trimmed)) {
        return [System.IO.Path]::GetFullPath($trimmed)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $invokedFrom $trimmed))
}

# What makes a directory a Field document rather than an ordinary folder: the metadata file the
# backend writes. Both names are accepted because both are read (SavingContext.METADATA_EDN and
# METADATA_JSON, either of which welcomeDirCandidates accepts), so this recognises every document
# the welcome page lists.
function Test-FieldDocumentDirectory {
    param([string] $Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return $false }
    if (Test-Path -LiteralPath (Join-Path $Path "metadata.edn") -PathType Leaf) { return $true }
    if (Test-Path -LiteralPath (Join-Path $Path "metadata.json") -PathType Leaf) { return $true }
    return $false
}

# Sets $script:fieldWorkspace, plus $script:fieldDocument when what was named turns out to be a
# document rather than a workspace. Both spellings get typed, and a document folder passed
# straight through as the workspace would open a workspace that merely looks empty - its
# documents are its *subdirectories*.
function Resolve-FieldWorkspaceRequest {
    param([string] $Request)
    $raw = Resolve-FieldArgumentPath $Request
    $script:fieldDocument = ""
    $leaf = Split-Path -Leaf $raw
    if ($leaf -eq "metadata.edn" -or $leaf -eq "metadata.json") {
        $raw = Split-Path -Parent $raw
        $script:fieldDocument = Split-Path -Leaf $raw
        $raw = Split-Path -Parent $raw
    } elseif (Test-FieldDocumentDirectory $raw) {
        $script:fieldDocument = $leaf
        $raw = Split-Path -Parent $raw
    }
    $script:fieldWorkspace = $raw
}

$workspaceRequested = ""
$documentRequested = ""
$printWorkspace = $false
$readOnlyRequested = $false
$passthroughSeen = $false
$backendArgs = @()

$argIndex = 0
while ($argIndex -lt $args.Count) {
    $arg = [string] $args[$argIndex]
    $argIndex += 1
    if ($arg -eq "-h" -or $arg -eq "--help") {
        Show-FieldUsage
        exit 0
    } elseif ($arg -eq "--print-workspace") {
        $printWorkspace = $true
    } elseif ($arg -eq "-w" -or $arg -eq "--workspace") {
        if ($argIndex -ge $args.Count) {
            Write-FieldNote "field: $arg needs a directory"
            exit 2
        }
        $workspaceRequested = [string] $args[$argIndex]
        $argIndex += 1
    } elseif ($arg.StartsWith("--workspace=")) {
        $workspaceRequested = $arg.Substring("--workspace=".Length)
    } elseif ($arg -eq "-d" -or $arg -eq "--document") {
        if ($argIndex -ge $args.Count) {
            Write-FieldNote "field: $arg needs a document"
            exit 2
        }
        $documentRequested = [string] $args[$argIndex]
        $argIndex += 1
    } elseif ($arg.StartsWith("--document=")) {
        $documentRequested = $arg.Substring("--document=".Length)
    } elseif ($arg -eq "--read-only") {
        # The StartsWith("-") passthrough below already carried this to the backend, so the flag
        # worked before this arm existed. Two reasons it is spelled out anyway. The banner: a
        # Field that silently refuses to save is alarming if unannounced. And the passthrough sets
        # $passthroughSeen, which stops a *later* bare path being read as the workspace - so
        # `--read-only <path>` reached the backend with the path as an argument, where field.sh
        # resolves it. field.sh's own arm has a third reason with no counterpart here: it has to
        # survive run_in_terminal's re-exec, and this launcher has no relaunch path.
        $readOnlyRequested = $true
    } elseif ($arg -eq "--offline") {
        # A launcher flag, not a backend one: it changes how Gradle is invoked (Get-GradleOfflineArgs).
        $env:AFIELD_OFFLINE = "1"
    } elseif ($arg.StartsWith("-")) {
        $backendArgs += $arg
        $passthroughSeen = $true
    } elseif ($workspaceRequested -eq "" -and -not $passthroughSeen) {
        # A bare path is the workspace only while it is unambiguous: once any backend argument
        # has gone past, a bare word is more likely to be that argument's value than a workspace.
        $workspaceRequested = $arg
    } else {
        $backendArgs += $arg
        $passthroughSeen = $true
    }
}

# A trailing separator is what tab-completing a directory leaves behind - `-d docA\` - and is not
# part of the name. Stripped before the path test below, or a completed bare name would be read as
# a path. Guarded so a lone separator is not stripped to nothing.
while ($documentRequested.Length -gt 1 -and ($documentRequested.EndsWith("\") -or $documentRequested.EndsWith("/"))) {
    $documentRequested = $documentRequested.Substring(0, $documentRequested.Length - 1)
}

# A document named as a path carries its own workspace with it - a document lives one level below
# the workspace it belongs to - so that spelling is folded into the workspace request, which
# Resolve-FieldWorkspaceRequest already knows how to split. That is what makes `-d <path>` and a
# bare `<path>` naming a document agree; only a bare *name* is looked up in the workspace below.
$documentPathGiven = $false
if ($documentRequested -ne "" -and ($documentRequested.Contains("\") -or $documentRequested.Contains("/"))) {
    if ($workspaceRequested -ne "") {
        Write-FieldNote "field: name the document by name, not by path, when you also name a workspace"
        Write-FieldNote "field: (a path to a document already says which workspace it is in)"
        exit 2
    }
    $workspaceRequested = $documentRequested
    $documentRequested = ""
    $documentPathGiven = $true
}

$fieldWorkspace = ""
$fieldDocument = ""
$workspaceOrigin = ""
$workspaceWasADocument = $false

if ($workspaceRequested -ne "") {
    Resolve-FieldWorkspaceRequest $workspaceRequested
    $workspaceOrigin = "from the command line"
    if ($documentPathGiven -and $fieldDocument -eq "") {
        Write-FieldNote "field: there is no Field document at $fieldWorkspace"
        Write-FieldNote "field: a document is a folder holding a metadata.edn - name one that exists, or leave -d off"
        exit 2
    }
    if ($fieldDocument -ne "" -and -not $documentPathGiven) {
        $workspaceWasADocument = $true
    }
    if ([string]::IsNullOrWhiteSpace($fieldWorkspace) -or
        -not (Test-Path -LiteralPath $fieldWorkspace -PathType Container)) {
        Write-FieldNote "field: there is no workspace directory at $fieldWorkspace"
        Write-FieldNote "field: to start a new workspace there, create it first - New-Item -ItemType Directory -Force -Path `"$fieldWorkspace`""
        exit 2
    }
    $backendArgs += @("--workspace", $fieldWorkspace)
} else {
    # Read-only, always: the backend owns this file (Server.kt persists it). A launcher that
    # wrote it would re-point every other clone's next bare launch, including from a
    # --print-workspace that never started anything.
    $persisted = ""
    if (Test-Path -LiteralPath $workspacePreferenceFile -PathType Leaf) {
        $firstLine = Get-Content -LiteralPath $workspacePreferenceFile -TotalCount 1
        if ($null -ne $firstLine) { $persisted = ([string] $firstLine).Trim() }
    }
    if ($persisted -ne "") {
        $fieldWorkspace = $persisted
        $workspaceOrigin = "last used, per $workspacePreferenceFile"
    } else {
        $fieldWorkspace = $defaultWorkspace
        $workspaceOrigin = "the default; nothing recorded yet"
    }
}

# A bare `-d <name>` is looked up in whichever workspace the block above settled on - including
# the remembered one, which is the whole point of the short spelling.
if ($documentRequested -ne "") {
    $fieldDocument = $documentRequested.Trim()
}

# Whichever spelling it arrived by, the document has to exist. The backend *creates* a document
# that does not (saving mkdirs its folder), so an unchecked typo would open an empty new document
# under that name and say nothing about it. Checked before --print-workspace exits, so that
# reports a typo too.
if ($fieldDocument -ne "" -and -not (Test-FieldDocumentDirectory (Join-Path $fieldWorkspace $fieldDocument))) {
    Write-FieldNote "field: the workspace $fieldWorkspace has no document named $fieldDocument"
    Write-FieldNote "field: its documents are its subdirectories holding a metadata.edn; the welcome page lists them"
    exit 2
}

if ($printWorkspace) {
    Write-Output $fieldWorkspace
    exit 0
}

if ($workspaceWasADocument) {
    Write-FieldNote "field: $fieldDocument is a document, not a workspace - using the folder that holds it"
}
Write-FieldNote "field: workspace $fieldWorkspace ($workspaceOrigin)"
if ($fieldDocument -ne "") {
    Write-FieldNote "field: document $fieldDocument - opening it directly, not the welcome page"
    $backendArgs += @("--document", $fieldDocument)
}
if ($readOnlyRequested) {
    Write-FieldNote "field: read-only - nothing this session does will be written back to disk"
    $backendArgs += "--read-only"
}

Write-Host "===================================" -ForegroundColor Cyan
Write-Host "Starting Field4..." -ForegroundColor Cyan
Write-Host "===================================" -ForegroundColor Cyan
Write-Host ""

$exitCode = Run-Field @backendArgs

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
