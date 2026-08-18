[CmdletBinding()]
param(
  [string]$ProjectName = "vortex-it-$([Guid]::NewGuid().ToString('N').Substring(0, 12))",
  [switch]$IncludeFullLifecycle
)

$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = (Resolve-Path (Join-Path $scriptRoot "..")).Path
$composeFile = Join-Path $projectRoot "docker-compose.it.yml"

if ($ProjectName -notmatch '^vortex-it-[a-z0-9][a-z0-9-]{0,40}$') {
  throw "ProjectName must start with 'vortex-it-' and contain only lowercase letters, digits, and hyphens."
}

function Get-PublishedPort {
  param(
    [Parameter(Mandatory = $true)][string]$Service,
    [Parameter(Mandatory = $true)][int]$ContainerPort
  )

  $binding = docker compose -p $ProjectName -f $composeFile port $Service $ContainerPort
  if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($binding)) {
    throw "Unable to resolve published port for $Service/$ContainerPort."
  }
  return [int]($binding.Trim() -split ':')[-1]
}

function New-SecureRandomBase64 {
  param([int]$Bytes = 32)

  $buffer = New-Object byte[] $Bytes
  $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
  try {
    $rng.GetBytes($buffer)
  } finally {
    $rng.Dispose()
  }
  return [Convert]::ToBase64String($buffer)
}

function Test-OwnedResources {
  $containerIds = @(docker compose -p $ProjectName -f $composeFile ps -q)
  if ($LASTEXITCODE -ne 0) {
    return $false
  }
  foreach ($containerId in $containerIds) {
    $labelJson = docker inspect --format '{{json .Config.Labels}}' $containerId
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($labelJson)) {
      return $false
    }
    $labels = $labelJson | ConvertFrom-Json
    if ($labels.'com.vortex.test.run-id' -ne $env:VORTEX_IT_RUN_ID) {
      return $false
    }
  }
  return $true
}

$managedEnvironment = @(
  "COMPOSE_PROJECT_NAME",
  "VORTEX_IT_RUN_ID",
  "VORTEX_IT_MINIO_USER",
  "VORTEX_IT_MINIO_PASSWORD"
)
$previousEnvironment = @{}
foreach ($name in $managedEnvironment) {
  $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

$env:COMPOSE_PROJECT_NAME = $ProjectName
$env:VORTEX_IT_RUN_ID = [Guid]::NewGuid().ToString('N')
$env:VORTEX_IT_MINIO_USER = "vortex-it-$($env:VORTEX_IT_RUN_ID.Substring(0, 8))"
$env:VORTEX_IT_MINIO_PASSWORD = New-SecureRandomBase64 -Bytes 32
$started = $false

Push-Location $projectRoot
try {
  docker compose -p $ProjectName -f $composeFile config --quiet
  if ($LASTEXITCODE -ne 0) {
    throw "Integration-test Compose configuration is invalid."
  }

  $started = $true
  docker compose -p $ProjectName -f $composeFile up -d --wait
  if ($LASTEXITCODE -ne 0) {
    throw "Integration-test dependencies failed to start."
  }

  if (-not (Test-OwnedResources)) {
    throw "Integration-test resource ownership validation failed."
  }

  $minioPort = Get-PublishedPort -Service "minio" -ContainerPort 9000
  $milvusPort = Get-PublishedPort -Service "milvus" -ContainerPort 19530

  $mavenArgs = @(
    "-B",
    "-Pit",
    "verify",
    "-pl", "vortex-app",
    "-am",
    "-Dvortex.it.minio-endpoint=http://127.0.0.1:$minioPort",
    "-Dvortex.it.minio-access-key=$env:VORTEX_IT_MINIO_USER",
    "-Dvortex.it.minio-secret-key=$env:VORTEX_IT_MINIO_PASSWORD",
    "-Dvortex.it.milvus-host=127.0.0.1",
    "-Dvortex.it.milvus-port=$milvusPort"
  )
  if ($IncludeFullLifecycle) {
    $mavenArgs += "-Dvortex.it.fullLifecycleExclude="
  }

  & mvn @mavenArgs
  if ($LASTEXITCODE -ne 0) {
    throw "Integration tests failed with exit code $LASTEXITCODE."
  }
} finally {
  if ($started) {
    if (Test-OwnedResources) {
      docker compose -p $ProjectName -f $composeFile down --volumes --remove-orphans
    } else {
      Write-Error "Skipped cleanup because resource ownership could not be verified for project $ProjectName."
    }
  }
  Pop-Location
  foreach ($name in $managedEnvironment) {
    [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], "Process")
  }
}
