param(
    [string]$Profile = "learning-v1-agent-feedback-audit",

    [string]$DatasetLocation = "classpath:llm-memory-eval-set-learning-v1-hard-agent-feedback.json",

    [string]$EvidenceStamp = "20260609-learning-v1-agent-feedback-hard-governance-001",

    [string]$EvidenceRoot = "ops/eval-fixtures/learning",

    [ValidateRange(1, 10)]
    [int]$ExpectedEvidenceReports = 1,

    [string]$ReportRoot = "ops/eval-reports",

    [ValidateRange(0.0, 1.0)]
    [double]$MinProbeAverageNdcg = 0.90,

    [ValidateRange(0, 50)]
    [int]$MinRankImprovedScenarioCount = 5,

    [ValidateRange(0, 50)]
    [int]$MinNdcgImprovedScenarioCount = 5,

    [switch]$SkipMavenTest,

    [switch]$SkipPackage,

    [switch]$SkipLearningRun,

    [switch]$SkipComposeUp,

    [switch]$SkipEvidenceVerify
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
    $reports = @(Get-ChildItem -LiteralPath $Directory -Filter $Filter -File |
        Sort-Object LastWriteTimeUtc -Descending)
    if ($reports.Count -eq 0) {
        throw "No report matching '$Filter' found in: $Directory"
    }
    return $reports[0]
}

function Get-LearningEvidenceReports {
    param([string]$Directory)
    return @(Get-ChildItem -LiteralPath $Directory -Filter "learning-memory-eval-*.json" -File |
        Sort-Object Name)
}

