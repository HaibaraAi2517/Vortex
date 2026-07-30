$ErrorActionPreference = "Stop"

function Write-Json {
    param([object]$Value, [string]$Path)
    $json = ($Value | ConvertTo-Json -Depth 30) -replace "`r`n", "`n"
    [System.IO.File]::WriteAllText(
        $Path,
        $json + "`n",
        [System.Text.UTF8Encoding]::new($false))
}

function Assert-Equal {
    param([object]$Actual, [object]$Expected, [string]$Label)
    if ($Actual -ne $Expected) {
        throw "$Label expected '$Expected' but was '$Actual'."
    }
}

function New-CaseResult {
    param(
        [string]$CaseId,
        [string]$Mode,
        [string[]]$ReturnedFragmentIds,
        [double]$Recall,
        [double]$Mrr,
        [double]$Ndcg,
        [long]$LatencyMs,
        [object]$Diagnostics
    )
    return [pscustomobject][ordered]@{
        caseId = $CaseId
        mode = $Mode
        returnedFragmentIds = $ReturnedFragmentIds
        recallHit = $Recall -gt 0.0
        allExpectedReturned = $Recall -eq 1.0
        recallAtK = $Recall
        reciprocalRank = $Mrr
        ndcg = $Ndcg
        latencyMs = $LatencyMs
        recallDiagnostics = $Diagnostics
        errorMessage = $null
    }
}

