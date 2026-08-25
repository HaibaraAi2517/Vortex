[CmdletBinding()]
param(
  [switch]$StartQuickstart,
  [switch]$UsePublishedImage,
  [switch]$NonInteractive,
  [string]$BaseUrl = $(if ($env:VORTEX_BASE_URL) {
      $env:VORTEX_BASE_URL
    } elseif ($env:VORTEX_HTTP_PORT) {
      "http://127.0.0.1:$($env:VORTEX_HTTP_PORT)"
    } else {
      "http://127.0.0.1:8080"
    }),
  [string]$ApiToken = $env:VORTEX_SECURITY_BEARER_TOKEN,
  [string]$ModelBaseUrl = $(if ($env:MODEL_BASE_URL) { $env:MODEL_BASE_URL } else { "https://api.deepseek.com/v1" }),
  [string]$ModelApiKey = $env:MODEL_API_KEY,
  [string]$ModelName = $(if ($env:MODEL_NAME) { $env:MODEL_NAME } else { "deepseek-chat" }),
  [string]$Namespace = $env:VORTEX_NAMESPACE,
  [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"

function Write-Section {
  param([string]$Title)
  Write-Host ""
  Write-Host ("=== {0} ===" -f $Title)
}

function New-RandomHex {
  param([int]$Bytes)
  $buffer = New-Object byte[] $Bytes
  $generator = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $generator.GetBytes($buffer)
  } finally {
    $generator.Dispose()
  }
  return -join ($buffer | ForEach-Object { $_.ToString("x2") })
}

function Wait-Vortex {
  param([int]$Seconds)
  $deadline = (Get-Date).AddSeconds($Seconds)
  while ((Get-Date) -lt $deadline) {
    try {
      $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Method Get -TimeoutSec 5
      if ($health.status -eq "UP") {
        return
      }
    } catch {
      Start-Sleep -Seconds 2
    }
  }
  throw "Vortex did not become healthy at $BaseUrl within $Seconds seconds."
}

function Stop-ProcessTree {
  param([System.Diagnostics.Process]$Process)
  if ($null -eq $Process -or $Process.HasExited) {
    return
  }
  $isWindowsHost = -not (Get-Variable -Name IsWindows -ErrorAction SilentlyContinue) -or $IsWindows
  if ($isWindowsHost) {
    & taskkill.exe /PID $Process.Id /T /F | Out-Null
  } else {
    Stop-Process -Id $Process.Id -Force
  }
  try {
    $Process.WaitForExit()
  } catch {
    # The operating system may have already released the process handle.
  }
}

$scriptRoot = Split-Path -Parent $PSCommandPath
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..")).Path
$pomPath = Join-Path $scriptRoot "pom.xml"
$mavenCommand = Get-Command mvn.cmd -ErrorAction SilentlyContinue
if ($null -eq $mavenCommand) {
  $mavenCommand = Get-Command mvn -ErrorAction Stop
}

if ([string]::IsNullOrWhiteSpace($ModelApiKey)) {
  if ($NonInteractive) {
    throw "Set MODEL_API_KEY or pass -ModelApiKey when using -NonInteractive."
  }
  $secureModelApiKey = Read-Host "Enter the DeepSeek API Key (input is hidden)" -AsSecureString
  $credential = New-Object System.Management.Automation.PSCredential("deepseek", $secureModelApiKey)
  $ModelApiKey = $credential.GetNetworkCredential().Password
  if ([string]::IsNullOrWhiteSpace($ModelApiKey)) {
    throw "A model API key is required."
  }
}

$runId = [Guid]::NewGuid().ToString("N").Substring(0, 12)
if ([string]::IsNullOrWhiteSpace($Namespace)) {
  $Namespace = "quickstart-real-agent-$(Get-Date -Format 'yyyyMMddHHmmss')-$runId"
}

if ($StartQuickstart) {
  Write-Section "Starting the Vortex quickstart stack"
  Get-Command docker -ErrorAction Stop | Out-Null
  if ([string]::IsNullOrWhiteSpace($ApiToken)) {
    $ApiToken = New-RandomHex -Bytes 32
  }
  $env:VORTEX_SECURITY_BEARER_TOKEN = $ApiToken
  if ([string]::IsNullOrWhiteSpace($env:MINIO_ROOT_USER)) {
    $env:MINIO_ROOT_USER = "vortex-local"
  }
  if ([string]::IsNullOrWhiteSpace($env:MINIO_ROOT_PASSWORD)) {
    $env:MINIO_ROOT_PASSWORD = New-RandomHex -Bytes 24
  }
  if ([string]::IsNullOrWhiteSpace($env:REDIS_PASSWORD)) {
    $env:REDIS_PASSWORD = New-RandomHex -Bytes 24
  }
  if ([string]::IsNullOrWhiteSpace($env:VORTEX_SECURITY_NAMESPACE_PATTERNS)) {
    $env:VORTEX_SECURITY_NAMESPACE_PATTERNS = "quickstart-*"
  }
  Push-Location $repoRoot
  try {
    $composeArguments = @("compose", "-f", "docker-compose.quickstart.yml", "up")
    if ($UsePublishedImage) {
      $composeArguments += "--no-build"
    } else {
      $composeArguments += "--build"
    }
    $composeArguments += @("-d")
    & docker @composeArguments
    $composeExitCode = $LASTEXITCODE
    if ($composeExitCode -ne 0 -and $UsePublishedImage) {
      Write-Warning "The published Vortex image is unavailable. Falling back to a local source build."
      & docker compose -f docker-compose.quickstart.yml up --build -d
      $composeExitCode = $LASTEXITCODE
    }
    if ($composeExitCode -ne 0) {
      throw "Quickstart Docker Compose failed with exit code $composeExitCode."
    }
  } finally {
    Pop-Location
  }
}

if ([string]::IsNullOrWhiteSpace($ApiToken)) {
  throw "Set VORTEX_SECURITY_BEARER_TOKEN, pass -ApiToken, or use -StartQuickstart."
}

Write-Section "Waiting for Vortex"
Wait-Vortex -Seconds $TimeoutSeconds
Write-Host "Vortex is healthy at $BaseUrl"

$stateFile = Join-Path ([System.IO.Path]::GetTempPath()) "vortex-real-agent-$runId.json"
$pendingStateFile = "$stateFile.pending"
foreach ($path in @($stateFile, $pendingStateFile)) {
  if (Test-Path -LiteralPath $path) {
    Remove-Item -LiteralPath $path -Force
  }
}

$env:VORTEX_BASE_URL = $BaseUrl
$env:VORTEX_SECURITY_BEARER_TOKEN = $ApiToken
$env:VORTEX_NAMESPACE = $Namespace
$env:MODEL_BASE_URL = $ModelBaseUrl
$env:MODEL_API_KEY = $ModelApiKey
$env:MODEL_NAME = $ModelName
$env:DEMO_RUN_ID = $runId
$env:DEMO_STATE_FILE = $stateFile
$env:DEMO_REPOSITORY_ROOT = $repoRoot
$env:DEMO_INTERACTIVE = if ($NonInteractive) { "false" } else { "true" }
$env:DEMO_MODE = "phase1"

Write-Section "Phase 1: real model, tools, memory, and checkpoint"
Write-Host "Namespace: $Namespace"
Write-Host "Model: $ModelName at $ModelBaseUrl"
$phaseOne = Start-Process `
  -FilePath $mavenCommand.Source `
  -ArgumentList @("-q", "-f", $pomPath, "exec:java") `
  -WorkingDirectory $repoRoot `
  -NoNewWindow `
  -PassThru

try {
  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline -and -not (Test-Path -LiteralPath $stateFile)) {
    if ($phaseOne.HasExited) {
      throw "Phase-one Agent exited before writing its checkpoint state. ExitCode=$($phaseOne.ExitCode)"
    }
    Start-Sleep -Milliseconds 500
  }
  if (-not (Test-Path -LiteralPath $stateFile)) {
    throw "Timed out waiting for the phase-one Agent checkpoint."
  }

  Write-Section "Crash injection"
  Write-Host ("Checkpoint is durable. Phase-one process tree PID {0} will be terminated." -f $phaseOne.Id)
  foreach ($remaining in 3, 2, 1) {
    Write-Host ("Hard crash in {0}..." -f $remaining)
    Start-Sleep -Seconds 1
  }
  Stop-ProcessTree -Process $phaseOne
  Write-Host "Phase-one process was terminated. Its in-process Agent state is gone."

  Write-Section "Phase 2: recover and continue in a new process"
  $interactiveMessage = if ($NonInteractive) {
    "Interactive console is disabled for this non-interactive run."
  } else {
    "After recovery, type questions at the YOU > prompt. Use /help to list commands."
  }
  Write-Host $interactiveMessage
  $env:DEMO_MODE = "phase2"
  & $mavenCommand.Source -q -f $pomPath exec:java
  if ($LASTEXITCODE -ne 0) {
    throw "Phase-two Agent failed with exit code $LASTEXITCODE."
  }
} finally {
  Stop-ProcessTree -Process $phaseOne
  foreach ($path in @($stateFile, $pendingStateFile)) {
    if (Test-Path -LiteralPath $path) {
      Remove-Item -LiteralPath $path -Force
    }
  }
}

Write-Section "One-click demo finished"
Write-Host "Quickstart is still running unless the calling launcher stops it."
