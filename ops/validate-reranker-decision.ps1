param(
    [Parameter(Mandatory = $true)]
    [string]$AnalysisPath,

    [Parameter(Mandatory = $true)]
    [string]$SplitManifestPath,

    [Parameter(Mandatory = $true)]
    [ValidateSet("DEV", "VALIDATION")]
    [string]$Phase,

    [string]$CandidateMode = "Vector+CrossEncoderReranker",

    [string]$BaselineMode = "VectorOnly",

    [string]$ExpectedModel = "",

    [string]$ExpectedModelVersion = "",

    [string]$ExpectedModelSha256 = ""
)

$ErrorActionPreference = "Stop"

function Get-FileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Resolve-ManifestArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$ManifestDirectory,
        [Parameter(Mandatory = $true)][string]$FileName
    )
    $path = [System.IO.Path]::GetFullPath((Join-Path $ManifestDirectory $FileName))
    $root = [System.IO.Path]::GetFullPath($ManifestDirectory).TrimEnd("\", "/")
    $prefix = $root + [System.IO.Path]::DirectorySeparatorChar
    if (-not $path.StartsWith($prefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Manifest artifact '$FileName' escapes '$ManifestDirectory'."
    }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Manifest artifact '$path' does not exist."
    }
    return $path
}

function Add-Rule {
    param(
        [System.Collections.Generic.List[object]]$Rules,
        [string]$Name,
        [bool]$Passed,
        [object]$Actual,
        [object]$Required
    )
    $Rules.Add([pscustomobject][ordered]@{
        name = $Name
        passed = $Passed
        actual = $Actual
        required = $Required
    })
}

function Is-SingleValue {
    param(
        [object[]]$Values,
        [string]$Expected = ""
    )
    $normalized = @($Values |
        ForEach-Object { [string]$_ } |
        Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
        Sort-Object -Unique)
    if ($normalized.Count -ne 1) {
        return $false
    }
    return [string]::IsNullOrWhiteSpace($Expected) -or
        $normalized[0].Equals($Expected, [System.StringComparison]::Ordinal)
}

$analysisFullPath = (Resolve-Path -LiteralPath $AnalysisPath).Path
$manifestFullPath = (Resolve-Path -LiteralPath $SplitManifestPath).Path
$analysis = Get-Content -LiteralPath $analysisFullPath -Raw | ConvertFrom-Json
$manifest = Get-Content -LiteralPath $manifestFullPath -Raw | ConvertFrom-Json
$manifestDirectory = Split-Path -Parent $manifestFullPath

