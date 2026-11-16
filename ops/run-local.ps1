[CmdletBinding()]
param(
    [int]$Port = 8080,
    [string]$DataDir = "",
    [switch]$SkipBuild
)
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
Push-Location $repo
try {
    if (-not $env:BGE_MODEL_PATH) {
        & python ops/download-model.py
        if ($LASTEXITCODE -ne 0) { throw "Model preparation failed" }
    }
    if ($DataDir) { $env:VORTEX_DATA_DIR = [IO.Path]::GetFullPath($DataDir) }
    if (-not $SkipBuild) {
        & mvn -B -q -DskipTests package -pl vortex-app -am
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
    }
    $jar = Get-ChildItem -LiteralPath (Join-Path $repo "vortex-app/target") -Filter "vortex-app-*-exec.jar" |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw "Application jar is missing; run without -SkipBuild" }
    Write-Host "Vortex memory workbench: http://127.0.0.1:$Port/"
    Write-Host "API documentation: http://127.0.0.1:$Port/swagger-ui.html"
    Write-Host "Persistent data: $(if ($env:VORTEX_DATA_DIR) { $env:VORTEX_DATA_DIR } else { './data/vortex' })"
    & java --enable-preview -jar $jar.FullName --spring.profiles.active=local "--server.port=$Port"
    if ($LASTEXITCODE -ne 0) { throw "Vortex exited with code $LASTEXITCODE" }
} finally { Pop-Location }
