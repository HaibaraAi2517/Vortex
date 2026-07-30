param(
    [Parameter(Mandatory = $true)]
    [string]$ReportPath,

    [Parameter(Mandatory = $true)]
    [string]$DatasetPath,

    [Parameter(Mandatory = $true)]
    [string]$OutputDirectory,

    [int]$BootstrapIterations = 20000,

    [int]$RandomSeed = 20260728,

    [int]$KeywordCandidatePoolLimit = 1024,

    [string[]]$ExpectedMode = @(),

    [string[]]$ModeComparison = @(),

    [switch]$RequireCaseIsolatedReturns
)

$ErrorActionPreference = "Stop"

function Get-Mean {
    param([double[]]$Values)
    if ($null -eq $Values -or $Values.Count -eq 0) {
        return 0.0
    }
    return ($Values | Measure-Object -Average).Average
}

function Get-Percentile {
    param(
        [double[]]$SortedValues,
        [double]$Percentile
    )
    if ($null -eq $SortedValues -or $SortedValues.Count -eq 0) {
        return 0.0
    }
    $position = [Math]::Max(0.0, [Math]::Min(1.0, $Percentile)) * ($SortedValues.Count - 1)
    $lower = [Math]::Floor($position)
    $upper = [Math]::Ceiling($position)
    if ($lower -eq $upper) {
        return $SortedValues[$lower]
    }
    $fraction = $position - $lower
    return $SortedValues[$lower] + ($SortedValues[$upper] - $SortedValues[$lower]) * $fraction
}

function Get-BootstrapInterval {
    param(
        [double[]]$Values,
        [int]$Iterations,
        [int]$Seed
    )
    if ($null -eq $Values -or $Values.Count -eq 0 -or $Iterations -le 0) {
        return [pscustomobject]@{ lower = 0.0; upper = 0.0 }
    }
    $random = [System.Random]::new($Seed)
    $means = [double[]]::new($Iterations)
    for ($iteration = 0; $iteration -lt $Iterations; $iteration++) {
        $sum = 0.0
        for ($sample = 0; $sample -lt $Values.Count; $sample++) {
            $sum += $Values[$random.Next($Values.Count)]
        }
        $means[$iteration] = $sum / $Values.Count
    }
    [System.Array]::Sort($means)
    return [pscustomobject]@{
        lower = Get-Percentile -SortedValues $means -Percentile 0.025
        upper = Get-Percentile -SortedValues $means -Percentile 0.975
    }
}

function Format-Decimal {
    param([double]$Value)
    return $Value.ToString("0.0000", [System.Globalization.CultureInfo]::InvariantCulture)
}

function Format-Milliseconds {
    param([double]$Value)
    return $Value.ToString("0.0", [System.Globalization.CultureInfo]::InvariantCulture)
}

function Get-RerankEffectStatus {
    param($Result)
    if ($null -eq $Result -or $null -eq $Result.recallDiagnostics) {
        return "UNAVAILABLE"
    }
    $property = $Result.recallDiagnostics.PSObject.Properties["rerankEffectStatus"]
    if ($null -eq $property -or [string]::IsNullOrWhiteSpace([string]$property.Value)) {
        return "UNAVAILABLE"
    }
    return [string]$property.Value
}

function Get-DiagnosticCount {
    param(
        $Result,
        [string]$PropertyName
    )
    if ($null -eq $Result -or $null -eq $Result.recallDiagnostics) {
        return 0
    }
    $property = $Result.recallDiagnostics.PSObject.Properties[$PropertyName]
    if ($null -eq $property -or $null -eq $property.Value) {
        return 0
    }
    return [int]$property.Value
}

function Get-DiagnosticString {
    param(
        $Result,
        [string]$PropertyName
    )
    if ($null -eq $Result -or $null -eq $Result.recallDiagnostics) {
        return ""
    }
    $property = $Result.recallDiagnostics.PSObject.Properties[$PropertyName]
    if ($null -eq $property -or $null -eq $property.Value) {
        return ""
    }
    return [string]$property.Value
}

function Get-DiagnosticNumber {
    param(
        $Result,
        [string]$PropertyName
    )
    if ($null -eq $Result -or $null -eq $Result.recallDiagnostics) {
        return 0.0
    }
    $property = $Result.recallDiagnostics.PSObject.Properties[$PropertyName]
    if ($null -eq $property -or $null -eq $property.Value) {
        return 0.0
    }
    return [double]$property.Value
}

