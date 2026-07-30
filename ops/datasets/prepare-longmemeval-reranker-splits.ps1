param(
    [Parameter(Mandatory = $true)]
    [string]$SourcePath,

    [string]$OutputDirectory = "",

    [string[]]$AuditRoots = @(),

    [string]$Seed = "vortex-longmemeval-reranker-v1-20260729",

    [int]$SplitSize = 120,

    [string]$NamespacePrefix = "longmemeval-reranker-v1",

    [string]$ConverterPath = ""
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "longmemeval-split-common.ps1")

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../.."))
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot "generated/longmemeval-reranker-splits-v1"
}
if ([string]::IsNullOrWhiteSpace($ConverterPath)) {
    $ConverterPath = Join-Path $PSScriptRoot "convert-longmemeval.ps1"
}
if ($AuditRoots.Count -eq 0) {
    $AuditRoots = @(
        (Join-Path $repositoryRoot "ops/datasets"),
        (Join-Path $repositoryRoot "ops/eval-reports"),
        (Join-Path $repositoryRoot "ops/eval-fixtures"),
        (Join-Path $repositoryRoot "vortex-app/src/main/resources"),
        (Join-Path $repositoryRoot "vortex-app/src/test/resources")
    )
}
if ($SplitSize -le 0) {
    throw "SplitSize must be greater than zero."
}
if ([string]::IsNullOrWhiteSpace($Seed)) {
    throw "Seed must not be empty."
}
if ([string]::IsNullOrWhiteSpace($NamespacePrefix)) {
    throw "NamespacePrefix must not be empty."
}

function Get-ProportionalCategoryQuota {
    param(
        [Parameter(Mandatory = $true)][object[]]$Entries,
        [Parameter(Mandatory = $true)][int]$Capacity,
        [Parameter(Mandatory = $true)][string]$AllocationSeed,
        [Parameter(Mandatory = $true)][string]$SplitName
    )

    if ($Entries.Count -lt $Capacity) {
        throw "Cannot allocate $Capacity '$SplitName' cases from $($Entries.Count) eligible cases."
    }
    $rows = [System.Collections.Generic.List[object]]::new()
    $assigned = 0
    foreach ($categoryGroup in @($Entries | Group-Object category | Sort-Object Name)) {
        $exact = ([double]$categoryGroup.Count * $Capacity) / $Entries.Count
        $base = [int][System.Math]::Floor($exact)
        $assigned += $base
        $rows.Add([pscustomobject]@{
            category = $categoryGroup.Name
            available = $categoryGroup.Count
            quota = $base
            fractional = $exact - $base
            tieHash = Get-LongMemEvalStringSha256 (
                "$AllocationSeed|quota|$SplitName|$($categoryGroup.Name)")
        })
    }
    $remaining = $Capacity - $assigned
    $remainderOrder = @($rows |
        Sort-Object @{ Expression = "fractional"; Descending = $true },
            @{ Expression = "tieHash"; Descending = $false },
            @{ Expression = "category"; Descending = $false })
    for ($i = 0; $i -lt $remaining; $i++) {
        if ($remainderOrder[$i].quota -ge $remainderOrder[$i].available) {
            throw "Unable to allocate proportional '$SplitName' quota."
        }
        $remainderOrder[$i].quota++
    }
    $result = @{}
    foreach ($row in $rows) {
        $result[$row.category] = [int]$row.quota
    }
    return $result
}

$outputFullPath = [System.IO.Path]::GetFullPath($OutputDirectory)
$converterFullPath = (Resolve-Path -LiteralPath $ConverterPath).Path
foreach ($root in $AuditRoots) {
    $rootFullPath = (Resolve-Path -LiteralPath $root).Path
    if (Test-LongMemEvalPathWithin -CandidatePath $rootFullPath -RootPath $outputFullPath) {
        throw "Audit root '$rootFullPath' cannot be inside excluded output directory '$outputFullPath'."
    }
}
New-Item -ItemType Directory -Force -Path $outputFullPath | Out-Null

