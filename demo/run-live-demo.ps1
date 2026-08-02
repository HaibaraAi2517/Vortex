param(
  [string]$BaseUrl = $(if ($env:VORTEX_BASE_URL) { $env:VORTEX_BASE_URL } else { "http://localhost:8080" }),
  [ValidateRange(1, 10)]
  [int]$Runs = 1,
  [ValidateRange(30, 300)]
  [int]$MaxRunSeconds = 300
)

$ErrorActionPreference = "Stop"

$scriptRoot = Split-Path -Parent $PSCommandPath
$repoRoot = Resolve-Path (Join-Path $scriptRoot "..")
$scenarioScript = Join-Path $repoRoot "examples\quickstart-agent\run.ps1"
$powerShellExe = if ($PSVersionTable.PSEdition -eq "Core") { "pwsh" } else { "powershell" }

try {
  $health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health" -Method Get -TimeoutSec 10
} catch {
  throw "Vortex is not ready at $BaseUrl. Prewarm with examples\quickstart-agent\run.ps1 -StartQuickstart."
}

if ($health.status -ne "UP") {
  throw "Vortex health is '$($health.status)' instead of UP."
}

Write-Host "=== Vortex live demo ==="
Write-Host "Flow: store memory -> VectorOnly recall -> checkpoint -> kill worker -> recover -> continue"
Write-Host "Run limit: $MaxRunSeconds seconds per run"

$results = @()
for ($run = 1; $run -le $Runs; $run++) {
  $runId = [Guid]::NewGuid().ToString("N").Substring(0, 8)
  $namespace = "live-demo-" + (Get-Date -Format "yyyyMMddHHmmss") + "-$run-$runId"
  $stopwatch = [System.Diagnostics.Stopwatch]::StartNew()

  Write-Host ""
  Write-Host ("--- Run {0}/{1}: {2} ---" -f $run, $Runs, $namespace)

  & $powerShellExe `
    -NoProfile `
    -ExecutionPolicy Bypass `
    -File $scenarioScript `
    -BaseUrl $BaseUrl `
    -Namespace $namespace

  $exitCode = $LASTEXITCODE
  $stopwatch.Stop()
  $elapsedSeconds = [Math]::Round($stopwatch.Elapsed.TotalSeconds, 2)

  if ($exitCode -ne 0) {
    throw "Live demo run $run failed with exit code $exitCode."
  }
  if ($elapsedSeconds -gt $MaxRunSeconds) {
    throw "Live demo run $run took $elapsedSeconds seconds, exceeding the $MaxRunSeconds-second limit."
  }

  $results += [pscustomobject]@{
    Run = $run
    Namespace = $namespace
    Seconds = $elapsedSeconds
    Result = "PASS"
  }
  Write-Host ("LIVE DEMO RUN {0} PASS in {1} seconds." -f $run, $elapsedSeconds)
}

Write-Host ""
Write-Host "=== Repeatability summary ==="
$results | Format-Table -AutoSize | Out-String | Write-Host
Write-Host ("LIVE DEMO PASS: {0}/{0} runs completed within {1} seconds each." -f $Runs, $MaxRunSeconds)
