$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptRoot "..")

Push-Location $projectRoot
try {
  Write-Host "Starting docker compose services"
  docker compose up -d

  Write-Host ""
  Write-Host "Services are running."
  Write-Host ""
  Write-Host "To stop and keep containers visible in Docker Desktop:"
  Write-Host "  docker compose stop"
  Write-Host ""
  Write-Host "To remove containers from Docker Desktop:"
  Write-Host "  docker compose down"
} finally {
  Pop-Location
}