$sourceIndex = Get-LongMemEvalSourceIndex -SourcePath $SourcePath
$audit = Invoke-LongMemEvalUsedCaseAudit `
    -AuditRoots $AuditRoots `
    -SourceIndex $sourceIndex `
    -ExcludedPath $outputFullPath

$usedEntries = [System.Collections.Generic.List[object]]::new()
foreach ($caseId in $audit.UsedIds) {
    $usedEntries.Add([pscustomobject][ordered]@{
        caseId = $caseId
        category = $sourceIndex.CategoryById[$caseId]
        sources = @($audit.CaseSources[$caseId] | Sort-Object -Unique)
    })
}

$retrievalEntries = [System.Collections.Generic.List[object]]::new()
$abstentionEntries = [System.Collections.Generic.List[object]]::new()
foreach ($caseId in @($sourceIndex.CaseIds | Sort-Object)) {
    if ($audit.UsedIds -notcontains $caseId) {
        $entry = [pscustomobject][ordered]@{
            caseId = $caseId
            category = $sourceIndex.CategoryById[$caseId]
            selectionHash = Get-LongMemEvalStringSha256 "$Seed|case|$caseId"
        }
        if ($sourceIndex.HasEvidenceById[$caseId]) {
            $retrievalEntries.Add($entry)
        } else {
            $abstentionEntries.Add($entry)
        }
    }
}

$splitNames = @("dev", "validation", "reserve")
if ($retrievalEntries.Count -lt ($SplitSize * 2)) {
    throw ("Expected at least {0} unused retrieval-eligible cases for dev and validation, " +
        "but evidence filtering left {1}." -f ($SplitSize * 2), $retrievalEntries.Count)
}

$categories = @($retrievalEntries |
    Select-Object -ExpandProperty category -Unique |
    Sort-Object)
$splitEntries = @{
    dev = [System.Collections.Generic.List[object]]::new()
    validation = [System.Collections.Generic.List[object]]::new()
    reserve = [System.Collections.Generic.List[object]]::new()
}
$quotaByCategory = @{}
$unallocated = @($retrievalEntries)
foreach ($splitName in @("dev", "validation")) {
    $quota = Get-ProportionalCategoryQuota -Entries $unallocated -Capacity $SplitSize -AllocationSeed $Seed -SplitName $splitName
    $selectedIds = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    foreach ($category in $categories) {
        $ranked = @($unallocated |
            Where-Object category -eq $category |
            Sort-Object selectionHash, caseId)
        $categoryQuota = [int]$quota[$category]
        $quotaByCategory["$category|$splitName"] = $categoryQuota
        for ($i = 0; $i -lt $categoryQuota; $i++) {
            $selected = $ranked[$i]
            [void]$selectedIds.Add($selected.caseId)
            $splitEntries[$splitName].Add([pscustomobject][ordered]@{
                caseId = $selected.caseId
                category = $selected.category
                selectionHash = $selected.selectionHash
                categoryRank = $i + 1
            })
        }
    }
    $unallocated = @($unallocated | Where-Object {
        -not $selectedIds.Contains($_.caseId)
    })
}
foreach ($category in $categories) {
    $ranked = @($unallocated |
        Where-Object category -eq $category |
        Sort-Object selectionHash, caseId)
    $quotaByCategory["$category|reserve"] = $ranked.Count
    for ($i = 0; $i -lt $ranked.Count; $i++) {
        $selected = $ranked[$i]
        $splitEntries.reserve.Add([pscustomobject][ordered]@{
            caseId = $selected.caseId
            category = $selected.category
            selectionHash = $selected.selectionHash
            categoryRank = $i + 1
        })
    }
}

foreach ($splitName in @("dev", "validation")) {
    if ($splitEntries[$splitName].Count -ne $SplitSize) {
        throw "Allocation produced $($splitEntries[$splitName].Count) '$splitName' cases instead of $SplitSize."
    }
}
if (($splitEntries.dev.Count + $splitEntries.validation.Count + $splitEntries.reserve.Count) -ne
    $retrievalEntries.Count) {
    throw "Retrieval allocation did not cover every eligible unused case."
}

$quotaRows = @($categories | ForEach-Object {
    $category = $_
    [pscustomobject][ordered]@{
        category = $category
        available = @($retrievalEntries | Where-Object category -eq $category).Count
        dev = [int]$quotaByCategory["$category|dev"]
        validation = [int]$quotaByCategory["$category|validation"]
        reserve = [int]$quotaByCategory["$category|reserve"]
    }
})

$quarantineEntries = @($abstentionEntries |
    Sort-Object category, selectionHash, caseId |
    ForEach-Object {
        [pscustomobject][ordered]@{
            caseId = $_.caseId
            category = $_.category
            selectionHash = $_.selectionHash
            reason = "no-positive-evidence-fragment"
        }
    })

$usedIdsFileName = "used-case-ids.json"
$usedIdsPath = Join-Path $outputFullPath $usedIdsFileName
Write-LongMemEvalJson -Value $usedEntries.ToArray() -Path $usedIdsPath

$quarantineFileName = "abstention-quarantine-case-ids.json"
$quarantinePath = Join-Path $outputFullPath $quarantineFileName
Write-LongMemEvalJson -Value $quarantineEntries -Path $quarantinePath

$partitionMetadata = [ordered]@{}
foreach ($splitName in $splitNames) {
    $idFileName = "$splitName-case-ids.json"
    $partitionCount = $splitEntries[$splitName].Count
    $datasetFileName = "longmemeval-reranker-$splitName-$partitionCount-case-isolated.json"
    $idPath = Join-Path $outputFullPath $idFileName
    $datasetPath = Join-Path $outputFullPath $datasetFileName
    Write-LongMemEvalJson -Value $splitEntries[$splitName].ToArray() -Path $idPath

    & $converterFullPath `
        -InputPath $sourceIndex.Path `
        -OutputPath $datasetPath `
        -IncludeCaseIdsFrom $idPath `
        -Namespace "$NamespacePrefix-$splitName" `
        -NamespacePerCase | Out-Null

    $partitionMetadata[$splitName] = [pscustomobject][ordered]@{
        role = if ($splitName -eq "dev") {
            "model-selection-and-threshold-development"
        } elseif ($splitName -eq "validation") {
            "sealed-final-validation"
        } else {
            "untouched-reserve"
        }
        caseCount = $splitEntries[$splitName].Count
        namespaceCount = $splitEntries[$splitName].Count
        categoryCounts = @(Get-LongMemEvalCategoryCounts $splitEntries[$splitName].ToArray())
        caseIdsFile = $idFileName
        caseIdsSha256 = Get-LongMemEvalFileSha256 $idPath
        datasetFile = $datasetFileName
        datasetSha256 = Get-LongMemEvalFileSha256 $datasetPath
        namespaceBase = "$NamespacePrefix-$splitName"
        retrievalEvidenceRequired = $true
    }
}