$reportFullPath = (Resolve-Path -LiteralPath $ReportPath).Path
$datasetFullPath = (Resolve-Path -LiteralPath $DatasetPath).Path
$reportSha256 = (Get-FileHash -LiteralPath $reportFullPath -Algorithm SHA256).Hash.ToLowerInvariant()
$datasetSha256 = (Get-FileHash -LiteralPath $datasetFullPath -Algorithm SHA256).Hash.ToLowerInvariant()
$report = Get-Content -LiteralPath $reportFullPath -Raw | ConvertFrom-Json
$dataset = Get-Content -LiteralPath $datasetFullPath -Raw | ConvertFrom-Json

$categoryByCaseId = @{}
$fragmentIdsByCaseId = @{}
foreach ($case in @($dataset)) {
    $caseId = [string]$case.caseId
    if ($categoryByCaseId.ContainsKey($caseId)) {
        throw "Duplicate dataset caseId: $($case.caseId)"
    }
    $category = @($case.failureCategories) | Select-Object -First 1
    $categoryByCaseId[$caseId] = [string]$category
    $fragmentIds = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal)
    foreach ($fragment in @($case.memoryFragments)) {
        if ($null -ne $fragment -and -not [string]::IsNullOrWhiteSpace([string]$fragment.fragmentId)) {
            [void]$fragmentIds.Add([string]$fragment.fragmentId)
        }
    }
    $fragmentIdsByCaseId[$caseId] = $fragmentIds
}

$modeResults = @{}
foreach ($result in @($report.results)) {
    $mode = [string]$result.mode
    if (-not $modeResults.ContainsKey($mode)) {
        $modeResults[$mode] = @{}
    }
    $caseId = [string]$result.caseId
    if ($modeResults[$mode].ContainsKey($caseId)) {
        throw "Duplicate report result mode=$mode caseId=$caseId"
    }
    if (-not $categoryByCaseId.ContainsKey($caseId)) {
        throw "Report caseId is missing from dataset: $caseId"
    }
    $modeResults[$mode][$caseId] = $result
}

$expectedModes = if ($ExpectedMode.Count -gt 0) {
    @($ExpectedMode | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Select-Object -Unique)
} elseif ($null -ne $report.modes -and @($report.modes).Count -gt 0) {
    @($report.modes | ForEach-Object { [string]$_ } | Select-Object -Unique)
} else {
    @($modeResults.Keys | Sort-Object)
}
if ($expectedModes.Count -eq 0) {
    throw "No expected modes were provided or discovered in the report."
}
foreach ($mode in $expectedModes) {
    if (-not $modeResults.ContainsKey($mode)) {
        throw "Report is missing mode: $mode"
    }
    if ($modeResults[$mode].Count -ne $dataset.Count) {
        throw "Mode '$mode' has $($modeResults[$mode].Count) results; expected $($dataset.Count)."
    }
}

$errors = @($report.results | Where-Object {
    -not [string]::IsNullOrWhiteSpace([string]$_.errorMessage)
})
if ($errors.Count -gt 0) {
    throw "Report contains $($errors.Count) failed runs."
}

$foreignReturnedFragments = New-Object System.Collections.Generic.List[object]
foreach ($result in @($report.results)) {
    $caseId = [string]$result.caseId
    foreach ($fragmentId in @($result.returnedFragmentIds)) {
        if (-not $fragmentIdsByCaseId[$caseId].Contains([string]$fragmentId)) {
            $foreignReturnedFragments.Add([pscustomobject][ordered]@{
                caseId = $caseId
                mode = [string]$result.mode
                fragmentId = [string]$fragmentId
            })
        }
    }
}
if ($RequireCaseIsolatedReturns.IsPresent -and $foreignReturnedFragments.Count -gt 0) {
    $firstForeign = $foreignReturnedFragments[0]
    throw "Report contains $($foreignReturnedFragments.Count) cross-case returned fragments; first mode=$($firstForeign.mode) caseId=$($firstForeign.caseId) fragmentId=$($firstForeign.fragmentId)"
}

