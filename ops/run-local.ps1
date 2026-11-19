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
    if (-not $SkipBuild) {
        & mvn -B -q -DskipTests package -pl vortex-app -am
        if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
    }
    $jar = Get-ChildItem -LiteralPath (Join-Path $repo "vortex-app/target") -Filter "vortex-app-*-exec.jar" |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $jar) { throw "Application jar is missing; run without -SkipBuild" }
    if (-not $DataDir) {
        $DataDir = if ($env:VORTEX_DATA_DIR) { $env:VORTEX_DATA_DIR } else { Join-Path $repo "data/vortex" }
    }
    & (Join-Path $PSScriptRoot "local-package/start.ps1") -AppRoot $repo -Jar $jar.FullName -DataDir $DataDir -Port $Port -NoBrowser
} finally { Pop-Location }
