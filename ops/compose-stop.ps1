$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptRoot "..")

Push-Location $projectRoot
try {
  Write-Host "Stopping docker compose services without removing containers"
  docker compose stop

  Write-Host ""
  Write-Host "Services are stopped, but containers are still preserved."
  Write-Host "You can start them again from Docker Desktop or with:"
  Write-Host "  docker compose start"
} finally {
  Pop-Location
}