$modeSummaries = New-Object System.Collections.Generic.List[object]
foreach ($mode in $expectedModes) {
    $results = @($modeResults[$mode].Values)
    $latencies = [double[]]@($results |
        ForEach-Object { [double]$_.latencyMs } |
        Sort-Object)
    $modeSummaries.Add([pscustomobject][ordered]@{
        mode = $mode
        cases = $results.Count
        recallAt5 = Get-Mean -Values @($results | ForEach-Object { [double]$_.recallAtK })
        caseHitRate = Get-Mean -Values @($results | ForEach-Object { [double][bool]$_.recallHit })
        allExpectedRate = Get-Mean -Values @($results | ForEach-Object { [double][bool]$_.allExpectedReturned })
        mrr = Get-Mean -Values @($results | ForEach-Object { [double]$_.reciprocalRank })
        ndcg = Get-Mean -Values @($results | ForEach-Object { [double]$_.ndcg })
        averageLatencyMs = Get-Mean -Values @($results | ForEach-Object { [double]$_.latencyMs })
        latencyP50Ms = Get-Percentile -SortedValues $latencies -Percentile 0.50
        latencyP95Ms = Get-Percentile -SortedValues $latencies -Percentile 0.95
        latencyP99Ms = Get-Percentile -SortedValues $latencies -Percentile 0.99
    })
}

$rerankDiagnosticsSummaries = New-Object System.Collections.Generic.List[object]
foreach ($mode in $expectedModes) {
    $results = @($modeResults[$mode].Values)
    $statuses = @($results | ForEach-Object { Get-RerankEffectStatus -Result $_ })
    $orderChangedCases = @($statuses | Where-Object { $_ -eq "ORDER_CHANGED" }).Count
    $rerankLatenciesMs = [double[]]@($results |
        ForEach-Object {
            (Get-DiagnosticNumber -Result $_ -PropertyName "rerankLatencyNanos") / 1000000.0
        } |
        Sort-Object)
    $rerankDiagnosticsSummaries.Add([pscustomobject][ordered]@{
        mode = $mode
        cases = $results.Count
        rerankerTypes = @($results |
            ForEach-Object { Get-DiagnosticString -Result $_ -PropertyName "rerankerType" } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Sort-Object -Unique)
        models = @($results |
            ForEach-Object { Get-DiagnosticString -Result $_ -PropertyName "rerankModel" } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Sort-Object -Unique)
        modelVersions = @($results |
            ForEach-Object { Get-DiagnosticString -Result $_ -PropertyName "rerankModelVersion" } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Sort-Object -Unique)
        modelSha256 = @($results |
            ForEach-Object { Get-DiagnosticString -Result $_ -PropertyName "rerankModelSha256" } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Sort-Object -Unique)
        candidatePoolStrategies = @($results |
            ForEach-Object { Get-DiagnosticString -Result $_ -PropertyName "rerankCandidatePoolStrategy" } |
            Where-Object { -not [string]::IsNullOrWhiteSpace($_) } |
            Sort-Object -Unique)
        candidatePoolLimits = @($results |
            ForEach-Object { Get-DiagnosticCount -Result $_ -PropertyName "rerankCandidatePoolLimit" } |
            Where-Object { $_ -gt 0 } |
            Sort-Object -Unique)
        notExecutedCases = @($statuses | Where-Object { $_ -eq "NOT_EXECUTED" }).Count
        nonIdentifiableCases = @($statuses | Where-Object { $_ -eq "NON_IDENTIFIABLE" }).Count
        identifiableCases = @($statuses | Where-Object { $_ -eq "IDENTIFIABLE" }).Count
        orderChangedCases = $orderChangedCases
        changedOrderRate = if ($results.Count -eq 0) { 0.0 } else { $orderChangedCases / [double]$results.Count }
        unavailableCases = @($statuses | Where-Object { $_ -eq "UNAVAILABLE" }).Count
        preselectionCandidateCount = ($results | ForEach-Object {
            Get-DiagnosticCount -Result $_ -PropertyName "rerankPreselectionCandidateCount"
        } | Measure-Object -Sum).Sum
        inputCandidateCount = ($results | ForEach-Object {
            Get-DiagnosticCount -Result $_ -PropertyName "rerankInputCandidateCount"
        } | Measure-Object -Sum).Sum
        outputCandidateCount = ($results | ForEach-Object {
            Get-DiagnosticCount -Result $_ -PropertyName "rerankOutputCandidateCount"
        } | Measure-Object -Sum).Sum
        changedPositionCount = ($results | ForEach-Object {
            Get-DiagnosticCount -Result $_ -PropertyName "rerankChangedPositionCount"
        } | Measure-Object -Sum).Sum
        topKMembershipChangedCount = ($results | ForEach-Object {
            Get-DiagnosticCount -Result $_ -PropertyName "rerankTopKMembershipChangedCount"
        } | Measure-Object -Sum).Sum
        scoreDistinctCount = ($results | ForEach-Object {
            Get-DiagnosticCount -Result $_ -PropertyName "rerankScoreDistinctCount"
        } | Measure-Object -Sum).Sum
        averageRerankLatencyMs = Get-Mean -Values $rerankLatenciesMs
        rerankLatencyP95Ms = Get-Percentile -SortedValues $rerankLatenciesMs -Percentile 0.95
        rerankLatencyP99Ms = Get-Percentile -SortedValues $rerankLatenciesMs -Percentile 0.99
    })
}

