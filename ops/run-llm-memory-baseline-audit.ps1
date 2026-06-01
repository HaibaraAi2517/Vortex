param(
    [Parameter(Mandatory = $true)]
    [string]$ApiKey,

    [string]$BaseUrl = "https://sub2.congmingai.com",

    [string]$Model = "gpt-5.2",

    [ValidateRange(1, 10)]
    [int]$Rounds = 5,

    [string]$AuditStamp = "",

    [string]$DatasetLocation = "classpath:llm-memory-eval-set-v2.json",

    [string]$BgeModelPath = "E:/1projects/claude/Vortex/models/bge-small-zh",

    [int]$L1MaxTokens = 96,

    [string]$Modes = "BASELINE_NO_MEMORY,VORTEX_MEMORY,VORTEX_RECOVERED_MEMORY",

    [string]$ReportRoot = "ops/eval-reports",

    [switch]$SkipComposeUp,

    [switch]$SkipPackage,

    [switch]$ForceRerunExisting,

    [ValidateRange(0, 15)]
    [int]$BaselineNoMemoryMaxCorrect = 0,

    [ValidateRange(0.0, 1.0)]
    [double]$MinVortexMemoryMeanAccuracy = 0.85,

    [ValidateRange(0.0, 1.0)]
    [double]$MinRecoveredMeanAccuracy = 0.95,

    [ValidateRange(0.0, 1.0)]
    [double]$MinRecoveredL2MeanHitRate = 0.95,

    [switch]$FailOnAuditGateFailure
)

$ErrorActionPreference = "Stop"

function Assert-PathExists {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "$Label not found: $Path"
    }
}

function New-RoundStamp {
    param(
        [string]$Prefix,
        [int]$RoundIndex
    )
    return "{0}-run{1:d2}" -f $Prefix, $RoundIndex
}

function ConvertTo-PlainObject {
    param([object]$Value)
    if ($null -eq $Value) {
        return $null
    }
    return $Value | ConvertTo-Json -Depth 20 | ConvertFrom-Json
}

function New-VerifyResult {
    param(
        [int]$ExitCode,
        [string]$Output
    )
    [pscustomobject]@{
        Passed = ($ExitCode -eq 0)
        ExitCode = $ExitCode
        Output = $Output.Trim()
    }
}

function Assert-LastExitCodeZero {
    param([string]$Label)
    if ($LASTEXITCODE -ne 0) {
        throw "$Label failed with exit code $LASTEXITCODE"
    }
}

function Format-MetricSequence {
    param($Values)
    if ($null -eq $Values -or $Values.Count -eq 0) {
        return ""
    }
    return (($Values | ForEach-Object { $_.ToString() }) -join ", ")
}

function Format-Decimal {
    param([Nullable[double]]$Value)
    if ($null -eq $Value) {
        return ""
    }
    return "{0:N4}" -f [double]$Value
}

function Get-MeanValue {
    param($Values)
    $items = @($Values)
    if ($items.Count -eq 0) {
        return $null
    }
    $sum = 0.0
    foreach ($value in $items) {
        $sum += [double]$value
    }
    return $sum / $items.Count
}

function New-AuditGateCheck {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Expected,
        [string]$Actual,
        [string]$Details = ""
    )
    return [pscustomobject]@{
        Name = $Name
        Passed = $Passed
        Expected = $Expected
        Actual = $Actual
        Details = $Details
    }
}

function Get-DistinctRunValueCount {
    param(
        [System.Collections.IEnumerable]$Runs,
        [string]$PropertyName
    )
    return @($Runs | ForEach-Object {
        $value = $_.PSObject.Properties[$PropertyName].Value
        if ($value -is [System.Array]) {
            $value -join ","
        } else {
            [string]$value
        }
    } | Sort-Object -Unique).Count
}

