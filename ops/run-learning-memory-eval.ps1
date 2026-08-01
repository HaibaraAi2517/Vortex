param(
    [string]$Stamp = "",

    [string]$DatasetLocation = "classpath:llm-memory-eval-set-learning-v1-agent-feedback.json",

    [string]$BgeModelPath = "E:/1projects/claude/Vortex/models/bge-small-zh",

    [int]$L1MaxTokens = 8192,

    [string]$ReportRoot = "ops/eval-reports",

    [ValidateRange(0.0, 1.0)]
    [double]$MinProbeAverageNdcg = 0.90,

    [ValidateRange(0, 50)]
    [int]$MinRankImprovedScenarioCount = 0,

    [ValidateRange(0, 50)]
    [int]$MinNdcgImprovedScenarioCount = 0,

    [switch]$SkipComposeUp,

    [switch]$SkipPackage
)

$ErrorActionPreference = "Stop"

function Assert-PathExists {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Label not found: $Path"
    }
}

function Assert-LastExitCodeZero {
    param([string]$Label)
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

function Get-LatestReportArtifact {
    param(
        [string]$Directory,
        [string]$Filter
    )
    return Get-ChildItem -LiteralPath $Directory -Filter $Filter -File |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $repoRoot

if ([string]::IsNullOrWhiteSpace($Stamp)) {
    $Stamp = "learning-v1-agent-feedback-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}

$modelDir = (Resolve-Path $BgeModelPath).Path
$normalizedReportRoot = $ReportRoot.TrimEnd('/').TrimEnd([char]92)
$reportDir = Join-Path $repoRoot ($normalizedReportRoot + "/" + $Stamp)
$milvusCollection = "vortex_learning_eval_" + ($Stamp -replace "-", "_")
$minioKeyPrefix = "learning-eval/$Stamp/"
$evalCliJar = Join-Path $repoRoot "vortex-app/target/vortex-app-0.1.0-eval-cli.jar"

Assert-PathExists -Path $modelDir -Label "BGE model directory"
Assert-PathExists -Path (Join-Path $modelDir "model.onnx") -Label "BGE model.onnx"
Assert-PathExists -Path (Join-Path $modelDir "tokenizer.json") -Label "BGE tokenizer.json"

if (-not $SkipComposeUp) {
    docker compose up -d --wait
    Assert-LastExitCodeZero -Label "docker compose up"
}

if (-not $SkipPackage) {
    mvn -pl vortex-app -am -DskipTests package
    Assert-LastExitCodeZero -Label "mvn package"
}

Assert-PathExists -Path $evalCliJar -Label "eval CLI jar"
New-Item -ItemType Directory -Force -Path $reportDir | Out-Null

$env:BGE_MODEL_PATH = $modelDir
$env:VORTEX_GENERATION_ENABLED = "false"
$env:VORTEX_LEARNING_EVAL_DATASET_LOCATION = $DatasetLocation
$env:VORTEX_LEARNING_EVAL_REPORT_OUTPUT_DIR = $reportDir
$env:VORTEX_STORAGE_L1_MAX_TOKENS = "$L1MaxTokens"
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION = $milvusCollection
$env:MINIO_KEY_PREFIX = $minioKeyPrefix
$env:VORTEX_SHADOW_PERSISTENCE_PATH = (Join-Path $reportDir "shadow-eval.json")
$env:VORTEX_PERSISTENCE_DLQ_PATH = (Join-Path $reportDir "persistence-dlq.jsonl")
$env:VORTEX_PERSISTENCE_PROCESSED_KEYS_PATH = (Join-Path $reportDir "processed-keys.txt")
$env:VORTEX_LEARNING_EVAL_MIN_PROBE_AVERAGE_NDCG = "$MinProbeAverageNdcg"
$env:VORTEX_LEARNING_EVAL_MIN_RANK_IMPROVED_SCENARIO_COUNT = "$MinRankImprovedScenarioCount"
$env:VORTEX_LEARNING_EVAL_MIN_NDCG_IMPROVED_SCENARIO_COUNT = "$MinNdcgImprovedScenarioCount"

Write-Host "Starting deterministic learning memory eval"
Write-Host "  Stamp       : $Stamp"
Write-Host "  Dataset     : $DatasetLocation"
Write-Host "  Report Dir  : $reportDir"
Write-Host "  Collection  : $milvusCollection"
Write-Host "  MinIO Prefix: $minioKeyPrefix"
Write-Host "  Min Probe NDCG: $MinProbeAverageNdcg"
Write-Host "  Min Rank Improved Scenarios: $MinRankImprovedScenarioCount"
Write-Host "  Min NDCG Improved Scenarios: $MinNdcgImprovedScenarioCount"

java -jar $evalCliJar learning
Assert-LastExitCodeZero -Label "learning eval CLI run"

$reportJson = Get-LatestReportArtifact -Directory $reportDir -Filter "learning-memory-eval-*.json"
$reportMarkdown = Get-LatestReportArtifact -Directory $reportDir -Filter "learning-memory-eval-*.md"
if ($null -eq $reportJson) {
    throw "Learning eval report json not found in: $reportDir"
}
if ($null -eq $reportMarkdown) {
    throw "Learning eval report markdown not found in: $reportDir"
}

$report = Get-Content -Raw $reportJson.FullName | ConvertFrom-Json
if (-not [bool]$report.gatePassed) {
    throw "Learning eval gate failed. Report JSON: $($reportJson.FullName)"
}

Write-Host ""
Write-Host "Completed deterministic learning memory eval"
Write-Host "  Report JSON : $($reportJson.FullName)"
Write-Host "  Report MD   : $($reportMarkdown.FullName)"
Write-Host "  Gate Passed : $($report.gatePassed)"
Write-Host "  Scenarios   : $($report.scenarioCount)"
Write-Host "  Feedback    : $($report.feedbackSubmitted)"
Write-Host "  Probe Hit   : $($report.aggregate.probeAllRelevantHitRate)"

[pscustomobject]@{
    Stamp = $Stamp
    ReportDir = $reportDir
    ReportJsonPath = $reportJson.FullName
    ReportMarkdownPath = $reportMarkdown.FullName
    GeneratedAt = $report.generatedAt
    ProfileId = $report.profileId
    DatasetLocation = $report.datasetLocation
    GatePassed = [bool]$report.gatePassed
    ScenarioCount = [int]$report.scenarioCount
    FeedbackSubmitted = [int]$report.feedbackSubmitted
    ProbeAllRelevantHitRate = [double]$report.aggregate.probeAllRelevantHitRate
    ActiveUpdateCountBefore = [long]$report.aggregate.activeUpdateCountBefore
    ActiveUpdateCountAfter = [long]$report.aggregate.activeUpdateCountAfter
    PendingRecallSessions = [int]$report.aggregate.pendingRecallSessions
}