$categorySummaries = New-Object System.Collections.Generic.List[object]
$categories = @($categoryByCaseId.Values | Sort-Object -Unique)
foreach ($category in $categories) {
    $caseIds = @($categoryByCaseId.GetEnumerator() |
        Where-Object { $_.Value -eq $category } |
        ForEach-Object { $_.Key } |
        Sort-Object)
    foreach ($mode in $expectedModes) {
        $results = @($caseIds | ForEach-Object { $modeResults[$mode][$_] })
        $categorySummaries.Add([pscustomobject][ordered]@{
            category = $category
            mode = $mode
            cases = $results.Count
            recallAt5 = Get-Mean -Values @($results | ForEach-Object { [double]$_.recallAtK })
            caseHitRate = Get-Mean -Values @($results | ForEach-Object { [double][bool]$_.recallHit })
            ndcg = Get-Mean -Values @($results | ForEach-Object { [double]$_.ndcg })
            averageLatencyMs = Get-Mean -Values @($results | ForEach-Object { [double]$_.latencyMs })
        })
    }
}

function Get-RerankEffectAssessment {
    param(
        [string]$LeftMode,
        [string]$RightMode
    )
    $isDirectRerankComparison =
        ($LeftMode -eq "Vector+Rerank" -and $RightMode -eq "VectorOnly") -or
        ($LeftMode -eq "Hybrid+Rerank" -and $RightMode -eq "Hybrid") -or
        ($LeftMode -eq "Vector+CrossEncoderReranker" -and $RightMode -eq "VectorOnly") -or
        ($LeftMode -eq "Hybrid+CrossEncoderReranker" -and $RightMode -eq "Hybrid")
    if (-not $isDirectRerankComparison) {
        return [pscustomobject][ordered]@{
            status = "NOT_APPLICABLE"
            conclusionPermitted = $false
            reason = "Modes do not differ only by the rerank switch."
        }
    }

    $statuses = @($modeResults[$LeftMode].Values | ForEach-Object {
        Get-RerankEffectStatus -Result $_
    })
    if (@($statuses | Where-Object { $_ -eq "ORDER_CHANGED" }).Count -gt 0) {
        return [pscustomobject][ordered]@{
            status = "ORDER_CHANGED"
            conclusionPermitted = $true
            reason = "Rerank changed candidate order for at least one paired case."
        }
    }
    if (@($statuses | Where-Object { $_ -eq "IDENTIFIABLE" }).Count -gt 0) {
        return [pscustomobject][ordered]@{
            status = "IDENTIFIABLE"
            conclusionPermitted = $true
            reason = "At least one paired case contained an independent varying rerank signal."
        }
    }
    if (@($statuses | Where-Object { $_ -eq "NON_IDENTIFIABLE" }).Count -gt 0) {
        return [pscustomobject][ordered]@{
            status = "NON_IDENTIFIABLE"
            conclusionPermitted = $false
            reason = "Rerank executed without an independent varying signal; unchanged metrics cannot support an effectiveness conclusion."
        }
    }
    if (@($statuses | Where-Object { $_ -eq "NOT_EXECUTED" }).Count -gt 0) {
        return [pscustomobject][ordered]@{
            status = "NOT_EXECUTED"
            conclusionPermitted = $false
            reason = "Rerank did not execute for the paired cases."
        }
    }
    return [pscustomobject][ordered]@{
        status = "UNAVAILABLE"
        conclusionPermitted = $false
        reason = "The source report does not contain rerank identifiability diagnostics."
    }
}

