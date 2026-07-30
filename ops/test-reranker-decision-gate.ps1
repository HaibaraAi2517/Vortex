$ErrorActionPreference = "Stop"

function Write-Json {
    param([object]$Value, [string]$Path)
    $json = ($Value | ConvertTo-Json -Depth 30) -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText(
        $Path,
        $json + "`n",
        [System.Text.UTF8Encoding]::new($false))
}

function Get-Sha256 {
    param([string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Assert-ThrowsMatch {
    param([scriptblock]$Action, [string]$Pattern, [string]$Label)
    try {
        & $Action | Out-Null
    } catch {
        if ($_.Exception.Message -notmatch $Pattern) {
            throw "$Label failed with unexpected error: $($_.Exception.Message)"
        }
        return
    }
    throw "$Label did not reject the invalid decision input."
}

$validator = Join-Path $PSScriptRoot "validate-reranker-decision.ps1"
$tempParent = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "tmp"))
$testRoot = Join-Path $tempParent ("reranker-decision-test-" + [guid]::NewGuid().ToString("N"))
if (-not $testRoot.StartsWith(
        $tempParent + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create decision test outside '$tempParent'."
}

try {
    New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
    $devDatasetPath = Join-Path $testRoot "dev.json"
    $validationDatasetPath = Join-Path $testRoot "validation.json"
    [System.IO.File]::WriteAllText($devDatasetPath, "[]`n", [System.Text.UTF8Encoding]::new($false))
    [System.IO.File]::WriteAllText($validationDatasetPath, "[]`n", [System.Text.UTF8Encoding]::new($false))

    $manifestPath = Join-Path $testRoot "manifest.json"
    $checksumPath = Join-Path $testRoot "manifest.sha256"
    $manifest = [pscustomobject][ordered]@{
        partitions = [pscustomobject][ordered]@{
            dev = [pscustomobject][ordered]@{
                datasetFile = "dev.json"
                datasetSha256 = Get-Sha256 $devDatasetPath
                caseCount = 120
            }
            validation = [pscustomobject][ordered]@{
                datasetFile = "validation.json"
                datasetSha256 = Get-Sha256 $validationDatasetPath
                caseCount = 120
            }
        }
        manifestChecksumFile = "manifest.sha256"
    }
    Write-Json $manifest $manifestPath
    $manifestSha = Get-Sha256 $manifestPath
    [System.IO.File]::WriteAllText(
        $checksumPath,
        "$manifestSha  manifest.json`n",
        [System.Text.UTF8Encoding]::new($false))

    $modelSha = "c" * 64
    $analysisPath = Join-Path $testRoot "analysis.json"
    $analysis = [pscustomobject][ordered]@{
        sourceDataset = $devDatasetPath
        sourceDatasetSha256 = Get-Sha256 $devDatasetPath
        cases = 120
        errors = 0
        environmentSnapshot = [pscustomobject]@{
            hardwareDescription = "synthetic-hardware"
            gpuDescription = "synthetic-gpu"
            javaVersion = "21"
            osName = "synthetic-os"
        }
        rerankDiagnosticsSummaries = @(
            [pscustomobject]@{
                mode = "Vector+CrossEncoderReranker"
                rerankerTypes = @("CROSS_ENCODER")
                models = @("synthetic-model")
                modelVersions = @("1.0.0")
                modelSha256 = @($modelSha)
                candidatePoolStrategies = @("VECTOR_BASELINE_TOP_40")
                candidatePoolLimits = @(40)
                changedOrderRate = 0.5
            }
        )
        comparisons = @(
            [pscustomobject]@{
                comparison = "Vector+CrossEncoderReranker vs VectorOnly"
                rerankEffectConclusionPermitted = $true
                recallDelta = 0.05
                recallCi95Lower = 0.01
                ndcgDelta = 0.03
                ndcgCi95Lower = 0.0
                mrrDelta = 0.01
                latencyP95DeltaMs = 100.0
                latencyP99DeltaMs = 200.0
            }
        )
    }
    Write-Json $analysis $analysisPath

    $devResult = & $validator -AnalysisPath $analysisPath -SplitManifestPath $manifestPath -Phase DEV
    if (-not $devResult.passed) {
        throw "Valid DEV decision fixture did not pass."
    }

    $analysis.comparisons[0].recallCi95Lower = 0.0
    Write-Json $analysis $analysisPath
    Assert-ThrowsMatch {
        & $validator -AnalysisPath $analysisPath -SplitManifestPath $manifestPath -Phase DEV
    } "recall-ci-lower" "DEV quality gate"

    $analysis.comparisons[0].recallCi95Lower = 0.01
    $analysis.sourceDataset = $validationDatasetPath
    $analysis.sourceDatasetSha256 = Get-Sha256 $validationDatasetPath
    Write-Json $analysis $analysisPath
    Assert-ThrowsMatch {
        & $validator -AnalysisPath $analysisPath -SplitManifestPath $manifestPath -Phase VALIDATION
    } "requires ExpectedModel" "Validation model freeze gate"

    $validationResult = & $validator -AnalysisPath $analysisPath -SplitManifestPath $manifestPath -Phase VALIDATION -ExpectedModel "synthetic-model" -ExpectedModelVersion "1.0.0" -ExpectedModelSha256 $modelSha
    if (-not $validationResult.passed) {
        throw "Valid VALIDATION decision fixture did not pass."
    }

    [pscustomobject][ordered]@{
        DevPass = "PASS"
        DevQualityRejection = "PASS"
        ValidationModelFreezeRejection = "PASS"
        ValidationPass = "PASS"
    }
} finally {
    if ((Test-Path -LiteralPath $testRoot) -and
        $testRoot.StartsWith(
            $tempParent + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
