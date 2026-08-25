[CmdletBinding()]
param(
  [switch]$BuildCurrentSource,
  [switch]$KeepQuickstart
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $PSCommandPath
$repoRoot = (Resolve-Path (Join-Path $scriptRoot "..\..")).Path
$runner = Join-Path $scriptRoot "run.ps1"

$environmentNames = @(
  "MINIO_ROOT_USER",
  "MINIO_ROOT_PASSWORD",
  "REDIS_PASSWORD",
  "VORTEX_BASE_URL",
  "VORTEX_SECURITY_BEARER_TOKEN",
  "VORTEX_SECURITY_NAMESPACE_PATTERNS",
  "VORTEX_NAMESPACE",
  "MODEL_BASE_URL",
  "MODEL_API_KEY",
  "MODEL_NAME",
  "DEMO_RUN_ID",
  "DEMO_STATE_FILE",
  "DEMO_REPOSITORY_ROOT",
  "DEMO_MODE"
)
$originalEnvironment = @{}
foreach ($name in $environmentNames) {
  $originalEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

$quickstartAttempted = $false
try {
  Write-Host "Vortex DeepSeek one-click demo"
  Write-Host "The API key will be requested securely and will not be written to disk."
  Write-Host "Vortex will be built from the current source checkout."
  Write-Host ""

  $quickstartAttempted = $true
  & $runner `
    -StartQuickstart `
    -ModelBaseUrl "https://api.deepseek.com/v1" `
    -ModelName "deepseek-chat"
} finally {
  $hasQuickstartCredentials = -not ([string]::IsNullOrWhiteSpace($env:MINIO_ROOT_USER)) `
    -and -not ([string]::IsNullOrWhiteSpace($env:MINIO_ROOT_PASSWORD)) `
    -and -not ([string]::IsNullOrWhiteSpace($env:REDIS_PASSWORD)) `
    -and -not ([string]::IsNullOrWhiteSpace($env:VORTEX_SECURITY_BEARER_TOKEN))
  if ($quickstartAttempted -and -not $KeepQuickstart -and $hasQuickstartCredentials) {
    Write-Host ""
    Write-Host "=== Stopping the Quickstart stack ==="
    Push-Location $repoRoot
    try {
      docker compose -f docker-compose.quickstart.yml down
      if ($LASTEXITCODE -ne 0) {
        Write-Warning "Quickstart cleanup returned exit code $LASTEXITCODE."
      }
    } finally {
      Pop-Location
    }
  }

  foreach ($name in $environmentNames) {
    [Environment]::SetEnvironmentVariable($name, $originalEnvironment[$name], "Process")
  }
}
