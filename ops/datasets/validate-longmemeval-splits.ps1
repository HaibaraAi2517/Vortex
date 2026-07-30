param(
    [Parameter(Mandatory = $true)]
    [string]$ManifestPath,

    [string]$SourcePath = "",

    [string]$ReconciliationPath = ""
)

$ErrorActionPreference = "Stop"

. (Join-Path $PSScriptRoot "longmemeval-split-common.ps1")

function Resolve-ManifestArtifact {
    param(
        [Parameter(Mandatory = $true)][string]$ManifestDirectory,
        [Parameter(Mandatory = $true)][string]$FileName
    )

    if ([string]::IsNullOrWhiteSpace($FileName)) {
        throw "Manifest contains an empty artifact file name."
    }
    $path = if ([System.IO.Path]::IsPathRooted($FileName)) {
        [System.IO.Path]::GetFullPath($FileName)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $ManifestDirectory $FileName))
    }
    if (-not (Test-LongMemEvalPathWithin -CandidatePath $path -RootPath $ManifestDirectory)) {
        throw "Manifest artifact '$FileName' escapes manifest directory '$ManifestDirectory'."
    }
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Manifest artifact '$path' does not exist."
    }
    return $path
}

function Read-CaseEntries {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $entries = @(Read-LongMemEvalJsonRecords -Path $Path)
    if ($entries.Count -eq 0) {
        throw "$Label contains zero cases."
    }
    $ids = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $entries) {
        $caseId = Get-LongMemEvalCaseId $entry
        if ([string]::IsNullOrWhiteSpace($caseId)) {
            throw "$Label contains an entry without a case ID."
        }
        if (-not $ids.Add($caseId)) {
            throw "$Label contains duplicate case ID '$caseId'."
        }
    }
    return [pscustomobject]@{
        Entries = $entries
        Ids = $ids
        OrderedIds = @($entries | ForEach-Object { Get-LongMemEvalCaseId $_ })
    }
}

function Assert-NoOverlap {
    param(
        [Parameter(Mandatory = $true)][object]$Left,
        [Parameter(Mandatory = $true)][string]$LeftLabel,
        [Parameter(Mandatory = $true)][object]$Right,
        [Parameter(Mandatory = $true)][string]$RightLabel
    )

    $overlap = @($Left.Ids | Where-Object { $Right.Ids.Contains($_) } | Sort-Object)
    if ($overlap.Count -gt 0) {
        throw "Case ID overlap between '$LeftLabel' and '$RightLabel': $($overlap -join ', ')."
    }
}

function Assert-SetEquals {
    param(
        [Parameter(Mandatory = $true)][System.Collections.Generic.HashSet[string]]$Expected,
        [Parameter(Mandatory = $true)][System.Collections.Generic.HashSet[string]]$Actual,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $missing = @($Expected | Where-Object { -not $Actual.Contains($_) } | Sort-Object)
    $unexpected = @($Actual | Where-Object { -not $Expected.Contains($_) } | Sort-Object)
    if ($missing.Count -gt 0 -or $unexpected.Count -gt 0) {
        throw ("$Label set mismatch. Missing=[{0}] Unexpected=[{1}]" -f
            ($missing -join ", "), ($unexpected -join ", "))
    }
}

function Assert-SequenceEquals {
    param(
        [Parameter(Mandatory = $true)][object[]]$Expected,
        [Parameter(Mandatory = $true)][object[]]$Actual,
        [Parameter(Mandatory = $true)][string]$Label
    )

    if ($Expected.Count -ne $Actual.Count) {
        throw "$Label sequence count mismatch: expected $($Expected.Count), actual $($Actual.Count)."
    }
    for ($i = 0; $i -lt $Expected.Count; $i++) {
        if (-not ([string]$Expected[$i]).Equals(
                [string]$Actual[$i],
                [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "$Label sequence mismatch at index ${i}: expected '$($Expected[$i])', actual '$($Actual[$i])'."
        }
    }
}

function Get-CategoryCountSignature {
    param([Parameter(Mandatory = $true)][object[]]$Counts)

    return (@($Counts |
        Sort-Object category |
        ForEach-Object { "$($_.category)=$([int]$_.count)" }) -join ";")
}

function Assert-Hash {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Expected,
        [Parameter(Mandatory = $true)][string]$Label
    )

    $actual = Get-LongMemEvalFileSha256 $Path
    if (-not $actual.Equals($Expected, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "SHA-256 drift for $Label '$Path': expected $Expected, actual $actual."
    }
}

function Get-CanonicalTextSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)

    $fullPath = (Resolve-Path -LiteralPath $Path).Path
    $text = [System.IO.File]::ReadAllText($fullPath)
    if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) {
        $text = $text.Substring(1)
    }
    $canonicalText = $text.Replace("`r`n", "`n").Replace("`r", "`n")
    return Get-LongMemEvalStringSha256 $canonicalText
}