$checksumPath = Resolve-ManifestArtifact -ManifestDirectory $manifestDirectory -FileName ([string]$manifest.manifestChecksumFile)
$checksumLine = [System.IO.File]::ReadAllText($checksumPath).Trim()
if ($checksumLine -notmatch "^([0-9a-fA-F]{64})\s+(.+)$") {
    throw "Split manifest checksum file has invalid format."
}
if (-not (Get-FileSha256 $manifestFullPath).Equals(
        $Matches[1],
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Split manifest SHA-256 drift."
}

$partitionName = if ($Phase -eq "DEV") { "dev" } else { "validation" }
$partition = $manifest.partitions.$partitionName
if ($null -eq $partition) {
    throw "Split manifest has no '$partitionName' partition."
}
$datasetPath = Resolve-ManifestArtifact -ManifestDirectory $manifestDirectory -FileName ([string]$partition.datasetFile)
$datasetSha256 = Get-FileSha256 $datasetPath

$comparisonName = "$CandidateMode vs $BaselineMode"
$comparison = @($analysis.comparisons | Where-Object { [string]$_.comparison -eq $comparisonName } | Select-Object -First 1)
if ($comparison.Count -ne 1) {
    throw "Analysis does not contain comparison '$comparisonName'."
}
$comparison = $comparison[0]
$diagnostics = @($analysis.rerankDiagnosticsSummaries | Where-Object { [string]$_.mode -eq $CandidateMode } | Select-Object -First 1)
if ($diagnostics.Count -ne 1) {
    throw "Analysis does not contain rerank diagnostics for '$CandidateMode'."
}
$diagnostics = $diagnostics[0]

if ($Phase -eq "VALIDATION" -and (
        [string]::IsNullOrWhiteSpace($ExpectedModel) -or
        [string]::IsNullOrWhiteSpace($ExpectedModelVersion) -or
        [string]::IsNullOrWhiteSpace($ExpectedModelSha256))) {
    throw "VALIDATION requires ExpectedModel, ExpectedModelVersion, and ExpectedModelSha256."
}
if (-not [string]::IsNullOrWhiteSpace($ExpectedModelSha256) -and
    $ExpectedModelSha256 -notmatch "^[0-9a-fA-F]{64}$") {
    throw "ExpectedModelSha256 must contain 64 hex characters."
}

$minimumRecallDelta = if ($Phase -eq "DEV") { 0.0200d } else { 0.0150d }
$minimumNdcgDelta = 0.0100d
$minimumMrrDelta = -0.0050d
$minimumChangedOrderRate = 0.1000d
$maximumP95DeltaMs = 250.0d
$maximumP99DeltaMs = 500.0d
$requiredModel = if ($ExpectedModel) { $ExpectedModel } else { "one non-empty value" }
$requiredVersion = if ($ExpectedModelVersion) { $ExpectedModelVersion } else { "one non-empty value" }
$requiredSha256 = if ($ExpectedModelSha256) { $ExpectedModelSha256 } else { "one non-empty value" }

$rules = [System.Collections.Generic.List[object]]::new()
Add-Rule $rules "partition-name" ([System.IO.Path]::GetFileName($datasetPath) -eq [string]$partition.datasetFile) ([System.IO.Path]::GetFileName($datasetPath)) ([string]$partition.datasetFile)
Add-Rule $rules "dataset-sha256" ($datasetSha256 -eq ([string]$partition.datasetSha256).ToLowerInvariant()) $datasetSha256 ([string]$partition.datasetSha256)
Add-Rule $rules "analysis-dataset-sha256" ([string]$analysis.sourceDatasetSha256 -eq $datasetSha256) ([string]$analysis.sourceDatasetSha256) $datasetSha256
Add-Rule $rules "analysis-dataset-path" ([System.IO.Path]::GetFullPath([string]$analysis.sourceDataset) -eq $datasetPath) ([string]$analysis.sourceDataset) $datasetPath
Add-Rule $rules "case-count" ([int]$analysis.cases -eq 120 -and [int]$partition.caseCount -eq 120) ([int]$analysis.cases) 120
Add-Rule $rules "run-errors" ([int]$analysis.errors -eq 0) ([int]$analysis.errors) 0
Add-Rule $rules "reranker-type" (Is-SingleValue @($diagnostics.rerankerTypes) "CROSS_ENCODER") (@($diagnostics.rerankerTypes) -join ",") "CROSS_ENCODER"
Add-Rule $rules "candidate-pool-strategy" (Is-SingleValue @($diagnostics.candidatePoolStrategies) "VECTOR_BASELINE_TOP_40") (@($diagnostics.candidatePoolStrategies) -join ",") "VECTOR_BASELINE_TOP_40"
Add-Rule $rules "candidate-pool-limit" (@($diagnostics.candidatePoolLimits).Count -eq 1 -and [int]@($diagnostics.candidatePoolLimits)[0] -eq 40) (@($diagnostics.candidatePoolLimits) -join ",") 40
Add-Rule $rules "model-name-consistency" (Is-SingleValue @($diagnostics.models) $ExpectedModel) (@($diagnostics.models) -join ",") $requiredModel
Add-Rule $rules "model-version-consistency" (Is-SingleValue @($diagnostics.modelVersions) $ExpectedModelVersion) (@($diagnostics.modelVersions) -join ",") $requiredVersion
Add-Rule $rules "model-sha256-consistency" (Is-SingleValue @($diagnostics.modelSha256) $ExpectedModelSha256) (@($diagnostics.modelSha256) -join ",") $requiredSha256
Add-Rule $rules "rerank-identifiable" ([bool]$comparison.rerankEffectConclusionPermitted) ([bool]$comparison.rerankEffectConclusionPermitted) $true
Add-Rule $rules "recall-delta" ([double]$comparison.recallDelta -ge $minimumRecallDelta) ([double]$comparison.recallDelta) ">= $minimumRecallDelta"
Add-Rule $rules "recall-ci-lower" ([double]$comparison.recallCi95Lower -gt 0.0d) ([double]$comparison.recallCi95Lower) "> 0"
Add-Rule $rules "ndcg-delta" ([double]$comparison.ndcgDelta -ge $minimumNdcgDelta) ([double]$comparison.ndcgDelta) ">= $minimumNdcgDelta"
Add-Rule $rules "ndcg-ci-lower" ([double]$comparison.ndcgCi95Lower -ge 0.0d) ([double]$comparison.ndcgCi95Lower) ">= 0"
Add-Rule $rules "mrr-delta" ([double]$comparison.mrrDelta -ge $minimumMrrDelta) ([double]$comparison.mrrDelta) ">= $minimumMrrDelta"
Add-Rule $rules "changed-order-rate" ([double]$diagnostics.changedOrderRate -ge $minimumChangedOrderRate) ([double]$diagnostics.changedOrderRate) ">= $minimumChangedOrderRate"
Add-Rule $rules "latency-p95-delta-ms" ([double]$comparison.latencyP95DeltaMs -le $maximumP95DeltaMs) ([double]$comparison.latencyP95DeltaMs) "<= $maximumP95DeltaMs"
Add-Rule $rules "latency-p99-delta-ms" ([double]$comparison.latencyP99DeltaMs -le $maximumP99DeltaMs) ([double]$comparison.latencyP99DeltaMs) "<= $maximumP99DeltaMs"

$environment = $analysis.environmentSnapshot
Add-Rule $rules "environment-snapshot" ($null -ne $environment) ($null -ne $environment) $true
if ($null -ne $environment) {
    Add-Rule $rules "hardware-description" (-not [string]::IsNullOrWhiteSpace([string]$environment.hardwareDescription)) ([string]$environment.hardwareDescription) "non-empty"
    Add-Rule $rules "gpu-description" (-not [string]::IsNullOrWhiteSpace([string]$environment.gpuDescription)) ([string]$environment.gpuDescription) "non-empty"
    Add-Rule $rules "java-version" (-not [string]::IsNullOrWhiteSpace([string]$environment.javaVersion)) ([string]$environment.javaVersion) "non-empty"
    Add-Rule $rules "os-name" (-not [string]::IsNullOrWhiteSpace([string]$environment.osName)) ([string]$environment.osName) "non-empty"
}

$failedRules = @($rules | Where-Object { -not $_.passed })
$result = [pscustomobject][ordered]@{
    phase = $Phase
    passed = $failedRules.Count -eq 0
    candidateMode = $CandidateMode
    baselineMode = $BaselineMode
    analysisPath = $analysisFullPath
    analysisSha256 = Get-FileSha256 $analysisFullPath
    datasetPath = $datasetPath
    datasetSha256 = $datasetSha256
    thresholds = [pscustomobject][ordered]@{
        minimumRecallDelta = $minimumRecallDelta
        recallCi95LowerMustBePositive = $true
        minimumNdcgDelta = $minimumNdcgDelta
        ndcgCi95LowerMinimum = 0.0d
        minimumMrrDelta = $minimumMrrDelta
        minimumChangedOrderRate = $minimumChangedOrderRate
        maximumP95DeltaMs = $maximumP95DeltaMs
        maximumP99DeltaMs = $maximumP99DeltaMs
    }
    rules = $rules
    failedRuleCount = $failedRules.Count
}

$result
if ($failedRules.Count -gt 0) {
    $summary = $failedRules | ForEach-Object {
        "$($_.name): actual=$($_.actual) required=$($_.required)"
    }
    throw "Reranker $Phase decision gate failed: $($summary -join '; ')"
}