function Compare-Modes {
    param(
        [string]$LeftMode,
        [string]$RightMode,
        [int]$SeedOffset
    )
    $pairedRows = New-Object System.Collections.Generic.List[object]
    foreach ($caseId in @($categoryByCaseId.Keys | Sort-Object)) {
        $left = $modeResults[$LeftMode][$caseId]
        $right = $modeResults[$RightMode][$caseId]
        $pairedRows.Add([pscustomobject][ordered]@{
            caseId = $caseId
            category = $categoryByCaseId[$caseId]
            recallDelta = [double]$left.recallAtK - [double]$right.recallAtK
            caseHitDelta = [double][bool]$left.recallHit - [double][bool]$right.recallHit
            mrrDelta = [double]$left.reciprocalRank - [double]$right.reciprocalRank
            ndcgDelta = [double]$left.ndcg - [double]$right.ndcg
            latencyDeltaMs = [double]$left.latencyMs - [double]$right.latencyMs
        })
    }
    $recallDeltas = [double[]]@($pairedRows | ForEach-Object { $_.recallDelta })
    $mrrDeltas = [double[]]@($pairedRows | ForEach-Object { $_.mrrDelta })
    $ndcgDeltas = [double[]]@($pairedRows | ForEach-Object { $_.ndcgDelta })
    $recallInterval = Get-BootstrapInterval -Values $recallDeltas -Iterations $BootstrapIterations -Seed ($RandomSeed + $SeedOffset)
    $ndcgInterval = Get-BootstrapInterval -Values $ndcgDeltas -Iterations $BootstrapIterations -Seed ($RandomSeed + 100 + $SeedOffset)
    $leftLatency = ($modeSummaries | Where-Object mode -eq $LeftMode).averageLatencyMs
    $rightLatency = ($modeSummaries | Where-Object mode -eq $RightMode).averageLatencyMs
    $leftLatencyP95 = ($modeSummaries | Where-Object mode -eq $LeftMode).latencyP95Ms
    $rightLatencyP95 = ($modeSummaries | Where-Object mode -eq $RightMode).latencyP95Ms
    $leftLatencyP99 = ($modeSummaries | Where-Object mode -eq $LeftMode).latencyP99Ms
    $rightLatencyP99 = ($modeSummaries | Where-Object mode -eq $RightMode).latencyP99Ms
    $rerankAssessment = Get-RerankEffectAssessment -LeftMode $LeftMode -RightMode $RightMode
    return [pscustomobject][ordered]@{
        comparison = "$LeftMode vs $RightMode"
        leftMode = $LeftMode
        rightMode = $RightMode
        cases = $pairedRows.Count
        recallDelta = Get-Mean -Values $recallDeltas
        recallCi95Lower = $recallInterval.lower
        recallCi95Upper = $recallInterval.upper
        recallWins = @($pairedRows | Where-Object recallDelta -gt 0.0).Count
        recallTies = @($pairedRows | Where-Object recallDelta -eq 0.0).Count
        recallLosses = @($pairedRows | Where-Object recallDelta -lt 0.0).Count
        caseHitRateDelta = Get-Mean -Values @($pairedRows | ForEach-Object { [double]$_.caseHitDelta })
        mrrDelta = Get-Mean -Values $mrrDeltas
        ndcgDelta = Get-Mean -Values $ndcgDeltas
        ndcgCi95Lower = $ndcgInterval.lower
        ndcgCi95Upper = $ndcgInterval.upper
        averageLatencyDeltaMs = Get-Mean -Values @($pairedRows | ForEach-Object { [double]$_.latencyDeltaMs })
        latencyP95DeltaMs = $leftLatencyP95 - $rightLatencyP95
        latencyP99DeltaMs = $leftLatencyP99 - $rightLatencyP99
        averageLatencyRatio = if ($rightLatency -eq 0.0) { 0.0 } else { $leftLatency / $rightLatency }
        rerankEffectAssessment = $rerankAssessment.status
        rerankEffectConclusionPermitted = $rerankAssessment.conclusionPermitted
        rerankEffectAssessmentReason = $rerankAssessment.reason
        changedCases = @($pairedRows |
            Where-Object recallDelta -ne 0.0 |
            Sort-Object @{ Expression = { [Math]::Abs($_.recallDelta) }; Descending = $true }, caseId)
    }
}