function Assert-BooleanProperty {
    param(
        [Parameter(Mandatory = $true)][object]$Object,
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Expected,
        [Parameter(Mandatory = $true)][string]$Label
    )

    if ($null -eq $Object.PSObject.Properties[$Name] -or
        $Object.$Name -isnot [bool] -or
        [bool]$Object.$Name -ne $Expected) {
        throw "$Label must declare '$Name=$($Expected.ToString().ToLowerInvariant())'."
    }
}

function Assert-ConverterReconciliation {
    param(
        [Parameter(Mandatory = $true)][object]$Manifest,
        [Parameter(Mandatory = $true)][string]$ManifestSha256,
        [Parameter(Mandatory = $true)][hashtable]$Partitions,
        [Parameter(Mandatory = $true)][string[]]$SplitNames,
        [Parameter(Mandatory = $true)][string]$EffectiveSourcePath,
        [Parameter(Mandatory = $true)][string]$RecordPath
    )

    $recordFullPath = (Resolve-Path -LiteralPath $RecordPath).Path
    $records = @(Read-LongMemEvalJsonRecords -Path $recordFullPath)
    if ($records.Count -ne 1) {
        throw "Reconciliation record '$recordFullPath' must contain one JSON object."
    }
    $record = $records[0]
    if ([int]$record.schemaVersion -ne 1 -or
        [string]$record.recordType -ne "LONGMEMEVAL_CONVERTER_PROVENANCE_RECONCILIATION" -or
        [string]$record.status -ne "ACCEPTED") {
        throw "Reconciliation record '$recordFullPath' has an unsupported schema, type, or status."
    }
    if (-not ([string]$record.historicalManifest.sha256).Equals(
            $ManifestSha256,
            [System.StringComparison]::OrdinalIgnoreCase) -or
        [int]$record.historicalManifest.schemaVersion -ne [int]$Manifest.schemaVersion -or
        -not ([string]$record.historicalManifest.converterRawSha256).Equals(
            [string]$Manifest.generationParameters.converterSha256,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Reconciliation record does not match the historical manifest and converter identity."
    }
    if (-not ([string]$record.sourceSha256).Equals(
            [string]$Manifest.source.sha256,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Reconciliation record source SHA-256 does not match the manifest."
    }

    $repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../.."))
    $historicalConverterPath = [string]$Manifest.generationParameters.converterPath
    $expectedConverterPath = if (Test-Path -LiteralPath $historicalConverterPath -PathType Leaf) {
        [System.IO.Path]::GetFullPath($historicalConverterPath)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot "convert-longmemeval.ps1"))
    }
    $declaredConverterPath = [string]$record.replacementConverter.repositoryPath
    $converterPath = if ([System.IO.Path]::IsPathRooted($declaredConverterPath)) {
        [System.IO.Path]::GetFullPath($declaredConverterPath)
    } else {
        [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $declaredConverterPath))
    }
    if (-not (Test-LongMemEvalPathWithin -CandidatePath $converterPath -RootPath $repositoryRoot) -or
        -not $converterPath.Equals($expectedConverterPath, [System.StringComparison]::OrdinalIgnoreCase) -or
        -not (Test-Path -LiteralPath $converterPath -PathType Leaf)) {
        throw "Reconciliation replacement converter must resolve to the repository converter."
    }
    if ([string]$record.replacementConverter.canonicalization -ne
        "UTF-8 text; optional BOM removed; CRLF and CR normalized to LF; content otherwise unchanged") {
        throw "Reconciliation record declares an unsupported converter canonicalization."
    }
    $canonicalSha256 = Get-CanonicalTextSha256 $converterPath
    if (-not $canonicalSha256.Equals(
            [string]$record.replacementConverter.canonicalTextSha256,
            [System.StringComparison]::OrdinalIgnoreCase)) {
        throw ("Reconciliation replacement converter canonical SHA-256 mismatch: " +
            "expected $($record.replacementConverter.canonicalTextSha256), actual $canonicalSha256.")
    }

    Assert-BooleanProperty -Object $record.verification -Name "requiresAllPartitions" `
        -Expected $true -Label "Reconciliation boundary"
    Assert-BooleanProperty -Object $record.verification -Name "permitsManifestRewrite" `
        -Expected $false -Label "Reconciliation boundary"
    Assert-BooleanProperty -Object $record.verification -Name "permitsPartitionMutation" `
        -Expected $false -Label "Reconciliation boundary"
    Assert-BooleanProperty -Object $record.verification -Name "permitsValidationModelRun" `
        -Expected $false -Label "Reconciliation boundary"
    Assert-BooleanProperty -Object $record.verification -Name "permitsReserveModelRun" `
        -Expected $false -Label "Reconciliation boundary"

    $reconciledPartitions = @($record.partitions)
    if ($reconciledPartitions.Count -ne $SplitNames.Count) {
        throw "Reconciliation record must contain exactly $($SplitNames.Count) partitions."
    }
    $reconciliationByName = @{}
    foreach ($entry in $reconciledPartitions) {
        $name = [string]$entry.name
        if ($SplitNames -notcontains $name -or $reconciliationByName.ContainsKey($name)) {
            throw "Reconciliation record contains an unknown or duplicate partition '$name'."
        }
        $reconciliationByName[$name] = $entry
    }
    foreach ($splitName in $SplitNames) {
        $entry = $reconciliationByName[$splitName]
        $partition = $Partitions[$splitName]
        if (-not ([string]$entry.caseIdsSha256).Equals(
                [string]$partition.Manifest.caseIdsSha256,
                [System.StringComparison]::OrdinalIgnoreCase) -or
            -not ([string]$entry.datasetSha256).Equals(
                [string]$partition.Manifest.datasetSha256,
                [System.StringComparison]::OrdinalIgnoreCase) -or
            [string]$entry.namespaceBase -ne [string]$partition.Manifest.namespaceBase) {
            throw "Reconciliation partition '$splitName' does not exactly match the historical manifest."
        }
    }

    $tempParent = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    $tempRoot = Join-Path $tempParent (
        "vortex-longmemeval-reconciliation-" + [System.Guid]::NewGuid().ToString("N"))
    if (-not (Test-LongMemEvalPathWithin -CandidatePath $tempRoot -RootPath $tempParent)) {
        throw "Refusing to use reconciliation path outside '$tempParent'."
    }
    try {
        New-Item -ItemType Directory -Path $tempRoot | Out-Null
        foreach ($splitName in $SplitNames) {
            $partition = $Partitions[$splitName]
            $outputPath = Join-Path $tempRoot "$splitName.json"
            & $converterPath `
                -InputPath $EffectiveSourcePath `
                -OutputPath $outputPath `
                -IncludeCaseIdsFrom $partition.IdPath `
                -Namespace ([string]$partition.Manifest.namespaceBase) `
                -NamespacePerCase | Out-Null
            Assert-Hash `
                -Path $outputPath `
                -Expected ([string]$partition.Manifest.datasetSha256) `
                -Label "reconciled $splitName dataset"
        }
    } finally {
        if ((Test-Path -LiteralPath $tempRoot) -and
            (Test-LongMemEvalPathWithin -CandidatePath $tempRoot -RootPath $tempParent)) {
            Remove-Item -LiteralPath $tempRoot -Recurse -Force
        }
    }

    return [pscustomobject][ordered]@{
        Mode = "RECONCILED_CANONICAL_HASH_AND_OUTPUT_EQUIVALENCE"
        ConverterCanonicalSha256 = $canonicalSha256
        ReconciliationPath = ConvertTo-LongMemEvalNormalizedPath $recordFullPath
        ReconciliationSha256 = Get-LongMemEvalFileSha256 $recordFullPath
    }
}

$manifestFullPath = (Resolve-Path -LiteralPath $ManifestPath).Path
$manifestDirectory = Split-Path -Parent $manifestFullPath
$manifestRecords = @(Read-LongMemEvalJsonRecords -Path $manifestFullPath)
if ($manifestRecords.Count -ne 1) {
    throw "Manifest '$manifestFullPath' must contain one JSON object."
}
$manifest = $manifestRecords[0]
if ([int]$manifest.schemaVersion -ne 2) {
    throw "Unsupported LongMemEval split manifest schemaVersion '$($manifest.schemaVersion)'."
}

$checksumPath = Resolve-ManifestArtifact `
    -ManifestDirectory $manifestDirectory `
    -FileName ([string]$manifest.manifestChecksumFile)
$checksumLine = [System.IO.File]::ReadAllText($checksumPath).Trim()
if ($checksumLine -notmatch "^([0-9a-fA-F]{64})\s+(.+)$") {
    throw "Manifest checksum file '$checksumPath' has invalid format."
}
if (-not $Matches[2].Equals(
        [System.IO.Path]::GetFileName($manifestFullPath),
        [System.StringComparison]::Ordinal)) {
    throw "Manifest checksum names '$($Matches[2])' instead of '$([System.IO.Path]::GetFileName($manifestFullPath))'."
}
$manifestSha256 = [string]$Matches[1]
Assert-Hash -Path $manifestFullPath -Expected $manifestSha256 -Label "manifest"

$effectiveSourcePath = if ([string]::IsNullOrWhiteSpace($SourcePath)) {
    [string]$manifest.source.path
} else {
    $SourcePath
}
$sourceIndex = Get-LongMemEvalSourceIndex -SourcePath $effectiveSourcePath
if ($sourceIndex.Records.Count -ne [int]$manifest.source.caseCount) {
    throw "Source case count drift: expected $($manifest.source.caseCount), actual $($sourceIndex.Records.Count)."
}
if ((Get-CategoryCountSignature $sourceIndex.CategoryCounts) -ne
    (Get-CategoryCountSignature @($manifest.source.categoryCounts))) {
    throw "Source category distribution drift."
}
if ($sourceIndex.EvidenceCaseCount -ne [int]$manifest.source.evidenceCaseCount -or
    (Get-CategoryCountSignature $sourceIndex.EvidenceCategoryCounts) -ne
        (Get-CategoryCountSignature @($manifest.source.evidenceCategoryCounts))) {
    throw "Source retrieval-evidence distribution drift."
}
Assert-Hash -Path $sourceIndex.Path -Expected ([string]$manifest.source.sha256) -Label "source"

$usedPath = Resolve-ManifestArtifact `
    -ManifestDirectory $manifestDirectory `
    -FileName ([string]$manifest.audit.usedCaseIdsFile)
$used = Read-CaseEntries -Path $usedPath -Label "used case manifest"
if ($used.Entries.Count -ne [int]$manifest.audit.usedCaseCount) {
    throw "Used case count mismatch: expected $($manifest.audit.usedCaseCount), actual $($used.Entries.Count)."
}

$quarantinePath = Resolve-ManifestArtifact `
    -ManifestDirectory $manifestDirectory `
    -FileName ([string]$manifest.abstentionQuarantine.caseIdsFile)
$quarantine = Read-CaseEntries -Path $quarantinePath -Label "abstention quarantine"
if ($quarantine.Entries.Count -ne [int]$manifest.abstentionQuarantine.caseCount) {
    throw "Abstention quarantine count does not match manifest."
}

$splitNames = @($manifest.generationParameters.splitOrder | ForEach-Object { [string]$_ })
if ($splitNames.Count -ne 3 -or
    $splitNames[0] -ne "dev" -or
    $splitNames[1] -ne "validation" -or
    $splitNames[2] -ne "reserve") {
    throw "Manifest splitOrder must be exactly dev, validation, reserve."
}

$partitions = @{}
foreach ($splitName in $splitNames) {
    $partitionManifest = $manifest.partitions.$splitName
    if ($null -eq $partitionManifest) {
        throw "Manifest is missing '$splitName' partition metadata."
    }
    $idPath = Resolve-ManifestArtifact `
        -ManifestDirectory $manifestDirectory `
        -FileName ([string]$partitionManifest.caseIdsFile)
    $datasetPath = Resolve-ManifestArtifact `
        -ManifestDirectory $manifestDirectory `
        -FileName ([string]$partitionManifest.datasetFile)
    $ids = Read-CaseEntries -Path $idPath -Label "$splitName case ID manifest"
    $dataset = Read-CaseEntries -Path $datasetPath -Label "$splitName dataset"
    $partitions[$splitName] = [pscustomobject]@{
        Manifest = $partitionManifest
        IdPath = $idPath
        DatasetPath = $datasetPath
        Ids = $ids
        Dataset = $dataset
    }
}

foreach ($splitName in $splitNames) {
    Assert-NoOverlap -Left $used -LeftLabel "used" `
        -Right $partitions[$splitName].Ids -RightLabel $splitName
    Assert-NoOverlap -Left $quarantine -LeftLabel "abstention quarantine" `
        -Right $partitions[$splitName].Ids -RightLabel $splitName
}
Assert-NoOverlap -Left $used -LeftLabel "used" `
    -Right $quarantine -RightLabel "abstention quarantine"
