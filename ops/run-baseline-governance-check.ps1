param(
    [string]$Profile = "official-v3.1-real-agent-workload-strict",

    [string]$EvidenceStamp = "20260603-v3-1-real-agent-workload-official-strict-audit-003",

    [string]$ReportRoot = "ops/eval-fixtures/baselines",

    [ValidateRange(1, 10)]
    [int]$ExpectedRounds = 3,

    [switch]$SkipMavenTest,

    [switch]$SkipPackage,

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

function Assert-Value {
    param(
        [string]$Name,
        [object]$Expected,
        [object]$Actual
    )
    if ($Expected -ne $Actual) {
        throw "$Name expected=$Expected actual=$Actual"
    }
}

function Get-LatestJsonReport {
    param([string]$RunDir)
    $reports = @(Get-ChildItem -LiteralPath $RunDir -Filter "llm-memory-eval-*.json" -File |
        Sort-Object LastWriteTimeUtc -Descending)
    if ($reports.Count -eq 0) {
        throw "No llm-memory-eval JSON report found in: $RunDir"
    }
    return $reports[0]
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $repoRoot

$evalCliJar = Join-Path $repoRoot "vortex-app/target/vortex-app-0.1.0-eval-cli.jar"
$auditDir = Join-Path $repoRoot ($ReportRoot.TrimEnd("/").TrimEnd([char]92) + "/" + $EvidenceStamp)
$summaryJson = Join-Path $auditDir "baseline-audit-summary.json"

Write-Host "Starting baseline governance check"
Write-Host "  Profile        : $Profile"
Write-Host "  Evidence Stamp : $EvidenceStamp"
Write-Host "  Evidence Root  : $ReportRoot"

if (-not $SkipMavenTest) {
    Write-Host ""
    Write-Host "Running Maven unit tests"
    mvn test
    Assert-LastExitCodeZero -Label "mvn test"
}

if (-not $SkipPackage) {
    Write-Host ""
    Write-Host "Packaging eval CLI"
    mvn -pl vortex-app -am -DskipTests package
    Assert-LastExitCodeZero -Label "mvn package"
}

Assert-PathExists -Path $evalCliJar -Label "eval CLI jar"

Write-Host ""
Write-Host "Checking eval baseline profiles"
java -jar $evalCliJar verify --list-profiles
Assert-LastExitCodeZero -Label "eval-cli verify --list-profiles"

java -jar $evalCliJar verify --profile $Profile --describe
Assert-LastExitCodeZero -Label "eval-cli verify --profile $Profile --describe"

if (-not $SkipEvidenceVerify) {
    Write-Host ""
    Write-Host "Verifying accepted evidence reports"
    Assert-PathExists -Path $auditDir -Label "evidence audit directory"
    Assert-PathExists -Path $summaryJson -Label "evidence audit summary JSON"

    $summary = Get-Content -LiteralPath $summaryJson -Raw | ConvertFrom-Json
    Assert-Value -Name "summary.OverallPassed" -Expected $true -Actual ([bool]$summary.OverallPassed)
    Assert-Value -Name "summary.AuditGate.Passed" -Expected $true -Actual ([bool]$summary.AuditGate.Passed)
    Assert-Value -Name "summary.ProfileGate.Passed" -Expected $true -Actual ([bool]$summary.ProfileGate.Passed)
    Assert-Value -Name "summary.StrictVerifierPassed" -Expected $true -Actual ([bool]$summary.StrictVerifierPassed)
    Assert-Value -Name "summary.Aggregate.EvalSuccessCount" -Expected $ExpectedRounds -Actual ([int]$summary.Aggregate.EvalSuccessCount)
    Assert-Value -Name "summary.Aggregate.VerifierPassCount" -Expected $ExpectedRounds -Actual ([int]$summary.Aggregate.VerifierPassCount)
    Assert-Value -Name "summary.Aggregate.CaseFailureCount" -Expected 0 -Actual ([int]$summary.Aggregate.CaseFailureCount)

    for ($round = 1; $round -le $ExpectedRounds; $round++) {
        $roundStamp = "{0}-run{1:d2}" -f $EvidenceStamp, $round
        $runDir = Join-Path $auditDir ("runs/" + $roundStamp)
        Assert-PathExists -Path $runDir -Label "evidence run directory"
        $report = Get-LatestJsonReport -RunDir $runDir
        Write-Host ("  Verifying round {0}: {1}" -f $round, $report.Name)
        java -jar $evalCliJar verify --profile $Profile $report.FullName
        Assert-LastExitCodeZero -Label "eval-cli verify evidence round $round"
    }
} else {
    Write-Host ""
    Write-Host "Skipping evidence report verification by request"
}

Write-Host ""
Write-Host "Baseline governance check passed"