$comparisonSpecs = New-Object System.Collections.Generic.List[object]
if ($ModeComparison.Count -gt 0) {
    foreach ($rawComparison in $ModeComparison) {
        $parts = @($rawComparison -split "::", 2)
        if ($parts.Count -ne 2 -or
            [string]::IsNullOrWhiteSpace($parts[0]) -or
            [string]::IsNullOrWhiteSpace($parts[1])) {
            throw "ModeComparison '$rawComparison' must use the format LeftMode::RightMode."
        }
        if (-not $modeResults.ContainsKey($parts[0]) -or -not $modeResults.ContainsKey($parts[1])) {
            throw "ModeComparison '$rawComparison' references a mode that is missing from the report."
        }
        $comparisonSpecs.Add([pscustomobject]@{ left = $parts[0]; right = $parts[1] })
    }
} else {
    $defaultComparisonSpecs = @(
        [pscustomobject]@{ left = "Hybrid+Rerank"; right = "Hybrid" },
        [pscustomobject]@{ left = "Hybrid+Rerank"; right = "KeywordOnly" },
        [pscustomobject]@{ left = "Hybrid+Rerank"; right = "Vector+Rerank" },
        [pscustomobject]@{ left = "Hybrid"; right = "KeywordOnly" },
        [pscustomobject]@{ left = "Vector+Rerank"; right = "VectorOnly" },
        [pscustomobject]@{ left = "KeywordOnly"; right = "VectorOnly" },
        [pscustomobject]@{ left = "Vector+CrossEncoderReranker"; right = "VectorOnly" },
        [pscustomobject]@{ left = "Vector+CrossEncoderReranker"; right = "Vector+Rerank" },
        [pscustomobject]@{ left = "Hybrid+CrossEncoderReranker"; right = "Hybrid" },
        [pscustomobject]@{ left = "Hybrid+CrossEncoderReranker"; right = "Hybrid+Rerank" }
    )
    foreach ($spec in $defaultComparisonSpecs) {
        if ($modeResults.ContainsKey($spec.left) -and $modeResults.ContainsKey($spec.right)) {
            $comparisonSpecs.Add($spec)
        }
    }
}
$comparisons = New-Object System.Collections.Generic.List[object]
for ($index = 0; $index -lt $comparisonSpecs.Count; $index++) {
    $spec = $comparisonSpecs[$index]
    $comparisons.Add((Compare-Modes -LeftMode $spec.left -RightMode $spec.right -SeedOffset ($index + 1)))
}

$analysis = [pscustomobject][ordered]@{
    generatedAt = [DateTimeOffset]::UtcNow
    sourceReport = $reportFullPath
    sourceReportSha256 = $reportSha256
    sourceDataset = $datasetFullPath
    sourceDatasetSha256 = $datasetSha256
    sourceRunId = $report.runId
    cases = $dataset.Count
    runs = $report.results.Count
    errors = $errors.Count
    topK = $report.topK
    tokenBudget = $report.tokenBudget
    keywordCandidatePoolLimit = $KeywordCandidatePoolLimit
    requireCaseIsolatedReturns = $RequireCaseIsolatedReturns.IsPresent
    foreignReturnedFragmentCount = $foreignReturnedFragments.Count
    bootstrapIterations = $BootstrapIterations
    randomSeed = $RandomSeed
    environmentSnapshot = $report.environmentSnapshot
    modeSummaries = $modeSummaries
    rerankDiagnosticsSummaries = $rerankDiagnosticsSummaries
    categorySummaries = $categorySummaries
    comparisons = $comparisons
}

$outputFullPath = [System.IO.Path]::GetFullPath($OutputDirectory)
[System.IO.Directory]::CreateDirectory($outputFullPath) | Out-Null
$jsonPath = Join-Path $outputFullPath "recall-decision-analysis.json"
$markdownPath = Join-Path $outputFullPath "recall-decision-analysis.md"
$analysis | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonPath -Encoding UTF8