for ($i = 0; $i -lt $splitNames.Count; $i++) {
    for ($j = $i + 1; $j -lt $splitNames.Count; $j++) {
        Assert-NoOverlap `
            -Left $partitions[$splitNames[$i]].Ids `
            -LeftLabel $splitNames[$i] `
            -Right $partitions[$splitNames[$j]].Ids `
            -RightLabel $splitNames[$j]
    }
}

$coverageIds = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase)
foreach ($caseId in $used.Ids) {
    [void]$coverageIds.Add($caseId)
}
foreach ($splitName in $splitNames) {
    foreach ($caseId in $partitions[$splitName].Ids.Ids) {
        [void]$coverageIds.Add($caseId)
    }
}
foreach ($caseId in $quarantine.Ids) {
    [void]$coverageIds.Add($caseId)
}
Assert-SetEquals -Expected $sourceIndex.CaseIds -Actual $coverageIds -Label "Source coverage"

foreach ($entry in $used.Entries) {
    $caseId = Get-LongMemEvalCaseId $entry
    if (-not $sourceIndex.CaseIds.Contains($caseId)) {
        throw "Used case '$caseId' does not exist in source."
    }
    if ((Get-LongMemEvalCategory $entry) -ne $sourceIndex.CategoryById[$caseId]) {
        throw "Used case '$caseId' category does not match source."
    }
}