function New-AuditGateResult {
    param(
        [System.Collections.IEnumerable]$Runs,
        [int]$RequestedRounds,
        [int]$EvalSuccessCount,
        [double[]]$BaselineCorrectValues,
        [double[]]$MemoryAccuracyValues,
        [double[]]$RecoveredAccuracyValues,
        [double[]]$RecoveredL2HitRateValues,
        [int]$BaselineNoMemoryMaxCorrect,
        [double]$MinVortexMemoryMeanAccuracy,
        [double]$MinRecoveredMeanAccuracy,
        [double]$MinRecoveredL2MeanHitRate
    )
    $completedRuns = @($Runs | Where-Object { $_.Status -eq "completed" })
    $memoryMean = Get-MeanValue -Values $MemoryAccuracyValues
    $recoveredMean = Get-MeanValue -Values $RecoveredAccuracyValues
    $recoveredL2Mean = Get-MeanValue -Values $RecoveredL2HitRateValues
    $baselineMax = if (@($BaselineCorrectValues).Count -eq 0) { $null } else { ($BaselineCorrectValues | Measure-Object -Maximum).Maximum }
    $environmentStable = $completedRuns.Count -gt 0 `
        -and (Get-DistinctRunValueCount -Runs $completedRuns -PropertyName "DatasetLocation") -eq 1 `
        -and (Get-DistinctRunValueCount -Runs $completedRuns -PropertyName "GenerationBaseUrl") -eq 1 `
        -and (Get-DistinctRunValueCount -Runs $completedRuns -PropertyName "GenerationModel") -eq 1 `
        -and (Get-DistinctRunValueCount -Runs $completedRuns -PropertyName "L1MaxTokens") -eq 1 `
        -and (Get-DistinctRunValueCount -Runs $completedRuns -PropertyName "EvalSystemPromptSha256") -eq 1 `
        -and (Get-DistinctRunValueCount -Runs $completedRuns -PropertyName "Modes") -eq 1
    $checks = @(
        New-AuditGateCheck `
            -Name "evalSuccessCount" `
            -Passed ($EvalSuccessCount -eq $RequestedRounds) `
            -Expected "$RequestedRounds/$RequestedRounds" `
            -Actual "$EvalSuccessCount/$RequestedRounds"
        New-AuditGateCheck `
            -Name "environmentStable" `
            -Passed $environmentStable `
            -Expected "all completed runs share dataset/baseUrl/model/l1MaxTokens/promptSha/modes" `
            -Actual ("completedRuns={0}" -f $completedRuns.Count)
        New-AuditGateCheck `
            -Name "baselineNoMemoryMaxCorrect" `
            -Passed ($null -ne $baselineMax -and [double]$baselineMax -le $BaselineNoMemoryMaxCorrect -and @($BaselineCorrectValues).Count -eq $RequestedRounds) `
            -Expected ("max <= {0}" -f $BaselineNoMemoryMaxCorrect) `
            -Actual ("values=[{0}]" -f (Format-MetricSequence -Values $BaselineCorrectValues))
        New-AuditGateCheck `
            -Name "vortexMemoryMeanAccuracy" `
            -Passed ($null -ne $memoryMean -and $memoryMean -ge $MinVortexMemoryMeanAccuracy -and @($MemoryAccuracyValues).Count -eq $RequestedRounds) `
            -Expected (">= " + (Format-Decimal $MinVortexMemoryMeanAccuracy)) `
            -Actual (Format-Decimal $memoryMean)
        New-AuditGateCheck `
            -Name "recoveredMeanAccuracy" `
            -Passed ($null -ne $recoveredMean -and $recoveredMean -ge $MinRecoveredMeanAccuracy -and @($RecoveredAccuracyValues).Count -eq $RequestedRounds) `
            -Expected (">= " + (Format-Decimal $MinRecoveredMeanAccuracy)) `
            -Actual (Format-Decimal $recoveredMean)
        New-AuditGateCheck `
            -Name "recoveredL2MeanHitRate" `
            -Passed ($null -ne $recoveredL2Mean -and $recoveredL2Mean -ge $MinRecoveredL2MeanHitRate -and @($RecoveredL2HitRateValues).Count -eq $RequestedRounds) `
            -Expected (">= " + (Format-Decimal $MinRecoveredL2MeanHitRate)) `
            -Actual (Format-Decimal $recoveredL2Mean)
    )
    return [pscustomobject]@{
        Passed = @($checks | Where-Object { -not $_.Passed }).Count -eq 0
        Thresholds = [pscustomobject]@{
            BaselineNoMemoryMaxCorrect = $BaselineNoMemoryMaxCorrect
            MinVortexMemoryMeanAccuracy = $MinVortexMemoryMeanAccuracy
            MinRecoveredMeanAccuracy = $MinRecoveredMeanAccuracy
            MinRecoveredL2MeanHitRate = $MinRecoveredL2MeanHitRate
        }
        Metrics = [pscustomobject]@{
            VortexMemoryMeanAccuracy = $memoryMean
            RecoveredMeanAccuracy = $recoveredMean
            RecoveredL2MeanHitRate = $recoveredL2Mean
            BaselineNoMemoryMaxCorrect = $baselineMax
        }
        Checks = $checks
    }
}

function Get-AuditGateMarkdown {
    param([object]$AuditGate)
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.AppendLine("| Check | Passed | Expected | Actual | Details |")
    [void]$builder.AppendLine("| --- | --- | --- | --- | --- |")
    foreach ($check in @($AuditGate.Checks)) {
        [void]$builder.AppendLine("| $(Format-MarkdownCell $check.Name) | $(Format-MarkdownCell $check.Passed) | $(Format-MarkdownCell $check.Expected) | $(Format-MarkdownCell $check.Actual) | $(Format-MarkdownCell $check.Details) |")
    }
    return $builder.ToString()
}

