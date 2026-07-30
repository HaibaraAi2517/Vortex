$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "longmemeval-split-common.ps1")

function Assert-ThrowsMatch {
    param(
        [Parameter(Mandatory = $true)][scriptblock]$Action,
        [Parameter(Mandatory = $true)][string]$Pattern,
        [Parameter(Mandatory = $true)][string]$Label
    )

    try {
        & $Action
    } catch {
        if ($_.Exception.Message -notmatch $Pattern) {
            throw "$Label failed with unexpected error: $($_.Exception.Message)"
        }
        return
    }
    throw "$Label did not reject the invalid artifact."
}

function Get-TestCanonicalTextSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)

    $text = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $Path).Path)
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) {
        $text = $text.Substring(1)
    }
    return Get-LongMemEvalStringSha256 (
        $text.Replace("`r`n", "`n").Replace("`r", "`n"))
}

$prepareScript = Join-Path $PSScriptRoot "prepare-longmemeval-reranker-splits.ps1"
$validateScript = Join-Path $PSScriptRoot "validate-longmemeval-splits.ps1"
$converterScript = Join-Path $PSScriptRoot "convert-longmemeval.ps1"
$tempParent = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../tmp"))
$testRoot = Join-Path $tempParent ("longmemeval-split-test-" + [System.Guid]::NewGuid().ToString("N"))
if (-not (Test-LongMemEvalPathWithin -CandidatePath $testRoot -RootPath $tempParent)) {
    throw "Refusing to use test path outside '$tempParent'."
}