$builder = [System.Text.StringBuilder]::new()
[void]$builder.AppendLine("# Recall Architecture Decision Analysis")
[void]$builder.AppendLine()
[void]$builder.AppendLine("- SourceRunId: $($report.runId)")
[void]$builder.AppendLine("- Cases: $($dataset.Count)")
[void]$builder.AppendLine("- Runs: $($report.results.Count)")
[void]$builder.AppendLine("- Errors: $($errors.Count)")
[void]$builder.AppendLine("- TopK: $($report.topK)")
[void]$builder.AppendLine("- TokenBudget: $($report.tokenBudget)")
[void]$builder.AppendLine("- KeywordCandidatePoolLimit: $KeywordCandidatePoolLimit")
[void]$builder.AppendLine("- RequireCaseIsolatedReturns: $($RequireCaseIsolatedReturns.IsPresent)")
[void]$builder.AppendLine("- ForeignReturnedFragmentCount: $($foreignReturnedFragments.Count)")
[void]$builder.AppendLine("- PairedBootstrapIterations: $BootstrapIterations")
[void]$builder.AppendLine("- RandomSeed: $RandomSeed")
[void]$builder.AppendLine()
[void]$builder.AppendLine("## Overall")
[void]$builder.AppendLine()
[void]$builder.AppendLine("| Mode | Cases | Recall@5 | Case Hit | All Expected | MRR | NDCG | Avg Latency ms | P50 ms | P95 ms | P99 ms |")
[void]$builder.AppendLine("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
foreach ($summary in $modeSummaries) {
    [void]$builder.AppendLine("| $($summary.mode) | $($summary.cases) | $(Format-Decimal $summary.recallAt5) | $(Format-Decimal $summary.caseHitRate) | $(Format-Decimal $summary.allExpectedRate) | $(Format-Decimal $summary.mrr) | $(Format-Decimal $summary.ndcg) | $(Format-Milliseconds $summary.averageLatencyMs) | $(Format-Milliseconds $summary.latencyP50Ms) | $(Format-Milliseconds $summary.latencyP95Ms) | $(Format-Milliseconds $summary.latencyP99Ms) |")
}
[void]$builder.AppendLine()
[void]$builder.AppendLine("## Rerank Identifiability")
[void]$builder.AppendLine()
[void]$builder.AppendLine("| Mode | Type | Model | Version | SHA-256 | Pool | Cases | Not Executed | Non-Identifiable | Identifiable | Order Changed | Changed Rate | Unavailable | Preselection | Input | Output | Score Distinct | Changed Positions | TopK Membership Changed | Rerank P95 ms | Rerank P99 ms |")
[void]$builder.AppendLine("| --- | --- | --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
foreach ($summary in $rerankDiagnosticsSummaries) {
    [void]$builder.AppendLine("| $($summary.mode) | $($summary.rerankerTypes -join ',') | $($summary.models -join ',') | $($summary.modelVersions -join ',') | $($summary.modelSha256 -join ',') | $($summary.candidatePoolStrategies -join ',')/$($summary.candidatePoolLimits -join ',') | $($summary.cases) | $($summary.notExecutedCases) | $($summary.nonIdentifiableCases) | $($summary.identifiableCases) | $($summary.orderChangedCases) | $(Format-Decimal $summary.changedOrderRate) | $($summary.unavailableCases) | $($summary.preselectionCandidateCount) | $($summary.inputCandidateCount) | $($summary.outputCandidateCount) | $($summary.scoreDistinctCount) | $($summary.changedPositionCount) | $($summary.topKMembershipChangedCount) | $(Format-Milliseconds $summary.rerankLatencyP95Ms) | $(Format-Milliseconds $summary.rerankLatencyP99Ms) |")
}
[void]$builder.AppendLine()
[void]$builder.AppendLine("## Paired Comparisons")
[void]$builder.AppendLine()
[void]$builder.AppendLine("| Comparison | Recall Delta | Recall 95% CI | W/T/L | Hit Delta | MRR Delta | NDCG Delta | NDCG 95% CI | Avg Latency Delta ms | P95 Delta ms | P99 Delta ms | Latency Ratio | Rerank Assessment | Conclusion Permitted |")
[void]$builder.AppendLine("| --- | ---: | --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | ---: | --- | --- |")
foreach ($comparison in $comparisons) {
    $recallCi = "[$(Format-Decimal $comparison.recallCi95Lower), $(Format-Decimal $comparison.recallCi95Upper)]"
    $ndcgCi = "[$(Format-Decimal $comparison.ndcgCi95Lower), $(Format-Decimal $comparison.ndcgCi95Upper)]"
    $record = "$($comparison.recallWins)/$($comparison.recallTies)/$($comparison.recallLosses)"
    [void]$builder.AppendLine("| $($comparison.comparison) | $(Format-Decimal $comparison.recallDelta) | $recallCi | $record | $(Format-Decimal $comparison.caseHitRateDelta) | $(Format-Decimal $comparison.mrrDelta) | $(Format-Decimal $comparison.ndcgDelta) | $ndcgCi | $(Format-Milliseconds $comparison.averageLatencyDeltaMs) | $(Format-Milliseconds $comparison.latencyP95DeltaMs) | $(Format-Milliseconds $comparison.latencyP99DeltaMs) | $(Format-Decimal $comparison.averageLatencyRatio) | $($comparison.rerankEffectAssessment) | $($comparison.rerankEffectConclusionPermitted) |")
}
[void]$builder.AppendLine()
[void]$builder.AppendLine("## Category Breakdown")
[void]$builder.AppendLine()
[void]$builder.AppendLine("| Category | Mode | Cases | Recall@5 | Case Hit | NDCG | Avg Latency ms |")
[void]$builder.AppendLine("| --- | --- | ---: | ---: | ---: | ---: | ---: |")
foreach ($summary in $categorySummaries) {
    [void]$builder.AppendLine("| $($summary.category) | $($summary.mode) | $($summary.cases) | $(Format-Decimal $summary.recallAt5) | $(Format-Decimal $summary.caseHitRate) | $(Format-Decimal $summary.ndcg) | $(Format-Milliseconds $summary.averageLatencyMs) |")
}
[void]$builder.AppendLine()
[void]$builder.AppendLine("## Changed Cases")
[void]$builder.AppendLine()
foreach ($comparison in $comparisons) {
    [void]$builder.AppendLine("### $($comparison.comparison)")
    [void]$builder.AppendLine()
    if ($comparison.rerankEffectAssessment -eq "NON_IDENTIFIABLE") {
        [void]$builder.AppendLine("No rerank effectiveness conclusion: $($comparison.rerankEffectAssessmentReason)")
        [void]$builder.AppendLine()
        if ($comparison.changedCases.Count -gt 0) {
            [void]$builder.AppendLine("Observed Recall@5 differences below are diagnostic only and are not attributed to reranking.")
            [void]$builder.AppendLine()
        }
    }
    if ($comparison.changedCases.Count -eq 0) {
        if ($comparison.rerankEffectAssessment -ne "NON_IDENTIFIABLE") {
            [void]$builder.AppendLine("No Recall@5 changes.")
        }
        [void]$builder.AppendLine()
        continue
    }
    [void]$builder.AppendLine("| CaseId | Category | Recall Delta | Hit Delta | MRR Delta | NDCG Delta | Latency Delta ms |")
    [void]$builder.AppendLine("| --- | --- | ---: | ---: | ---: | ---: | ---: |")
    foreach ($changed in $comparison.changedCases) {
        [void]$builder.AppendLine("| $($changed.caseId) | $($changed.category) | $(Format-Decimal $changed.recallDelta) | $(Format-Decimal $changed.caseHitDelta) | $(Format-Decimal $changed.mrrDelta) | $(Format-Decimal $changed.ndcgDelta) | $(Format-Milliseconds $changed.latencyDeltaMs) |")
    }
    [void]$builder.AppendLine()
}
$builder.ToString() | Set-Content -LiteralPath $markdownPath -Encoding UTF8

[pscustomobject]@{
    JsonPath = $jsonPath
    MarkdownPath = $markdownPath
    Cases = $dataset.Count
    Runs = $report.results.Count
    Errors = $errors.Count
    Comparisons = $comparisons.Count
}