function Assert-LearningReportThresholds {
    param([string]$ReportPath)
    $report = Get-Content -LiteralPath $ReportPath -Raw | ConvertFrom-Json
    if ($report.profileId -ne $Profile) {
        throw "profileId expected=$Profile actual=$($report.profileId) report=$ReportPath"
    }
    if (-not [bool]$report.gatePassed) {
        throw "gatePassed expected=true actual=$($report.gatePassed) report=$ReportPath"
    }
    if ($null -eq $report.aggregate) {
        throw "aggregate missing report=$ReportPath"
    }
    if ([int]$report.scenarioCount -lt 5) {
        throw "scenarioCount expected>=5 actual=$($report.scenarioCount) report=$ReportPath"
    }
    if ([int]$report.feedbackSubmitted -ne [int]$report.totalRecallCount) {
        throw "feedbackSubmitted expected=totalRecallCount actual=$($report.feedbackSubmitted)/$($report.totalRecallCount) report=$ReportPath"
    }
    if ([long]$report.aggregate.feedbackSampleCount -lt 30) {
        throw "feedbackSampleCount expected>=30 actual=$($report.aggregate.feedbackSampleCount) report=$ReportPath"
    }
    if ([int]$report.aggregate.pendingRecallSessions -ne 0) {
        throw "pendingRecallSessions expected=0 actual=$($report.aggregate.pendingRecallSessions) report=$ReportPath"
    }
    if ([double]$report.aggregate.probeAllRelevantHitRate -lt 0.90) {
        throw "probeAllRelevantHitRate expected>=0.90 actual=$($report.aggregate.probeAllRelevantHitRate) report=$ReportPath"
    }
    if ([double]$report.aggregate.probeAverageNdcg -lt $MinProbeAverageNdcg) {
        throw "probeAverageNdcg expected>=$MinProbeAverageNdcg actual=$($report.aggregate.probeAverageNdcg) report=$ReportPath"
    }
    if ([int]$report.aggregate.rankImprovedScenarioCount -lt $MinRankImprovedScenarioCount) {
        throw "rankImprovedScenarioCount expected>=$MinRankImprovedScenarioCount actual=$($report.aggregate.rankImprovedScenarioCount) report=$ReportPath"
    }
    if ([int]$report.aggregate.ndcgImprovedScenarioCount -lt $MinNdcgImprovedScenarioCount) {
        throw "ndcgImprovedScenarioCount expected>=$MinNdcgImprovedScenarioCount actual=$($report.aggregate.ndcgImprovedScenarioCount) report=$ReportPath"
    }
    if ([double]$report.aggregate.medianRelevantRankAfter -gt [double]$report.aggregate.medianRelevantRankBefore) {
        throw "medianRelevantRank expected after<=before actual=$($report.aggregate.medianRelevantRankBefore)->$($report.aggregate.medianRelevantRankAfter) report=$ReportPath"
    }
    if ([long]$report.aggregate.activeUpdateCountAfter -le [long]$report.aggregate.activeUpdateCountBefore) {
        throw "activeUpdateCount expected after>before actual=$($report.aggregate.activeUpdateCountBefore)->$($report.aggregate.activeUpdateCountAfter) report=$ReportPath"
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $repoRoot

$evalCliJar = Join-Path $repoRoot "vortex-app/target/vortex-app-0.1.1-eval-cli.jar"
$learningRunScript = Join-Path $repoRoot "ops/run-learning-memory-eval.ps1"
$stamp = "learning-governance-" + (Get-Date -Format "yyyyMMdd-HHmmss")
$reportDir = Join-Path $repoRoot ($ReportRoot.TrimEnd("/").TrimEnd([char]92) + "/" + $stamp)

Write-Host "Starting learning governance check"
Write-Host "  Profile    : $Profile"
Write-Host "  Dataset    : $DatasetLocation"
Write-Host "  Report Root: $ReportRoot"
Write-Host "  Evidence   : $(if ([string]::IsNullOrWhiteSpace($EvidenceStamp)) { '<none>' } else { $EvidenceStamp })"

if (-not $SkipMavenTest) {
    Write-Host ""
    Write-Host "Running Maven unit tests"
    mvn -pl vortex-app -am test
    Assert-LastExitCodeZero -Label "mvn test"
}

if (-not $SkipPackage) {
    Write-Host ""
    Write-Host "Packaging eval CLI"
    mvn -pl vortex-app -am -DskipTests package
    Assert-LastExitCodeZero -Label "mvn package"
}

Assert-PathExists -Path $evalCliJar -Label "eval CLI jar"
Assert-PathExists -Path $learningRunScript -Label "learning eval script"

if (-not $SkipLearningRun) {
    Write-Host ""
    Write-Host "Running strict deterministic learning workload"
    $runArgs = @{
        Stamp = $stamp
        DatasetLocation = $DatasetLocation
        ReportRoot = $ReportRoot
        MinProbeAverageNdcg = $MinProbeAverageNdcg
        MinRankImprovedScenarioCount = $MinRankImprovedScenarioCount
        MinNdcgImprovedScenarioCount = $MinNdcgImprovedScenarioCount
        SkipPackage = $true
    }
    if ($SkipComposeUp) {
        $runArgs.SkipComposeUp = $true
    }
    & $learningRunScript @runArgs
    Assert-LastExitCodeZero -Label "strict deterministic learning workload"

    $reportJson = Get-LatestReportArtifact -Directory $reportDir -Filter "learning-memory-eval-*.json"
    Write-Host ""
    Write-Host "Verifying generated learning report"
    java -jar $evalCliJar learning verify --profile $Profile $reportJson.FullName
    Assert-LastExitCodeZero -Label "learning verify generated report"
    Assert-LearningReportThresholds -ReportPath $reportJson.FullName
} else {
    Write-Host ""
    Write-Host "Skipping deterministic learning workload by request"
}

if (-not $SkipEvidenceVerify -and -not [string]::IsNullOrWhiteSpace($EvidenceStamp)) {
    Write-Host ""
    Write-Host "Verifying promoted learning evidence"
    $evidenceDir = Join-Path $repoRoot ($EvidenceRoot.TrimEnd("/").TrimEnd([char]92) + "/" + $EvidenceStamp)
    Assert-PathExists -Path $evidenceDir -Label "learning evidence directory"
    $reports = Get-LearningEvidenceReports -Directory $evidenceDir
    if ($reports.Count -ne $ExpectedEvidenceReports) {
        throw "learning evidence report count expected=$ExpectedEvidenceReports actual=$($reports.Count)"
    }
    foreach ($report in $reports) {
        Write-Host "  Verifying evidence report: $($report.Name)"
        java -jar $evalCliJar learning verify --profile $Profile $report.FullName
        Assert-LastExitCodeZero -Label "learning verify evidence report $($report.Name)"
        Assert-LearningReportThresholds -ReportPath $report.FullName
    }
} elseif ($SkipEvidenceVerify) {
    Write-Host ""
    Write-Host "Skipping promoted evidence verification by request"
} else {
    Write-Host ""
    Write-Host "No promoted learning evidence stamp supplied; fixture verification skipped"
}

Write-Host ""
Write-Host "Learning governance check passed"
