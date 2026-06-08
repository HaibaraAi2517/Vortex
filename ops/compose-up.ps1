$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptRoot "..")

function Assert-Command {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Name
  )

  if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
    throw "Missing required command: $Name"
  }
}

function Wait-Http {
  param(
    [Parameter(Mandatory = $true)]
    [string]$Name,

    [Parameter(Mandatory = $true)]
    [string]$Url,

    [int]$Attempts = 60,
    [int]$DelaySeconds = 2
  )

  Write-Host "Waiting for $Name at $Url"
  for ($i = 1; $i -le $Attempts; $i++) {
    try {
      Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5 | Out-Null
      Write-Host "$Name is healthy"
      return
    } catch {
      Start-Sleep -Seconds $DelaySeconds
    }
  }

  throw "$Name did not become healthy: $Url"
}

Assert-Command docker

Push-Location $projectRoot
try {
  Write-Host "Starting docker compose dependencies"
  docker compose up -d

  Wait-Http -Name "etcd" -Url "http://localhost:2379/health"
  Wait-Http -Name "MinIO" -Url "http://localhost:9000/minio/health/live"
  Wait-Http -Name "Milvus" -Url "http://localhost:9091/healthz" -Attempts 90 -DelaySeconds 3

  Write-Host ""
  Write-Host "Compose dependencies are ready."
  Write-Host ""
  Write-Host "Next steps:"
  Write-Host "1. Start the application:"
  Write-Host "   mvn spring-boot:run -pl vortex-app"
  Write-Host "2. Verify observability endpoints:"
  Write-Host "   curl http://localhost:8080/api/v1/memory/health"
  Write-Host "   curl http://localhost:8080/api/v1/memory/health/catalog"
  Write-Host "   curl http://localhost:8080/actuator/prometheus"
  Write-Host "3. Run the API walkthrough:"
  Write-Host "   BASE_URL=http://localhost:8080 bash ops/demo.sh"
  Write-Host ""
  Write-Host "For the default automated regression, you usually do not need this script:"
  Write-Host "   mvn verify -pl vortex-app -am"
} finally {
  Pop-Location
}