$manifestFileName = "longmemeval-reranker-splits-manifest.json"
$manifestChecksumFileName = "longmemeval-reranker-splits-manifest.sha256"
$manifestPath = Join-Path $outputFullPath $manifestFileName
$manifestChecksumPath = Join-Path $outputFullPath $manifestChecksumFileName
$manifest = [pscustomobject][ordered]@{
    schemaVersion = 2
    dataset = "LongMemEval oracle"
    source = [pscustomobject][ordered]@{
        path = ConvertTo-LongMemEvalNormalizedPath $sourceIndex.Path
        sha256 = $sourceIndex.Sha256
        caseCount = $sourceIndex.Records.Count
        categoryCounts = $sourceIndex.CategoryCounts
        evidenceCaseCount = $sourceIndex.EvidenceCaseCount
        evidenceCategoryCounts = $sourceIndex.EvidenceCategoryCounts
    }
    generationParameters = [pscustomobject][ordered]@{
        seed = $Seed
        decisionSplitSize = $SplitSize
        splitOrder = $splitNames
        hashAlgorithm = "SHA-256"
        selectionHashInput = "UTF-8(seed|case|caseId)"
        quotaAlgorithm = "sequential-proportional-largest-remainder-with-hash-tiebreak"
        retrievalEligibility = "at-least-one-positive-evidence-fragment"
        abstentionPolicy = "quarantine-outside-retrieval-partitions"
        namespacePrefix = $NamespacePrefix
        namespacePerCase = $true
        validationPolicy = "sealed-until-model-and-decision-thresholds-are-frozen"
        converterPath = ConvertTo-LongMemEvalNormalizedPath $converterFullPath
        converterSha256 = Get-LongMemEvalFileSha256 $converterFullPath
        generatorSha256 = Get-LongMemEvalFileSha256 $PSCommandPath
        commonLibrarySha256 = Get-LongMemEvalFileSha256 (Join-Path $PSScriptRoot "longmemeval-split-common.ps1")
    }
    audit = [pscustomobject][ordered]@{
        roots = $audit.Roots
        excludedOutputDirectory = ConvertTo-LongMemEvalNormalizedPath $outputFullPath
        matchedSourceCount = $audit.MatchedSources.Count
        matchedSources = $audit.MatchedSources
        usedCaseCount = $usedEntries.Count
        usedCategoryCounts = @(Get-LongMemEvalCategoryCounts $usedEntries.ToArray())
        usedCaseIdsFile = $usedIdsFileName
        usedCaseIdsSha256 = Get-LongMemEvalFileSha256 $usedIdsPath
    }
    allocation = [pscustomobject][ordered]@{
        unusedCaseCount = $retrievalEntries.Count + $abstentionEntries.Count
        retrievalEligibleCaseCount = $retrievalEntries.Count
        abstentionCaseCount = $abstentionEntries.Count
        quotaByCategory = $quotaRows
    }
    abstentionQuarantine = [pscustomobject][ordered]@{
        role = "excluded-from-positive-evidence-retrieval-evaluation"
        caseCount = $quarantineEntries.Count
        categoryCounts = @(Get-LongMemEvalCategoryCounts $quarantineEntries)
        caseIdsFile = $quarantineFileName
        caseIdsSha256 = Get-LongMemEvalFileSha256 $quarantinePath
        reason = "no-positive-evidence-fragment"
    }
    partitions = [pscustomobject]$partitionMetadata
    coverage = [pscustomobject][ordered]@{
        sourceCaseCount = $sourceIndex.Records.Count
        usedCaseCount = $usedEntries.Count
        retrievalPartitionCaseCount = $retrievalEntries.Count
        abstentionQuarantineCaseCount = $abstentionEntries.Count
        completeSourceCoverage = $true
        pairwiseDisjoint = $true
    }
    manifestChecksumFile = $manifestChecksumFileName
}
Write-LongMemEvalJson -Value $manifest -Path $manifestPath
$manifestSha256 = Get-LongMemEvalFileSha256 $manifestPath
[System.IO.File]::WriteAllText(
    $manifestChecksumPath,
    "$manifestSha256  $manifestFileName`n",
    [System.Text.UTF8Encoding]::new($false))

[pscustomobject][ordered]@{
    SourceCases = $sourceIndex.Records.Count
    UsedCases = $usedEntries.Count
    RetrievalEligibleCases = $retrievalEntries.Count
    AbstentionQuarantineCases = $abstentionEntries.Count
    DevCases = $splitEntries.dev.Count
    ValidationCases = $splitEntries.validation.Count
    ReserveCases = $splitEntries.reserve.Count
    MatchedAuditSources = $audit.MatchedSources.Count
    OutputDirectory = $outputFullPath
    ManifestPath = $manifestPath
    ManifestSha256 = $manifestSha256
}