foreach ($entry in $quarantine.Entries) {
    $caseId = Get-LongMemEvalCaseId $entry
    if (-not $sourceIndex.CaseIds.Contains($caseId)) {
        throw "Abstention quarantine case '$caseId' does not exist in source."
    }
    if ($sourceIndex.HasEvidenceById[$caseId]) {
        throw "Abstention quarantine case '$caseId' contains positive evidence."
    }
    if ((Get-LongMemEvalCategory $entry) -ne $sourceIndex.CategoryById[$caseId]) {
        throw "Abstention quarantine case '$caseId' category does not match source."
    }
}

foreach ($splitName in $splitNames) {
    $partition = $partitions[$splitName]
    $expectedCount = [int]$partition.Manifest.caseCount
    if ((($splitName -eq "dev" -or $splitName -eq "validation") -and
            $expectedCount -ne [int]$manifest.generationParameters.decisionSplitSize) -or
        $partition.Ids.Entries.Count -ne $expectedCount -or
        $partition.Dataset.Entries.Count -ne $expectedCount) {
        throw "'$splitName' case count does not match manifest allocation."
    }
    foreach ($entry in $partition.Ids.Entries) {
        $caseId = Get-LongMemEvalCaseId $entry
        if (-not $sourceIndex.CaseIds.Contains($caseId)) {
            throw "'$splitName' case '$caseId' does not exist in source."
        }
        if ((Get-LongMemEvalCategory $entry) -ne $sourceIndex.CategoryById[$caseId]) {
            throw "'$splitName' case '$caseId' category does not match source."
        }
        if (-not $sourceIndex.HasEvidenceById[$caseId]) {
            throw "Retrieval eligibility failure in '$splitName' case '$caseId': source has no positive evidence."
        }
    }
    $actualCategoryCounts = @(Get-LongMemEvalCategoryCounts $partition.Ids.Entries)
    if ((Get-CategoryCountSignature $actualCategoryCounts) -ne
        (Get-CategoryCountSignature @($partition.Manifest.categoryCounts))) {
        throw "'$splitName' category distribution does not match manifest."
    }
    Assert-SequenceEquals `
        -Expected $partition.Ids.OrderedIds `
        -Actual $partition.Dataset.OrderedIds `
        -Label "$splitName dataset/ID manifest"

    $namespaces = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    for ($i = 0; $i -lt $partition.Dataset.Entries.Count; $i++) {
        $case = $partition.Dataset.Entries[$i]
        $caseId = $partition.Dataset.OrderedIds[$i]
        if (@($case.expectedFragments).Count -eq 0) {
            throw "Retrieval eligibility failure in '$splitName' case '$caseId': expectedFragments is empty."
        }
        $expectedNamespace = "$($partition.Manifest.namespaceBase)-$caseId"
        if (-not ([string]$case.namespace).Equals(
                $expectedNamespace,
                [System.StringComparison]::Ordinal)) {
            throw "Namespace isolation failure in '$splitName' case '$caseId': expected '$expectedNamespace', actual '$($case.namespace)'."
        }
        if (-not $namespaces.Add([string]$case.namespace)) {
            throw "Namespace isolation failure in '$splitName': duplicate namespace '$($case.namespace)'."
        }
    }
    if ($namespaces.Count -ne $expectedCount -or
        [int]$partition.Manifest.namespaceCount -ne $expectedCount) {
        throw "Namespace isolation failure in '$splitName': expected $expectedCount unique namespaces, actual $($namespaces.Count)."
    }
}

$currentAudit = Invoke-LongMemEvalUsedCaseAudit `
    -AuditRoots @($manifest.audit.roots | ForEach-Object { [string]$_ }) `
    -SourceIndex $sourceIndex `
    -ExcludedPath ([string]$manifest.audit.excludedOutputDirectory)
$currentAuditSet = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase)
foreach ($caseId in $currentAudit.UsedIds) {
    [void]$currentAuditSet.Add($caseId)
}
$missingHistoricalIds = @($used.Ids |
    Where-Object { -not $currentAuditSet.Contains($_) } |
    Sort-Object)