function Resolve-DatasetFilePath {
    param(
        [string]$Location,
        [string]$RepoRoot
    )
    if ([string]::IsNullOrWhiteSpace($Location)) {
        return $null
    }
    if ($Location.StartsWith("classpath:")) {
        $resourceName = $Location.Substring(("classpath:").Length).TrimStart("/").TrimStart("\")
        return Join-Path $RepoRoot ("vortex-app/src/main/resources/" + $resourceName)
    }
    if ([System.IO.Path]::IsPathRooted($Location)) {
        return $Location
    }
    return Join-Path $RepoRoot $Location
}

function Get-DatasetCaseMap {
    param(
        [string]$Location,
        [string]$RepoRoot
    )
    $caseMap = @{}
    $datasetPath = Resolve-DatasetFilePath -Location $Location -RepoRoot $RepoRoot
    if ($null -eq $datasetPath -or -not (Test-Path -LiteralPath $datasetPath)) {
        return $caseMap
    }
    $cases = Get-Content -Raw $datasetPath | ConvertFrom-Json
    foreach ($case in @($cases)) {
        $caseMap[[string]$case.caseId] = [pscustomobject]@{
            CaseId = [string]$case.caseId
            Question = [string]$case.question
            ExpectedAnswer = [string]$case.expectedAnswer
            ExpectedFragments = @($case.expectedFragments | ForEach-Object { [string]$_ })
        }
    }
    return $caseMap
}

function Get-CaseMetadata {
    param(
        [hashtable]$DatasetCases,
        [string]$CaseId
    )
    if ($null -ne $DatasetCases -and $DatasetCases.ContainsKey($CaseId)) {
        return $DatasetCases[$CaseId]
    }
    return [pscustomobject]@{
        CaseId = $CaseId
        Question = ""
        ExpectedAnswer = ""
        ExpectedFragments = @()
    }
}

function Get-CaseFailureDetails {
    param(
        [System.Collections.IEnumerable]$Runs,
        [hashtable]$DatasetCases
    )
    $failures = New-Object System.Collections.Generic.List[object]
    foreach ($run in $Runs) {
        if ($run.Status -ne "completed" -or [string]::IsNullOrWhiteSpace($run.ReportJsonPath)) {
            continue
        }
        if (-not (Test-Path -LiteralPath $run.ReportJsonPath)) {
            continue
        }
        $report = Get-Content -Raw $run.ReportJsonPath | ConvertFrom-Json
        foreach ($result in @($report.results)) {
            if ($result.mode -eq "Baseline-NoMemory" -or $result.isCorrect -eq $true) {
                continue
            }
            $metadata = Get-CaseMetadata -DatasetCases $DatasetCases -CaseId ([string]$result.caseId)
            $returnedFragments = @($result.returnedFragmentIds | ForEach-Object { [string]$_ })
            $expectedFragments = @($metadata.ExpectedFragments | ForEach-Object { [string]$_ })
            $missingExpectedFragments = @($expectedFragments | Where-Object { $returnedFragments -notcontains $_ })
            $question = if ([string]::IsNullOrWhiteSpace($metadata.Question)) {
                [string]$result.question
            } else {
                [string]$metadata.Question
            }
            $failures.Add([pscustomobject]@{
                RoundIndex = $run.RoundIndex
                RoundStamp = $run.RoundStamp
                CaseId = [string]$result.caseId
                Mode = [string]$result.mode
                Question = $question
                ExpectedAnswer = [string]$metadata.ExpectedAnswer
                ExpectedFragments = $expectedFragments
                ReturnedFragmentIds = $returnedFragments
                MissingExpectedFragments = $missingExpectedFragments
                RecalledFromTiers = @($result.recalledFromTiers | ForEach-Object { [string]$_ })
                RecallHit = $result.recallHit
                EvictedBeforeAnswer = $result.evictedBeforeAnswer
                GeneratedAnswer = [string]$result.generatedAnswer
                ErrorMessage = $result.errorMessage
                ReportJsonPath = $run.ReportJsonPath
            }) | Out-Null
        }
    }
    return $failures.ToArray()
}

function Get-CaseFailureSummary {
    param(
        [object]$Failures,
        [int]$RoundCount
    )
    $items = @($Failures)
    $summary = New-Object System.Collections.Generic.List[object]
    foreach ($group in ($items | Group-Object -Property CaseId, Mode)) {
        $first = $group.Group[0]
        $missing = @($group.Group | ForEach-Object { $_.MissingExpectedFragments } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
        $returnedSets = @($group.Group | ForEach-Object { ($_.ReturnedFragmentIds -join ",") } | Sort-Object -Unique)
        $summary.Add([pscustomobject]@{
            CaseId = $first.CaseId
            Mode = $first.Mode
            FailureCount = @($group.Group).Count
            RoundCount = $RoundCount
            FailureRounds = @($group.Group | Sort-Object RoundIndex | ForEach-Object { $_.RoundIndex })
            RecallHitFailureCount = @($group.Group | Where-Object { $_.RecallHit -eq $true }).Count
            RecallMissFailureCount = @($group.Group | Where-Object { $_.RecallHit -eq $false }).Count
            MissingExpectedFragments = $missing
            ReturnedFragmentSets = $returnedSets
            ExpectedAnswer = $first.ExpectedAnswer
            ExpectedFragments = $first.ExpectedFragments
            Question = $first.Question
        }) | Out-Null
    }
    return @($summary.ToArray() | Sort-Object CaseId, Mode)
}

function Format-MarkdownCell {
    param([object]$Value)
    if ($null -eq $Value) {
        return ""
    }
    $text = if ($Value -is [System.Array]) {
        ($Value | ForEach-Object { [string]$_ }) -join ", "
    } else {
        [string]$Value
    }
    return ($text -replace "\r?\n", " " -replace "\|", "\|").Trim()
}

function Limit-Text {
    param(
        [string]$Value,
        [int]$MaxLength = 180
    )
    if ([string]::IsNullOrWhiteSpace($Value) -or $Value.Length -le $MaxLength) {
        return $Value
    }
    return $Value.Substring(0, $MaxLength) + "..."
}

function Get-CaseFailureSummaryMarkdown {
    param([object]$CaseFailureSummary)
    $items = @($CaseFailureSummary)
    if ($items.Count -eq 0) {
        return "No Vortex case failures."
    }
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.AppendLine("| Case | Mode | Failures | Rounds | Recall Hit | Recall Miss | Missing Expected Fragments | Expected Answer | Question |")
    [void]$builder.AppendLine("| --- | --- | ---: | --- | ---: | ---: | --- | --- | --- |")
    foreach ($item in $items) {
        [void]$builder.AppendLine("| $(Format-MarkdownCell $item.CaseId) | $(Format-MarkdownCell $item.Mode) | $($item.FailureCount)/$($item.RoundCount) | $(Format-MarkdownCell $item.FailureRounds) | $($item.RecallHitFailureCount) | $($item.RecallMissFailureCount) | $(Format-MarkdownCell $item.MissingExpectedFragments) | $(Format-MarkdownCell $item.ExpectedAnswer) | $(Format-MarkdownCell $item.Question) |")
    }
    return $builder.ToString()
}

function Get-CaseFailureDetailsMarkdown {
    param([object]$CaseFailureDetails)
    $items = @($CaseFailureDetails)
    if ($items.Count -eq 0) {
        return "No Vortex case failures."
    }
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.AppendLine("| Round | Case | Mode | Recall Hit | Returned Fragments | Missing Expected Fragments | Generated Answer |")
    [void]$builder.AppendLine("| ---: | --- | --- | --- | --- | --- | --- |")
    foreach ($item in ($items | Sort-Object RoundIndex, CaseId, Mode)) {
        $answerPreview = Limit-Text -Value $item.GeneratedAnswer
        [void]$builder.AppendLine("| $($item.RoundIndex) | $(Format-MarkdownCell $item.CaseId) | $(Format-MarkdownCell $item.Mode) | $(Format-MarkdownCell $item.RecallHit) | $(Format-MarkdownCell $item.ReturnedFragmentIds) | $(Format-MarkdownCell $item.MissingExpectedFragments) | $(Format-MarkdownCell $answerPreview) |")
    }
    return $builder.ToString()
}

function Invoke-ProcessCapture {
    param(
        [string]$FilePath,
        [string[]]$ArgumentList
    )
    $stdoutPath = [System.IO.Path]::GetTempFileName()
    $stderrPath = [System.IO.Path]::GetTempFileName()
    try {
        $process = Start-Process `
            -FilePath $FilePath `
            -ArgumentList $ArgumentList `
            -NoNewWindow `
            -Wait `
            -PassThru `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath
        $stdout = if (Test-Path -LiteralPath $stdoutPath) { Get-Content -Raw $stdoutPath } else { "" }
        $stderr = if (Test-Path -LiteralPath $stderrPath) { Get-Content -Raw $stderrPath } else { "" }
        $combined = @($stdout, $stderr) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            Output = ($combined -join [Environment]::NewLine).Trim()
        }
    } finally {
        Remove-Item -LiteralPath $stdoutPath -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath $stderrPath -ErrorAction SilentlyContinue
    }
}

function Get-LatestReportArtifact {
    param(
        [string]$Directory,
        [string]$Filter
    )
    if (-not (Test-Path -LiteralPath $Directory)) {
        return $null
    }
    return Get-ChildItem -LiteralPath $Directory -Filter $Filter -File |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
}

function Import-ExistingRun {
    param(
        [string]$ReportDir,
        [string]$RoundStamp
    )
    $reportJson = Get-LatestReportArtifact -Directory $ReportDir -Filter "llm-memory-eval-*.json"
    if ($null -eq $reportJson) {
        return $null
    }
    $reportMarkdown = Get-LatestReportArtifact -Directory $ReportDir -Filter "llm-memory-eval-*.md"
    $report = Get-Content -Raw $reportJson.FullName | ConvertFrom-Json
    return [pscustomobject]@{
        Stamp = $RoundStamp
        ReportDir = $ReportDir
        ReportJsonPath = $reportJson.FullName
        ReportMarkdownPath = if ($null -eq $reportMarkdown) { $null } else { $reportMarkdown.FullName }
        GeneratedAt = $report.generatedAt
        TotalCases = $report.totalCases
        TotalRuns = $report.totalRuns
        DatasetLocation = $report.environment.datasetLocation
        GenerationBaseUrl = $report.environment.generationBaseUrl
        GenerationModel = $report.environment.generationModel
        L1MaxTokens = $report.environment.l1MaxTokens
        EvalSystemPromptSha256 = $report.environment.evalSystemPromptSha256
        Modes = @($report.environment.modes)
        ModeSummaries = $report.modeSummaries
    }
}

function Get-ModeMetricValues {
    param(
        [System.Collections.IEnumerable]$Runs,
        [string]$ModeName,
        [string]$MetricName
    )
    $values = New-Object System.Collections.Generic.List[double]
    foreach ($run in $Runs) {
        if ($null -eq $run.ModeSummaries) {
            continue
        }
        $summary = $run.ModeSummaries.PSObject.Properties[$ModeName]
        if ($null -eq $summary) {
            continue
        }
        $metric = $summary.Value.PSObject.Properties[$MetricName]
        if ($null -eq $metric -or $null -eq $metric.Value) {
            continue
        }
        $values.Add([double]$metric.Value)
    }
    return @($values)
}

function Get-RunMarkdownRows {
    param([System.Collections.IEnumerable]$Runs)
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.AppendLine("| Round | Eval | Verify | Baseline | Memory | Recovered | RecoveredAccuracy | RecoveredL2HitRate | Report |")
    [void]$builder.AppendLine("| --- | --- | --- | --- | --- | --- | ---: | ---: | --- |")
    foreach ($run in $Runs) {
        $baseline = if ($run.ModeSummaries -and $run.ModeSummaries.PSObject.Properties["Baseline-NoMemory"]) {
            $summary = $run.ModeSummaries."Baseline-NoMemory"
            "{0}/{1}" -f $summary.correct, $summary.total
        } else {
            ""
        }
        $memory = if ($run.ModeSummaries -and $run.ModeSummaries.PSObject.Properties["Vortex-Memory"]) {
            $summary = $run.ModeSummaries."Vortex-Memory"
            "{0}/{1}" -f $summary.correct, $summary.total
        } else {
            ""
        }
        $recovered = if ($run.ModeSummaries -and $run.ModeSummaries.PSObject.Properties["Vortex-RecoveredMemory"]) {
            $summary = $run.ModeSummaries."Vortex-RecoveredMemory"
            "{0}/{1}" -f $summary.correct, $summary.total
        } else {
            ""
        }
        $recoveredAccuracy = if ($run.ModeSummaries -and $run.ModeSummaries.PSObject.Properties["Vortex-RecoveredMemory"]) {
            "{0:N4}" -f [double]$run.ModeSummaries."Vortex-RecoveredMemory".recoveredAccuracy
        } else {
            ""
        }
        $recoveredL2HitRate = if ($run.ModeSummaries -and $run.ModeSummaries.PSObject.Properties["Vortex-RecoveredMemory"]) {
            "{0:N4}" -f [double]$run.ModeSummaries."Vortex-RecoveredMemory".recoveredL2HitRate
        } else {
            ""
        }
        $reportName = if ($run.ReportJsonPath) { Split-Path $run.ReportJsonPath -Leaf } else { "" }
        $verifyLabel = if ($run.Verify.Passed) {
            "PASS"
        } elseif ($run.Verify.ExitCode -eq 2) {
            "DRIFT"
        } else {
            "ERROR"
        }
        [void]$builder.AppendLine("| $($run.RoundIndex) | $($run.Status) | $verifyLabel | $baseline | $memory | $recovered | $recoveredAccuracy | $recoveredL2HitRate | $reportName |")
    }
    return $builder.ToString()
}

function Get-DriftMarkdown {
    param([System.Collections.IEnumerable]$Runs)
    $builder = New-Object System.Text.StringBuilder
    foreach ($run in ($Runs | Where-Object { -not $_.Verify.Passed })) {
        [void]$builder.AppendLine("## Round $($run.RoundIndex)")
        [void]$builder.AppendLine()
        [void]$builder.AppendLine('```text')
        [void]$builder.AppendLine($run.Verify.Output)
        [void]$builder.AppendLine('```')
        [void]$builder.AppendLine()
    }
    return $builder.ToString()
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $repoRoot

if ([string]::IsNullOrWhiteSpace($AuditStamp)) {
    $AuditStamp = "baseline-audit-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}

$normalizedReportRoot = $ReportRoot.TrimEnd('/').TrimEnd([char]92)
$auditDir = Join-Path $repoRoot ($normalizedReportRoot + "/" + $AuditStamp)
$runsRootRelative = ($normalizedReportRoot + "/" + $AuditStamp + "/runs")
$singleRunScript = Join-Path $repoRoot "ops/run-real-llm-memory-eval.ps1"
$evalCliJar = Join-Path $repoRoot "vortex-app/target/vortex-app-0.1.0-SNAPSHOT-eval-cli.jar"
$auditJsonPath = Join-Path $auditDir "baseline-audit-summary.json"
$auditMarkdownPath = Join-Path $auditDir "baseline-audit-summary.md"
$datasetCaseMap = Get-DatasetCaseMap -Location $DatasetLocation -RepoRoot $repoRoot

Assert-PathExists -Path $singleRunScript -Label "Single-run eval script"

New-Item -ItemType Directory -Force -Path $auditDir | Out-Null

Write-Host "Starting LLM memory baseline audit"
Write-Host "  Audit Stamp : $AuditStamp"
Write-Host "  Rounds      : $Rounds"
Write-Host "  Dataset     : $DatasetLocation"
Write-Host "  Report Dir  : $auditDir"

if (-not $SkipComposeUp) {
    docker compose up -d --wait
    Assert-LastExitCodeZero -Label "docker compose up"
}
if (-not $SkipPackage) {
    mvn -pl vortex-app -am -DskipTests package
    Assert-LastExitCodeZero -Label "mvn package"
}
Assert-PathExists -Path $evalCliJar -Label "eval CLI jar"

$startedAt = Get-Date
$runResults = New-Object System.Collections.Generic.List[object]

for ($round = 1; $round -le $Rounds; $round++) {
    $roundStamp = New-RoundStamp -Prefix $AuditStamp -RoundIndex $round
    $roundReportDir = Join-Path $repoRoot ($runsRootRelative + "/" + $roundStamp)
    $roundStartedAt = Get-Date
    Write-Host ""
    Write-Host ("[{0}/{1}] Processing baseline round {2}" -f $round, $Rounds, $roundStamp)

    try {
        $singleRun = $null
        if (-not $ForceRerunExisting) {
            $singleRun = Import-ExistingRun -ReportDir $roundReportDir -RoundStamp $roundStamp
            if ($null -ne $singleRun) {
                Write-Host ("  Reusing existing report: {0}" -f $singleRun.ReportJsonPath)
            }
        }

        if ($null -eq $singleRun) {
            Write-Host ("  Running real eval for: {0}" -f $roundStamp)
            $singleRun = & $singleRunScript `
                -ApiKey $ApiKey `
                -BaseUrl $BaseUrl `
                -Model $Model `
                -Stamp $roundStamp `
                -DatasetLocation $DatasetLocation `
                -BgeModelPath $BgeModelPath `
                -L1MaxTokens $L1MaxTokens `
                -Modes $Modes `
                -ReportRoot $runsRootRelative `
                -SkipComposeUp `
                -SkipPackage
        }

        $verifyInvocation = Invoke-ProcessCapture -FilePath "java" -ArgumentList @(
            "-jar",
            $evalCliJar,
            "verify",
            $singleRun.ReportJsonPath
        )
        $verify = New-VerifyResult -ExitCode $verifyInvocation.ExitCode -Output $verifyInvocation.Output

        $durationSeconds = [Math]::Round(((Get-Date) - $roundStartedAt).TotalSeconds, 3)
        $runResults.Add([pscustomobject]@{
            RoundIndex = $round
            RoundStamp = $roundStamp
            Status = "completed"
            DurationSeconds = $durationSeconds
            ReportDir = $singleRun.ReportDir
            ReportJsonPath = $singleRun.ReportJsonPath
            ReportMarkdownPath = $singleRun.ReportMarkdownPath
            GeneratedAt = $singleRun.GeneratedAt
            TotalCases = $singleRun.TotalCases
            TotalRuns = $singleRun.TotalRuns
            DatasetLocation = $singleRun.DatasetLocation
            GenerationBaseUrl = $singleRun.GenerationBaseUrl
            GenerationModel = $singleRun.GenerationModel
            L1MaxTokens = $singleRun.L1MaxTokens
            EvalSystemPromptSha256 = $singleRun.EvalSystemPromptSha256
            Modes = @($singleRun.Modes)
            ModeSummaries = ConvertTo-PlainObject $singleRun.ModeSummaries
            Verify = $verify
            FailureMessage = $null
        }) | Out-Null
    } catch {
        $durationSeconds = [Math]::Round(((Get-Date) - $roundStartedAt).TotalSeconds, 3)
        $failureMessage = $_.Exception.Message
        $runResults.Add([pscustomobject]@{
            RoundIndex = $round
            RoundStamp = $roundStamp
            Status = "failed"
            DurationSeconds = $durationSeconds
            ReportDir = $null
            ReportJsonPath = $null
            ReportMarkdownPath = $null
            GeneratedAt = $null
            TotalCases = $null
            TotalRuns = $null
            DatasetLocation = $DatasetLocation
            GenerationBaseUrl = $BaseUrl
            GenerationModel = $Model
            L1MaxTokens = $L1MaxTokens
            EvalSystemPromptSha256 = $null
            Modes = @()
            ModeSummaries = $null
            Verify = [pscustomobject]@{
                Passed = $false
                ExitCode = 1
                Output = $failureMessage
            }
            FailureMessage = $failureMessage
        }) | Out-Null
        Write-Warning ("Round {0} failed: {1}" -f $round, $failureMessage)
    }
}

try {
    $completedAt = Get-Date
    $completedRuns = $runResults.ToArray()
    $verifierPassCount = @($completedRuns | Where-Object { $_.Verify.Passed }).Count
    $evalSuccessCount = @($completedRuns | Where-Object { $_.Status -eq "completed" }).Count
    $strictVerifierPassed = ($completedRuns.Count -eq $Rounds) -and ($verifierPassCount -eq $Rounds)

    $baselineCorrectValues = Get-ModeMetricValues -Runs $completedRuns -ModeName "Baseline-NoMemory" -MetricName "correct"
    $memoryAccuracyValues = Get-ModeMetricValues -Runs $completedRuns -ModeName "Vortex-Memory" -MetricName "accuracy"
    $recoveredAccuracyValues = Get-ModeMetricValues -Runs $completedRuns -ModeName "Vortex-RecoveredMemory" -MetricName "recoveredAccuracy"
    $recoveredL2HitRateValues = Get-ModeMetricValues -Runs $completedRuns -ModeName "Vortex-RecoveredMemory" -MetricName "recoveredL2HitRate"
    $caseFailureDetails = Get-CaseFailureDetails -Runs $completedRuns -DatasetCases $datasetCaseMap
    $caseFailureSummary = Get-CaseFailureSummary -Failures $caseFailureDetails -RoundCount $Rounds
    $auditGate = New-AuditGateResult `
        -Runs $completedRuns `
        -RequestedRounds $Rounds `
        -EvalSuccessCount $evalSuccessCount `
        -BaselineCorrectValues $baselineCorrectValues `
        -MemoryAccuracyValues $memoryAccuracyValues `
        -RecoveredAccuracyValues $recoveredAccuracyValues `
        -RecoveredL2HitRateValues $recoveredL2HitRateValues `
        -BaselineNoMemoryMaxCorrect $BaselineNoMemoryMaxCorrect `
        -MinVortexMemoryMeanAccuracy $MinVortexMemoryMeanAccuracy `
        -MinRecoveredMeanAccuracy $MinRecoveredMeanAccuracy `
        -MinRecoveredL2MeanHitRate $MinRecoveredL2MeanHitRate
    $overallPassed = $auditGate.Passed

    Write-Host "Building audit summary object"
    $summary = [pscustomobject]@{
        AuditStamp = $AuditStamp
        GeneratedAt = (Get-Date).ToUniversalTime().ToString("o")
        BaselineId = "20260529-real-bge-v2-006"
        OverallPassed = $overallPassed
        StrictVerifierPassed = $strictVerifierPassed
        Settings = [pscustomobject]@{
            BaseUrl = $BaseUrl
            Model = $Model
            DatasetLocation = $DatasetLocation
            BgeModelPath = $BgeModelPath
            L1MaxTokens = $L1MaxTokens
            Modes = $Modes
            RequestedRounds = $Rounds
            ReportRoot = $ReportRoot
            BaselineNoMemoryMaxCorrect = $BaselineNoMemoryMaxCorrect
            MinVortexMemoryMeanAccuracy = $MinVortexMemoryMeanAccuracy
            MinRecoveredMeanAccuracy = $MinRecoveredMeanAccuracy
            MinRecoveredL2MeanHitRate = $MinRecoveredL2MeanHitRate
        }
        Aggregate = [pscustomobject]@{
            StartedAt = $startedAt.ToUniversalTime().ToString("o")
            CompletedAt = $completedAt.ToUniversalTime().ToString("o")
            TotalDurationSeconds = [Math]::Round(($completedAt - $startedAt).TotalSeconds, 3)
            EvalSuccessCount = $evalSuccessCount
            EvalFailureCount = $Rounds - $evalSuccessCount
            VerifierPassCount = $verifierPassCount
            VerifierFailCount = $Rounds - $verifierPassCount
            BaselineNoMemoryCorrectValues = $baselineCorrectValues
            VortexMemoryAccuracyValues = $memoryAccuracyValues
            RecoveredAccuracyValues = $recoveredAccuracyValues
            RecoveredL2HitRateValues = $recoveredL2HitRateValues
            CaseFailureCount = @($caseFailureDetails).Count
            CaseFailureGroupCount = @($caseFailureSummary).Count
        }
        AuditGate = $auditGate
        CaseFailureSummary = $caseFailureSummary
        CaseFailureDetails = $caseFailureDetails
        Runs = $completedRuns
    }

    Write-Host "Writing audit summary json"
    $summary | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $auditJsonPath -Encoding UTF8

    Write-Host "Building audit summary markdown"
    $markdown = @(
        "# LLM Memory Baseline Audit"
        ""
        "- Audit Stamp: $AuditStamp"
        "- GeneratedAt: $($summary.GeneratedAt)"
        "- Baseline Id: $($summary.BaselineId)"
        "- Overall Passed: $($summary.OverallPassed)"
        "- Audit Gate Passed: $($summary.AuditGate.Passed)"
        "- Strict Verifier Passed: $($summary.StrictVerifierPassed)"
        "- Requested Rounds: $Rounds"
        "- Eval Success Count: $evalSuccessCount"
        "- Verifier Pass Count: $verifierPassCount"
        "- Dataset Location: $DatasetLocation"
        "- Base URL: $BaseUrl"
        "- Model: $Model"
        "- L1 Max Tokens: $L1MaxTokens"
        "- Total Duration Seconds: $($summary.Aggregate.TotalDurationSeconds)"
        ""
        "## Aggregate"
        ""
        "- Baseline-NoMemory correct values: $(Format-MetricSequence -Values $baselineCorrectValues)"
        "- Vortex-Memory accuracy values: $(Format-MetricSequence -Values $memoryAccuracyValues)"
        "- RecoveredAccuracy values: $(Format-MetricSequence -Values $recoveredAccuracyValues)"
        "- RecoveredL2HitRate values: $(Format-MetricSequence -Values $recoveredL2HitRateValues)"
        "- Case failure count: $(@($caseFailureDetails).Count)"
        "- Case failure groups: $(@($caseFailureSummary).Count)"
        ""
        "## Audit Gate"
        ""
        (Get-AuditGateMarkdown -AuditGate $auditGate)
        ""
        "## Case Failure Summary"
        ""
        (Get-CaseFailureSummaryMarkdown -CaseFailureSummary $caseFailureSummary)
        ""
        "## Case Failure Details"
        ""
        (Get-CaseFailureDetailsMarkdown -CaseFailureDetails $caseFailureDetails)
        ""
        "## Runs"
        ""
        (Get-RunMarkdownRows -Runs $completedRuns)
    ) -join [Environment]::NewLine

    $driftMarkdown = Get-DriftMarkdown -Runs $completedRuns
    if (-not [string]::IsNullOrWhiteSpace($driftMarkdown)) {
        $markdown += [Environment]::NewLine + [Environment]::NewLine + "## Verification Drift" +
            [Environment]::NewLine + [Environment]::NewLine + $driftMarkdown.TrimEnd()
    }

    Write-Host "Writing audit summary markdown"
    Set-Content -LiteralPath $auditMarkdownPath -Value $markdown -Encoding UTF8
} catch {
    Write-Host ("Audit summary stage failed at line {0}: {1}" -f $_.InvocationInfo.ScriptLineNumber, $_.InvocationInfo.Line.Trim())
    throw
}

Write-Host ""
Write-Host "Completed LLM memory baseline audit"
Write-Host "  Summary JSON : $auditJsonPath"
Write-Host "  Summary MD   : $auditMarkdownPath"
Write-Host "  Overall Pass : $overallPassed"
Write-Host "  Audit Gate   : $($auditGate.Passed)"
Write-Host "  Verify Passes: $verifierPassCount/$Rounds"

if ($FailOnAuditGateFailure -and -not $auditGate.Passed) {
    exit 2
}

[pscustomobject]@{
    AuditStamp = $AuditStamp
    SummaryJsonPath = $auditJsonPath
    SummaryMarkdownPath = $auditMarkdownPath
    OverallPassed = $overallPassed
    AuditGatePassed = $auditGate.Passed
    StrictVerifierPassed = $strictVerifierPassed
    VerifierPassCount = $verifierPassCount
    RequestedRounds = $Rounds
}