try {
    $historyRoot = Join-Path $testRoot "history"
    $outputRoot = Join-Path $testRoot "output"
    $syntheticConverterPath = Join-Path $testRoot "convert-longmemeval.ps1"
    New-Item -ItemType Directory -Force -Path $historyRoot | Out-Null
    Copy-Item -LiteralPath $converterScript -Destination $syntheticConverterPath

    $categories = @("knowledge-update", "multi-session", "temporal-reasoning")
    $sourceRecords = [System.Collections.Generic.List[object]]::new()
    for ($categoryIndex = 0; $categoryIndex -lt $categories.Count; $categoryIndex++) {
        for ($caseIndex = 1; $caseIndex -le 4; $caseIndex++) {
            $number = ($categoryIndex * 4) + $caseIndex
            $caseId = "synthetic-{0:00}" -f $number
            $sourceRecords.Add([pscustomobject][ordered]@{
                question_id = $caseId
                question_type = $categories[$categoryIndex]
                question = "Question for $caseId?"
                answer = "answer-$caseId"
                haystack_sessions = @(
                    @(
                        [pscustomobject][ordered]@{
                            role = "user"
                            content = "Evidence for $caseId"
                            has_answer = $true
                        }
                    )
                )
            })
        }
    }
    for ($caseIndex = 1; $caseIndex -le 2; $caseIndex++) {
        $caseId = "synthetic-abstention-{0:00}" -f $caseIndex
        $sourceRecords.Add([pscustomobject][ordered]@{
            question_id = $caseId
            question_type = "multi-session"
            question = "Unanswerable question for $caseId?"
            answer = "abstain"
            haystack_sessions = @(
                @(
                    [pscustomobject][ordered]@{
                        role = "user"
                        content = "Non-evidence context for $caseId"
                        has_answer = $false
                    }
                )
            )
        })
    }
    $sourcePath = Join-Path $testRoot "source.json"
    Write-LongMemEvalJson -Value $sourceRecords.ToArray() -Path $sourcePath

    $historicalCases = @(
        [pscustomobject]@{ caseId = "synthetic-01" },
        [pscustomobject]@{ caseId = "synthetic-05" },
        [pscustomobject]@{ caseId = "synthetic-09" }
    )
    Write-LongMemEvalJson `
        -Value $historicalCases `
        -Path (Join-Path $historyRoot "historical-report.json")

    $prepareParameters = @{
        SourcePath = $sourcePath
        OutputDirectory = $outputRoot
        AuditRoots = @($historyRoot)
        Seed = "synthetic-regression-seed"
        SplitSize = 3
        NamespacePrefix = "synthetic-reranker"
        ConverterPath = $syntheticConverterPath
    }
    $firstGeneration = & $prepareScript @prepareParameters
    $manifestPath = $firstGeneration.ManifestPath
    $validResult = & $validateScript -ManifestPath $manifestPath -SourcePath $sourcePath
    if (-not $validResult.Valid) {
        throw "Valid synthetic split did not pass validation."
    }

    $artifactHashes = @{}
    foreach ($file in @(Get-ChildItem -LiteralPath $outputRoot -File | Sort-Object Name)) {
        $artifactHashes[$file.Name] = Get-LongMemEvalFileSha256 $file.FullName
    }
    [void](& $prepareScript @prepareParameters)
    foreach ($file in @(Get-ChildItem -LiteralPath $outputRoot -File | Sort-Object Name)) {
        $actualHash = Get-LongMemEvalFileSha256 $file.FullName
        if ($artifactHashes[$file.Name] -ne $actualHash) {
            throw "Determinism failure for '$($file.Name)'."
        }
    }

    $manifest = @(Read-LongMemEvalJsonRecords -Path $manifestPath)[0]
    if ([int]$manifest.abstentionQuarantine.caseCount -ne 2 -or
        [int]$manifest.partitions.dev.caseCount -ne 3 -or
        [int]$manifest.partitions.validation.caseCount -ne 3 -or
        [int]$manifest.partitions.reserve.caseCount -ne 3) {
        throw "Synthetic retrieval/abstention allocation does not match the expected 3/3/3/2 layout."
    }

    $originalConverterBytes = [System.IO.File]::ReadAllBytes($syntheticConverterPath)
    [System.IO.File]::AppendAllText(
        $syntheticConverterPath,
        "# Non-semantic provenance reconciliation test.`n",
        [System.Text.UTF8Encoding]::new($false))
    Assert-ThrowsMatch `
        -Action { & $validateScript -ManifestPath $manifestPath -SourcePath $sourcePath } `
        -Pattern "explicit -ReconciliationPath" `
        -Label "Missing reconciliation gate"

    $reconciliationPath = Join-Path $testRoot "converter-reconciliation.json"
    $reconciledPartitions = foreach ($splitName in @($manifest.generationParameters.splitOrder)) {
        $partition = $manifest.partitions.$splitName
        [pscustomobject][ordered]@{
            name = [string]$splitName
            caseIdsSha256 = [string]$partition.caseIdsSha256
            datasetSha256 = [string]$partition.datasetSha256
            namespaceBase = [string]$partition.namespaceBase
        }
    }
    $reconciliation = [pscustomobject][ordered]@{
        schemaVersion = 1
        recordType = "LONGMEMEVAL_CONVERTER_PROVENANCE_RECONCILIATION"
        status = "ACCEPTED"
        historicalManifest = [pscustomobject][ordered]@{
            sha256 = Get-LongMemEvalFileSha256 $manifestPath
            schemaVersion = [int]$manifest.schemaVersion
            converterRawSha256 = [string]$manifest.generationParameters.converterSha256
        }
        replacementConverter = [pscustomobject][ordered]@{
            repositoryPath = ConvertTo-LongMemEvalNormalizedPath $syntheticConverterPath
            observedRawSha256AtReconciliation = Get-LongMemEvalFileSha256 $syntheticConverterPath
            canonicalization = "UTF-8 text; optional BOM removed; CRLF and CR normalized to LF; content otherwise unchanged"
            canonicalTextSha256 = Get-TestCanonicalTextSha256 $syntheticConverterPath
        }
        sourceSha256 = [string]$manifest.source.sha256
        partitions = @($reconciledPartitions)
        verification = [pscustomobject][ordered]@{
            protocol = "Synthetic exact-output reconciliation."
            requiresAllPartitions = $true
            permitsManifestRewrite = $false
            permitsPartitionMutation = $false
            permitsValidationModelRun = $false
            permitsReserveModelRun = $false
        }
    }
    Write-LongMemEvalJson -Value $reconciliation -Path $reconciliationPath
    $reconciledResult = & $validateScript `
        -ManifestPath $manifestPath `
        -SourcePath $sourcePath `
        -ReconciliationPath $reconciliationPath
    if (-not $reconciledResult.Valid -or
        $reconciledResult.ConverterProvenance -ne
            "RECONCILED_CANONICAL_HASH_AND_OUTPUT_EQUIVALENCE") {
        throw "Exact-output converter reconciliation did not pass."
    }

    $validCanonicalSha256 = $reconciliation.replacementConverter.canonicalTextSha256
    $reconciliation.replacementConverter.canonicalTextSha256 = "0" * 64
    Write-LongMemEvalJson -Value $reconciliation -Path $reconciliationPath
    Assert-ThrowsMatch `
        -Action {
            & $validateScript -ManifestPath $manifestPath -SourcePath $sourcePath `
                -ReconciliationPath $reconciliationPath
        } `
        -Pattern "canonical SHA-256 mismatch" `
        -Label "Reconciliation converter identity gate"
    $reconciliation.replacementConverter.canonicalTextSha256 = $validCanonicalSha256

    $reconciliation.partitions[0].datasetSha256 = "0" * 64
    Write-LongMemEvalJson -Value $reconciliation -Path $reconciliationPath
    Assert-ThrowsMatch `
        -Action {
            & $validateScript -ManifestPath $manifestPath -SourcePath $sourcePath `
                -ReconciliationPath $reconciliationPath
        } `
        -Pattern "partition 'dev'" `
        -Label "Reconciliation partition identity gate"
    $reconciliation.partitions[0].datasetSha256 = [string]$manifest.partitions.dev.datasetSha256

    $reconciliation.verification.permitsValidationModelRun = $true
    Write-LongMemEvalJson -Value $reconciliation -Path $reconciliationPath
    Assert-ThrowsMatch `
        -Action {
            & $validateScript -ManifestPath $manifestPath -SourcePath $sourcePath `
                -ReconciliationPath $reconciliationPath
        } `
        -Pattern "Reconciliation boundary" `
        -Label "Reconciliation authorization boundary gate"
    $reconciliation.verification.permitsValidationModelRun = $false
    [System.IO.File]::WriteAllBytes($syntheticConverterPath, $originalConverterBytes)

    $usedPath = Join-Path $outputRoot $manifest.audit.usedCaseIdsFile
    $devIdsPath = Join-Path $outputRoot $manifest.partitions.dev.caseIdsFile
    $devDatasetPath = Join-Path $outputRoot $manifest.partitions.dev.datasetFile
    $validationIdsPath = Join-Path $outputRoot $manifest.partitions.validation.caseIdsFile

    $devUsageIds = @(Read-LongMemEvalJsonRecords -Path $devIdsPath)
    $allowedDevUsagePath = Join-Path $historyRoot "authorized-dev-report.json"
    Write-LongMemEvalJson `
        -Value @([pscustomobject]@{ caseId = $devUsageIds[0].caseId }) `
        -Path $allowedDevUsagePath
    $postDevResult = & $validateScript -ManifestPath $manifestPath -SourcePath $sourcePath
    if (-not $postDevResult.Valid -or [int]$postDevResult.AuthorizedDevAuditSources -ne 1) {
        throw "Authorized dev usage was not accepted by the historical audit lifecycle gate."
    }

    $validationUsageIds = @(Read-LongMemEvalJsonRecords -Path $validationIdsPath)
    $unauthorizedValidationPath = Join-Path $historyRoot "unauthorized-validation-report.json"
    Write-LongMemEvalJson `
        -Value @([pscustomobject]@{ caseId = $validationUsageIds[0].caseId }) `
        -Path $unauthorizedValidationPath
    Assert-ThrowsMatch `
        -Action { & $validateScript -ManifestPath $manifestPath -SourcePath $sourcePath } `
        -Pattern "Historical audit overlap/drift detected" `
        -Label "Sealed partition usage gate"
    Remove-Item -LiteralPath $unauthorizedValidationPath

    $devIdsBytes = [System.IO.File]::ReadAllBytes($devIdsPath)
    $devIds = @(Read-LongMemEvalJsonRecords -Path $devIdsPath)
    $usedIds = @(Read-LongMemEvalJsonRecords -Path $usedPath)
    $devIds[0].caseId = $usedIds[0].caseId
    Write-LongMemEvalJson -Value $devIds -Path $devIdsPath
    Assert-ThrowsMatch `
        -Action { & $validateScript -ManifestPath $manifestPath -SourcePath $sourcePath } `
        -Pattern "overlap" `
        -Label "Overlap gate"
    [System.IO.File]::WriteAllBytes($devIdsPath, $devIdsBytes)

    $devDatasetBytes = [System.IO.File]::ReadAllBytes($devDatasetPath)
    $devDataset = @(Read-LongMemEvalJsonRecords -Path $devDatasetPath)
    $devDataset[0].namespace = $devDataset[1].namespace
    Write-LongMemEvalJson -Value $devDataset -Path $devDatasetPath
    Assert-ThrowsMatch `
        -Action { & $validateScript -ManifestPath $manifestPath -SourcePath $sourcePath } `
        -Pattern "Namespace isolation failure" `
        -Label "Namespace isolation gate"
    [System.IO.File]::WriteAllBytes($devDatasetPath, $devDatasetBytes)

    $devDataset = @(Read-LongMemEvalJsonRecords -Path $devDatasetPath)
    $devDataset[0].expectedFragments = @()
    Write-LongMemEvalJson -Value $devDataset -Path $devDatasetPath
    Assert-ThrowsMatch `
        -Action { & $validateScript -ManifestPath $manifestPath -SourcePath $sourcePath } `
        -Pattern "Retrieval eligibility failure" `
        -Label "Retrieval eligibility gate"
    [System.IO.File]::WriteAllBytes($devDatasetPath, $devDatasetBytes)

    $devDataset = @(Read-LongMemEvalJsonRecords -Path $devDatasetPath)
    $devDataset[0].question = $devDataset[0].question + " hash-drift"
    Write-LongMemEvalJson -Value $devDataset -Path $devDatasetPath
    Assert-ThrowsMatch `
        -Action { & $validateScript -ManifestPath $manifestPath -SourcePath $sourcePath } `
        -Pattern "SHA-256 drift" `
        -Label "Hash drift gate"
    [System.IO.File]::WriteAllBytes($devDatasetPath, $devDatasetBytes)

    $finalResult = & $validateScript -ManifestPath $manifestPath -SourcePath $sourcePath
    [pscustomobject][ordered]@{
        SyntheticValidPath = if ($finalResult.Valid) { "PASS" } else { "FAIL" }
        DeterministicRegeneration = "PASS"
        OverlapGate = "PASS"
        AbstentionQuarantineGate = "PASS"
        RetrievalEligibilityGate = "PASS"
        AuthorizedDevUsageGate = "PASS"
        SealedPartitionUsageGate = "PASS"
        NamespaceIsolationGate = "PASS"
        HashDriftGate = "PASS"
        MissingReconciliationGate = "PASS"
        ExactOutputReconciliation = "PASS"
        ReconciliationIdentityGate = "PASS"
        ReconciliationPartitionGate = "PASS"
        ReconciliationBoundaryGate = "PASS"
    }
} finally {
    if ((Test-Path -LiteralPath $testRoot) -and
        (Test-LongMemEvalPathWithin -CandidatePath $testRoot -RootPath $tempParent)) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