if ($missingHistoricalIds.Count -gt 0) {
    throw "Historical audit overlap/drift detected: missing baseline IDs [$($missingHistoricalIds -join ', ')]."
}
$unauthorizedNewIds = @($currentAudit.UsedIds |
    Where-Object {
        -not $used.Ids.Contains($_) -and
        -not $partitions.dev.Ids.Ids.Contains($_)
    } |
    Sort-Object)
if ($unauthorizedNewIds.Count -gt 0) {
    throw ("Historical audit overlap/drift detected: unauthorized validation/reserve/quarantine " +
        "IDs [$($unauthorizedNewIds -join ', ')].")
}

$currentSourcesByPath = [System.Collections.Generic.Dictionary[string, object]]::new(
    [System.StringComparer]::OrdinalIgnoreCase)
foreach ($source in $currentAudit.MatchedSources) {
    $currentSourcesByPath[[string]$source.path] = $source
}
$baselineSourcePaths = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase)
foreach ($expectedSource in @($manifest.audit.matchedSources)) {
    $path = [string]$expectedSource.path
    [void]$baselineSourcePaths.Add($path)
    if (-not $currentSourcesByPath.ContainsKey($path)) {
        throw "Historical audit source drift: baseline source '$path' is missing."
    }
    $actualSource = $currentSourcesByPath[$path]
    if (-not ([string]$actualSource.sha256).Equals(
            [string]$expectedSource.sha256,
            [System.StringComparison]::OrdinalIgnoreCase) -or
        [int]$actualSource.matchedCaseCount -ne [int]$expectedSource.matchedCaseCount) {
        throw "Historical audit source path, count, or SHA-256 drift for '$path'."
    }
}
$authorizedDevAuditSourceCount = 0
foreach ($source in $currentAudit.MatchedSources) {
    $path = [string]$source.path
    if ($baselineSourcePaths.Contains($path)) {
        continue
    }
    $sourceIds = @($currentAudit.UsedIds | Where-Object {
        $currentAudit.CaseSources.ContainsKey($_) -and
        $currentAudit.CaseSources[$_] -contains $path
    })
    $sourceUnauthorizedIds = @($sourceIds | Where-Object {
        -not $partitions.dev.Ids.Ids.Contains($_)
    })
    if ($sourceUnauthorizedIds.Count -gt 0) {
        throw "Historical audit source '$path' contains unauthorized partition IDs."
    }
    $authorizedDevAuditSourceCount++
}