$analyzer = Join-Path $PSScriptRoot "analyze-recall-decision.ps1"
$tempParent = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "tmp"))
$testRoot = Join-Path $tempParent ("recall-decision-analyzer-test-" + [guid]::NewGuid().ToString("N"))
if (-not $testRoot.StartsWith(
        $tempParent + [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to create analyzer test outside '$tempParent'."
}

try {
    New-Item -ItemType Directory -Force -Path $testRoot | Out-Null
    $outputPath = Join-Path $testRoot "analysis"
    $datasetPath = Join-Path $testRoot "dataset.json"
    $reportPath = Join-Path $testRoot "report.json"
    $modelSha = "a" * 64

    $dataset = @(
        [pscustomobject][ordered]@{
            caseId = "case-a"
            failureCategories = @("multi-session")
            memoryFragments = @(
                [pscustomobject]@{ fragmentId = "a-expected"; content = "expected a" },
                [pscustomobject]@{ fragmentId = "a-distractor"; content = "distractor a" }
            )
        },
        [pscustomobject][ordered]@{
            caseId = "case-b"
            failureCategories = @("temporal-reasoning")
            memoryFragments = @(
                [pscustomobject]@{ fragmentId = "b-expected"; content = "expected b" },
                [pscustomobject]@{ fragmentId = "b-distractor"; content = "distractor b" }
            )
        }
    )
    Write-Json -Value $dataset -Path $datasetPath

    $noneDiagnostics = [pscustomobject][ordered]@{
        rerankerType = "NONE"
        rerankEffectStatus = "NOT_EXECUTED"
        rerankLatencyNanos = 0
    }
    $linearDiagnostics = [pscustomobject][ordered]@{
        rerankerType = "LINEAR_SCORE_FUSION"
        rerankEffectStatus = "NON_IDENTIFIABLE"
        rerankLatencyNanos = 1000000
    }
    $crossEncoderChangedDiagnostics = [pscustomobject][ordered]@{
        rerankerType = "CROSS_ENCODER"
        rerankEffectStatus = "ORDER_CHANGED"
        rerankModel = "synthetic-cross-encoder"
        rerankModelVersion = "1.0.0"
        rerankModelSha256 = $modelSha
        rerankCandidatePoolStrategy = "VECTOR_BASELINE_TOP_40"
        rerankCandidatePoolLimit = 40
        rerankPreselectionCandidateCount = 44
        rerankInputCandidateCount = 40
        rerankOutputCandidateCount = 40
        rerankChangedPositionCount = 4
        rerankTopKMembershipChangedCount = 2
        rerankScoreDistinctCount = 7
        rerankLatencyNanos = 30000000
    }
    $crossEncoderIdentifiableDiagnostics = [pscustomobject][ordered]@{
        rerankerType = "CROSS_ENCODER"
        rerankEffectStatus = "IDENTIFIABLE"
        rerankModel = "synthetic-cross-encoder"
        rerankModelVersion = "1.0.0"
        rerankModelSha256 = $modelSha
        rerankCandidatePoolStrategy = "VECTOR_BASELINE_TOP_40"
        rerankCandidatePoolLimit = 40
        rerankPreselectionCandidateCount = 40
        rerankInputCandidateCount = 40
        rerankOutputCandidateCount = 40
        rerankChangedPositionCount = 0
        rerankTopKMembershipChangedCount = 0
        rerankScoreDistinctCount = 5
        rerankLatencyNanos = 50000000
    }

    $results = @(
        New-CaseResult -CaseId "case-a" -Mode "VectorOnly" `
            -ReturnedFragmentIds @("a-distractor", "a-expected") `
            -Recall 0.0 -Mrr 0.0 -Ndcg 0.0 -LatencyMs 100 -Diagnostics $noneDiagnostics
        New-CaseResult -CaseId "case-b" -Mode "VectorOnly" `
            -ReturnedFragmentIds @("b-expected", "b-distractor") `
            -Recall 1.0 -Mrr 1.0 -Ndcg 1.0 -LatencyMs 110 -Diagnostics $noneDiagnostics
        New-CaseResult -CaseId "case-a" -Mode "Vector+Rerank" `
            -ReturnedFragmentIds @("a-distractor", "a-expected") `
            -Recall 0.0 -Mrr 0.0 -Ndcg 0.0 -LatencyMs 105 -Diagnostics $linearDiagnostics
        New-CaseResult -CaseId "case-b" -Mode "Vector+Rerank" `
            -ReturnedFragmentIds @("b-expected", "b-distractor") `
            -Recall 1.0 -Mrr 1.0 -Ndcg 1.0 -LatencyMs 115 -Diagnostics $linearDiagnostics
        New-CaseResult -CaseId "case-a" -Mode "Vector+CrossEncoderReranker" `
            -ReturnedFragmentIds @("a-expected", "a-distractor") `
            -Recall 1.0 -Mrr 1.0 -Ndcg 1.0 -LatencyMs 140 -Diagnostics $crossEncoderChangedDiagnostics
        New-CaseResult -CaseId "case-b" -Mode "Vector+CrossEncoderReranker" `
            -ReturnedFragmentIds @("b-expected", "b-distractor") `
            -Recall 1.0 -Mrr 1.0 -Ndcg 1.0 -LatencyMs 160 -Diagnostics $crossEncoderIdentifiableDiagnostics
    )
    $report = [pscustomobject][ordered]@{
        runId = "synthetic-cross-encoder"
        topK = 5
        tokenBudget = 4096
        modes = @("VectorOnly", "Vector+Rerank", "Vector+CrossEncoderReranker")
        environmentSnapshot = [pscustomobject][ordered]@{
            recallTopK = 5
            recallAblationModes = @("VECTOR_ONLY", "VECTOR_RERANK", "VECTOR_CROSS_ENCODER")
            crossEncoderCandidatePoolLimit = 40
            hardwareDescription = "synthetic-hardware"
            gpuDescription = "synthetic-gpu"
        }
        results = $results
    }
    Write-Json -Value $report -Path $reportPath

    $run = & $analyzer `
        -ReportPath $reportPath `
        -DatasetPath $datasetPath `
        -OutputDirectory $outputPath `
        -BootstrapIterations 200 `
        -RandomSeed 20260729 `
        -RequireCaseIsolatedReturns
    Assert-Equal $run.Cases 2 "Analyzer case count"
    Assert-Equal $run.Runs 6 "Analyzer run count"
    Assert-Equal $run.Errors 0 "Analyzer error count"
    Assert-Equal $run.Comparisons 3 "Analyzer comparison count"

    $analysisPath = Join-Path $outputPath "recall-decision-analysis.json"
    $markdownPath = Join-Path $outputPath "recall-decision-analysis.md"
    $analysis = Get-Content -LiteralPath $analysisPath -Raw | ConvertFrom-Json
    $markdown = Get-Content -LiteralPath $markdownPath -Raw
    Assert-Equal @($analysis.modeSummaries).Count 3 "Dynamic mode summary count"

    $crossEncoderSummary = @($analysis.rerankDiagnosticsSummaries |
        Where-Object mode -eq "Vector+CrossEncoderReranker")
    Assert-Equal $crossEncoderSummary.Count 1 "Cross-Encoder diagnostics summary count"
    Assert-Equal @($crossEncoderSummary[0].rerankerTypes)[0] "CROSS_ENCODER" "Reranker type"
    Assert-Equal @($crossEncoderSummary[0].models)[0] "synthetic-cross-encoder" "Model name"
    Assert-Equal @($crossEncoderSummary[0].modelVersions)[0] "1.0.0" "Model version"
    Assert-Equal @($crossEncoderSummary[0].modelSha256)[0] $modelSha "Model SHA-256"
    Assert-Equal @($crossEncoderSummary[0].candidatePoolStrategies)[0] `
        "VECTOR_BASELINE_TOP_40" "Candidate pool strategy"
    Assert-Equal @($crossEncoderSummary[0].candidatePoolLimits)[0] 40 "Candidate pool limit"
    Assert-Equal $crossEncoderSummary[0].changedOrderRate 0.5 "Changed-order rate"
    Assert-Equal $crossEncoderSummary[0].scoreDistinctCount 12 "Score distinct count"
    Assert-Equal $crossEncoderSummary[0].rerankLatencyP95Ms 49.0 "Rerank P95"
    Assert-Equal $crossEncoderSummary[0].rerankLatencyP99Ms 49.8 "Rerank P99"

    $crossEncoderComparisons = @($analysis.comparisons |
        Where-Object { $_.leftMode -eq "Vector+CrossEncoderReranker" })
    Assert-Equal $crossEncoderComparisons.Count 2 "Cross-Encoder comparison count"
    $directComparison = @($crossEncoderComparisons |
        Where-Object rightMode -eq "VectorOnly")
    Assert-Equal $directComparison.Count 1 "Direct Cross-Encoder comparison count"
    Assert-Equal $directComparison[0].rerankEffectAssessment "ORDER_CHANGED" `
        "Direct rerank assessment"
    Assert-Equal $directComparison[0].rerankEffectConclusionPermitted $true `
        "Direct rerank conclusion permission"

    if ($markdown -notmatch "Vector\+CrossEncoderReranker" -or
        $markdown -notmatch "synthetic-cross-encoder" -or
        $markdown -notmatch "VECTOR_BASELINE_TOP_40/40") {
        throw "Analyzer Markdown omitted Cross-Encoder audit fields."
    }

    [pscustomobject][ordered]@{
        DynamicModes = "PASS"
        CrossEncoderComparisons = "PASS"
        ModelAndPoolAudit = "PASS"
        LatencyPercentiles = "PASS"
        CaseIsolation = "PASS"
    }
} finally {
    if ((Test-Path -LiteralPath $testRoot) -and
        $testRoot.StartsWith(
            $tempParent + [System.IO.Path]::DirectorySeparatorChar,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
