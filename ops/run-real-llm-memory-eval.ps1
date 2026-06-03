param(
    [Parameter(Mandatory = $true)]
    [string]$ApiKey,

    [string]$BaseUrl = "https://sub2.congmingai.com",

    [string]$Model = "gpt-5.2",

    [string]$Stamp = "",

    [string]$DatasetLocation = "",

    [string]$BgeModelPath = "E:/1projects/claude/Vortex/models/bge-small-zh",

    [int]$L1MaxTokens = 96,

    [string]$Modes = "BASELINE_NO_MEMORY,VORTEX_MEMORY,VORTEX_RECOVERED_MEMORY",

    [ValidateRange(1, 128)]
    [int]$EvalParallelism = 1,

    [string]$ReportRoot = "ops/eval-reports",

    [switch]$SkipComposeUp,

    [switch]$SkipPackage,

    [switch]$SkipGenerationPreflight
)

$ErrorActionPreference = "Stop"

function Resolve-NormalizedBaseUrl {
    param([string]$Value)
    $normalized = $Value.TrimEnd("/")
    if ($normalized.EndsWith("/v1")) {
        return $normalized
    }
    return "$normalized/v1"
}

function Assert-PathExists {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Label not found: $Path"
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

function Assert-LastExitCodeZero {
    param([string]$Label)
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

function Test-GenerationEndpoint {
    param(
        [string]$BaseUrl,
        [string]$ApiKey,
        [string]$Model
    )
    $uri = "$BaseUrl/chat/completions"
    $headers = @{
        Authorization = "Bearer $ApiKey"
        "Content-Type" = "application/json"
    }
    $body = @{
        model = $Model
        messages = @(@{
            role = "user"
            content = "Return exactly OK."
        })
        temperature = 0
        max_tokens = 8
    } | ConvertTo-Json -Depth 8

    try {
        Invoke-WebRequest -Uri $uri -Method Post -Headers $headers -Body $body -TimeoutSec 30 | Out-Null
    } catch {
        $statusCode = $null
        $responseBody = ""
        if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            try {
                $responseBody = $reader.ReadToEnd()
            } finally {
                $reader.Dispose()
            }
        }
        $message = "Generation preflight failed"
        if ($null -ne $statusCode) {
            $message += " status=$statusCode"
        }
        if (-not [string]::IsNullOrWhiteSpace($responseBody)) {
            $message += " body=$responseBody"
        } else {
            $message += ": $($_.Exception.Message)"
        }
        throw $message
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $repoRoot

if ([string]::IsNullOrWhiteSpace($Stamp)) {
    $Stamp = "real-bge-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}

$normalizedBaseUrl = Resolve-NormalizedBaseUrl -Value $BaseUrl
$modelDir = (Resolve-Path $BgeModelPath).Path
$normalizedReportRoot = $ReportRoot.TrimEnd('/').TrimEnd([char]92)
$reportDir = Join-Path $repoRoot ($normalizedReportRoot + "/" + $Stamp)
$milvusCollection = "vortex_memory_eval_" + ($Stamp -replace "-", "_")
$minioKeyPrefix = "eval/$Stamp/"
$evalCliJar = Join-Path $repoRoot "vortex-app/target/vortex-app-0.1.0-SNAPSHOT-eval-cli.jar"

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

if (-not $SkipGenerationPreflight) {
    Test-GenerationEndpoint -BaseUrl $normalizedBaseUrl -ApiKey $ApiKey -Model $Model
}

$env:BGE_MODEL_PATH = $modelDir
Remove-Item Env:VORTEX_KERNEL_EMBEDDING_BGE_SAFE_HASH_MODE -ErrorAction SilentlyContinue
$env:VORTEX_GENERATION_ENABLED = "true"
$env:VORTEX_GENERATION_BASE_URL = $normalizedBaseUrl
$env:VORTEX_GENERATION_API_KEY = $ApiKey
$env:VORTEX_GENERATION_MODEL = $Model
$env:VORTEX_EVAL_MODES = $Modes
$env:VORTEX_EVAL_PARALLELISM = "$EvalParallelism"
$env:VORTEX_STORAGE_L1_MAX_TOKENS = "$L1MaxTokens"
$env:VORTEX_EVAL_REPORT_OUTPUT_DIR = $reportDir
$env:VORTEX_STORAGE_L2_MILVUS_COLLECTION = $milvusCollection
$env:MINIO_KEY_PREFIX = $minioKeyPrefix
if (-not [string]::IsNullOrWhiteSpace($DatasetLocation)) {
    $env:VORTEX_EVAL_DATASET_LOCATION = $DatasetLocation
} else {
    Remove-Item Env:VORTEX_EVAL_DATASET_LOCATION -ErrorAction SilentlyContinue
}

Write-Host "Starting real LLM memory eval"
Write-Host "  Base URL    : $normalizedBaseUrl"
Write-Host "  Model       : $Model"
$resolvedDatasetLocation = if ([string]::IsNullOrWhiteSpace($DatasetLocation)) {
    "classpath:llm-memory-eval-set.json"
} else {
    $DatasetLocation
}
Write-Host "  Dataset     : $resolvedDatasetLocation"
Write-Host "  Parallelism : $EvalParallelism"
Write-Host "  Report Dir  : $reportDir"
Write-Host "  Collection  : $milvusCollection"
Write-Host "  MinIO Prefix: $minioKeyPrefix"

java -jar $evalCliJar
Assert-LastExitCodeZero -Label "eval CLI run"

$reportJson = Get-LatestReportArtifact -Directory $reportDir -Filter "llm-memory-eval-*.json"
$reportMarkdown = Get-LatestReportArtifact -Directory $reportDir -Filter "llm-memory-eval-*.md"
Assert-PathExists -Path $reportDir -Label "Eval report directory"
if ($null -eq $reportJson) {
    throw "Eval report json not found in: $reportDir"
}
if ($null -eq $reportMarkdown) {
    throw "Eval report markdown not found in: $reportDir"
}

$report = Get-Content -Raw $reportJson.FullName | ConvertFrom-Json
Write-Host "Completed real LLM memory eval"
Write-Host "  Report JSON : $($reportJson.FullName)"
Write-Host "  Report MD   : $($reportMarkdown.FullName)"

[pscustomobject]@{
    Stamp = $Stamp
    ReportDir = $reportDir
    ReportJsonPath = $reportJson.FullName
    ReportMarkdownPath = $reportMarkdown.FullName
    GeneratedAt = $report.generatedAt
    TotalCases = $report.totalCases
    TotalRuns = $report.totalRuns
    DatasetLocation = $report.environment.datasetLocation
    GenerationBaseUrl = $report.environment.generationBaseUrl
    GenerationModel = $report.environment.generationModel
    L1MaxTokens = $report.environment.l1MaxTokens
    EvalSystemPromptSha256 = $report.environment.evalSystemPromptSha256
    EvalParallelism = if ($report.environment.PSObject.Properties["evalParallelism"]) { $report.environment.evalParallelism } else { $EvalParallelism }
    Modes = @($report.environment.modes)
    ModeSummaries = $report.modeSummaries
    RuntimeTelemetry = if ($report.PSObject.Properties["runtimeTelemetry"]) { $report.runtimeTelemetry } else { $null }
}