Assert-Hash `
    -Path $usedPath `
    -Expected ([string]$manifest.audit.usedCaseIdsSha256) `
    -Label "used case manifest"
Assert-Hash `
    -Path $quarantinePath `
    -Expected ([string]$manifest.abstentionQuarantine.caseIdsSha256) `
    -Label "abstention quarantine"
foreach ($splitName in $splitNames) {
    $partition = $partitions[$splitName]
    Assert-Hash `
        -Path $partition.IdPath `
        -Expected ([string]$partition.Manifest.caseIdsSha256) `
        -Label "$splitName case ID manifest"
    Assert-Hash `
        -Path $partition.DatasetPath `
        -Expected ([string]$partition.Manifest.datasetSha256) `
        -Label "$splitName dataset"
}
$manifestConverterPath = [string]$manifest.generationParameters.converterPath
$expectedConverterRawSha256 = [string]$manifest.generationParameters.converterSha256
$actualConverterRawSha256 = if (Test-Path -LiteralPath $manifestConverterPath -PathType Leaf) {
    Get-LongMemEvalFileSha256 $manifestConverterPath
} else {
    "MISSING"
}
$converterProvenance = if ($actualConverterRawSha256.Equals(
        $expectedConverterRawSha256,
        [System.StringComparison]::OrdinalIgnoreCase)) {
    [pscustomobject][ordered]@{
        Mode = "EXACT_RAW_SHA256"
        ConverterCanonicalSha256 = Get-CanonicalTextSha256 $manifestConverterPath
        ReconciliationPath = ""
        ReconciliationSha256 = ""
    }
} else {
    if ([string]::IsNullOrWhiteSpace($ReconciliationPath)) {
        throw ("SHA-256 drift for converter script '$manifestConverterPath': expected " +
            "$expectedConverterRawSha256, actual $actualConverterRawSha256. " +
            "An explicit -ReconciliationPath is required; raw hash drift is never accepted implicitly.")
    }
    Assert-ConverterReconciliation `
        -Manifest $manifest `
        -ManifestSha256 $manifestSha256 `
        -Partitions $partitions `
        -SplitNames $splitNames `
        -EffectiveSourcePath $sourceIndex.Path `
        -RecordPath $ReconciliationPath
}
Assert-Hash `
    -Path (Join-Path $PSScriptRoot "prepare-longmemeval-reranker-splits.ps1") `
    -Expected ([string]$manifest.generationParameters.generatorSha256) `
    -Label "generator script"
Assert-Hash `
    -Path (Join-Path $PSScriptRoot "longmemeval-split-common.ps1") `
    -Expected ([string]$manifest.generationParameters.commonLibrarySha256) `
    -Label "common library"

[pscustomobject][ordered]@{
    Valid = $true
    SourceCases = $sourceIndex.Records.Count
    UsedCases = $used.Entries.Count
    DevCases = $partitions.dev.Ids.Entries.Count
    ValidationCases = $partitions.validation.Ids.Entries.Count
    ReserveCases = $partitions.reserve.Ids.Entries.Count
    AbstentionQuarantineCases = $quarantine.Entries.Count
    NamespaceIsolation = "PASS"
    PairwiseOverlap = "PASS"
    SourceCoverage = "PASS"
    HistoricalAudit = "PASS"
    AuthorizedDevAuditSources = $authorizedDevAuditSourceCount
    HashDrift = "PASS"
    ConverterProvenance = $converterProvenance.Mode
    ConverterCanonicalSha256 = $converterProvenance.ConverterCanonicalSha256
    ReconciliationPath = $converterProvenance.ReconciliationPath
    ReconciliationSha256 = $converterProvenance.ReconciliationSha256
    ManifestSha256 = $manifestSha256
}
