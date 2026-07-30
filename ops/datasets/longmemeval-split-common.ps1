$ErrorActionPreference = "Stop"

function Get-LongMemEvalFileSha256 {
    param([Parameter(Mandatory = $true)][string]$Path)

    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Get-LongMemEvalStringSha256 {
    param([Parameter(Mandatory = $true)][string]$Value)

    $algorithm = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
        $hash = $algorithm.ComputeHash($bytes)
        return ([System.BitConverter]::ToString($hash) -replace "-", "").ToLowerInvariant()
    } finally {
        $algorithm.Dispose()
    }
}

function Write-LongMemEvalJson {
    param(
        [Parameter(Mandatory = $true)][object]$Value,
        [Parameter(Mandatory = $true)][string]$Path,
        [int]$Depth = 100
    )

    $fullPath = [System.IO.Path]::GetFullPath($Path)
    $directory = Split-Path -Parent $fullPath
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }
    $json = $Value | ConvertTo-Json -Depth $Depth
    $json = ($json -replace "`r`n", "`n") + "`n"
    [System.IO.File]::WriteAllText(
        $fullPath,
        $json,
        [System.Text.UTF8Encoding]::new($false))
}

function Read-LongMemEvalJsonRecords {
    param([Parameter(Mandatory = $true)][string]$Path)

    $raw = [System.IO.File]::ReadAllText((Resolve-Path -LiteralPath $Path).Path)
    $trimmed = $raw.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed)) {
        throw "JSON file '$Path' is empty."
    }
    if ($trimmed.StartsWith("[") -or $trimmed.StartsWith("{")) {
        $parsed = $trimmed | ConvertFrom-Json
        foreach ($record in @($parsed)) {
            $record
        }
        return
    }
    foreach ($lineValue in ($raw -split "`r?`n")) {
        $line = $lineValue.Trim()
        if (-not [string]::IsNullOrWhiteSpace($line)) {
            $line | ConvertFrom-Json
        }
    }
}

function Get-LongMemEvalProperty {
    param(
        [object]$Object,
        [string[]]$Names
    )

    if ($null -eq $Object) {
        return $null
    }
    foreach ($name in $Names) {
        if ($Object.PSObject.Properties.Name -contains $name) {
            $value = $Object.$name
            if ($null -ne $value) {
                return $value
            }
        }
    }
    return $null
}

function Get-LongMemEvalCaseId {
    param([object]$Record)

    $value = Get-LongMemEvalProperty $Record @(
        "caseId", "id", "question_id", "sample_id", "qid", "uuid")
    if ([string]::IsNullOrWhiteSpace([string]$value)) {
        return ""
    }
    return ([string]$value).Trim()
}

function Get-LongMemEvalCategory {
    param([object]$Record)

    $value = Get-LongMemEvalProperty $Record @("category", "question_type", "type")
    if ([string]::IsNullOrWhiteSpace([string]$value)) {
        return ""
    }
    return ([string]$value).Trim()
}

function Test-LongMemEvalEvidenceNode {
    param([object]$Node)

    if ($null -eq $Node -or $Node -is [string] -or $Node -is [System.ValueType]) {
        return $false
    }
    if ($Node -is [System.Collections.IDictionary]) {
        foreach ($key in $Node.Keys) {
            $value = $Node[$key]
            if ($key -in @("has_answer", "hasAnswer", "is_evidence", "isEvidence")) {
                try {
                    if ([System.Convert]::ToBoolean($value)) {
                        return $true
                    }
                } catch {
                    throw "Evidence flag '$key' has invalid value '$value'."
                }
            }
            if (Test-LongMemEvalEvidenceNode -Node $value) {
                return $true
            }
        }
        return $false
    }
    if ($Node -is [System.Collections.IEnumerable]) {
        foreach ($item in $Node) {
            if (Test-LongMemEvalEvidenceNode -Node $item) {
                return $true
            }
        }
        return $false
    }
    foreach ($property in @($Node.PSObject.Properties)) {
        $value = $property.Value
        if ($property.Name -in @("has_answer", "hasAnswer", "is_evidence", "isEvidence")) {
            try {
                if ([System.Convert]::ToBoolean($value)) {
                    return $true
                }
            } catch {
                throw "Evidence flag '$($property.Name)' has invalid value '$value'."
            }
        }
        if (Test-LongMemEvalEvidenceNode -Node $value) {
            return $true
        }
    }
    return $false
}

function Test-LongMemEvalRecordHasEvidence {
    param([Parameter(Mandatory = $true)][object]$Record)

    return Test-LongMemEvalEvidenceNode -Node $Record
}

function ConvertTo-LongMemEvalNormalizedPath {
    param([Parameter(Mandatory = $true)][string]$Path)

    return ([System.IO.Path]::GetFullPath($Path) -replace "\\", "/")
}

function Test-LongMemEvalPathWithin {
    param(
        [Parameter(Mandatory = $true)][string]$CandidatePath,
        [Parameter(Mandatory = $true)][string]$RootPath
    )

    $candidate = [System.IO.Path]::GetFullPath($CandidatePath).TrimEnd("\", "/")
    $root = [System.IO.Path]::GetFullPath($RootPath).TrimEnd("\", "/")
    if ($candidate.Equals($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    $rootPrefix = $root + [System.IO.Path]::DirectorySeparatorChar
    return $candidate.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)
}

function Get-LongMemEvalCategoryCounts {
    param([Parameter(Mandatory = $true)][object[]]$Entries)

    return @($Entries |
        Group-Object { [string]$_.category } |
        Sort-Object Name |
        ForEach-Object {
            [pscustomobject][ordered]@{
                category = $_.Name
                count = $_.Count
            }
        })
}

function Get-LongMemEvalSourceIndex {
    param([Parameter(Mandatory = $true)][string]$SourcePath)

    $sourceFullPath = (Resolve-Path -LiteralPath $SourcePath).Path
    $records = @(Read-LongMemEvalJsonRecords -Path $sourceFullPath)
    if ($records.Count -eq 0) {
        throw "LongMemEval source '$sourceFullPath' contains zero records."
    }

    $caseIds = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    $byId = [System.Collections.Generic.Dictionary[string, object]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    $categoryById = [System.Collections.Generic.Dictionary[string, string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    $hasEvidenceById = [System.Collections.Generic.Dictionary[string, bool]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    $evidenceCaseIds = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    $entries = [System.Collections.Generic.List[object]]::new()
    foreach ($record in $records) {
        $caseId = Get-LongMemEvalCaseId $record
        $category = Get-LongMemEvalCategory $record
        $hasEvidence = Test-LongMemEvalRecordHasEvidence $record
        if ([string]::IsNullOrWhiteSpace($caseId)) {
            throw "LongMemEval source '$sourceFullPath' contains a record without a case ID."
        }
        if ([string]::IsNullOrWhiteSpace($category)) {
            throw "LongMemEval source case '$caseId' has no category."
        }
        if (-not $caseIds.Add($caseId)) {
            throw "LongMemEval source contains duplicate case ID '$caseId'."
        }
        $byId.Add($caseId, $record)
        $categoryById.Add($caseId, $category)
        $hasEvidenceById.Add($caseId, $hasEvidence)
        if ($hasEvidence) {
            [void]$evidenceCaseIds.Add($caseId)
        }
        $entries.Add([pscustomobject]@{
            caseId = $caseId
            category = $category
            hasEvidence = $hasEvidence
        })
    }

    return [pscustomobject]@{
        Path = $sourceFullPath
        Sha256 = Get-LongMemEvalFileSha256 $sourceFullPath
        Records = $records
        CaseIds = $caseIds
        EvidenceCaseIds = $evidenceCaseIds
        ById = $byId
        CategoryById = $categoryById
        HasEvidenceById = $hasEvidenceById
        CategoryCounts = @(Get-LongMemEvalCategoryCounts $entries.ToArray())
        EvidenceCaseCount = $evidenceCaseIds.Count
        EvidenceCategoryCounts = @(Get-LongMemEvalCategoryCounts @(
            $entries | Where-Object hasEvidence))
    }
}

function Add-LongMemEvalMatchingIds {
    param(
        [object]$Node,
        [System.Collections.Generic.HashSet[string]]$SourceCaseIds,
        [System.Collections.Generic.HashSet[string]]$Matches
    )

    if ($null -eq $Node -or $Node -is [string] -or $Node -is [System.ValueType]) {
        return
    }
    if ($Node -is [System.Collections.IDictionary]) {
        foreach ($key in $Node.Keys) {
            $value = $Node[$key]
            if (($key -eq "caseId" -or $key -eq "question_id") -and
                $value -is [string] -and $SourceCaseIds.Contains($value.Trim())) {
                [void]$Matches.Add($value.Trim())
            }
            Add-LongMemEvalMatchingIds -Node $value -SourceCaseIds $SourceCaseIds -Matches $Matches
        }
        return
    }
    if ($Node -is [System.Collections.IEnumerable]) {
        foreach ($item in $Node) {
            Add-LongMemEvalMatchingIds -Node $item -SourceCaseIds $SourceCaseIds -Matches $Matches
        }
        return
    }
    foreach ($property in @($Node.PSObject.Properties)) {
        $value = $property.Value
        if (($property.Name -eq "caseId" -or $property.Name -eq "question_id") -and
            $value -is [string] -and $SourceCaseIds.Contains($value.Trim())) {
            [void]$Matches.Add($value.Trim())
        }
        Add-LongMemEvalMatchingIds -Node $value -SourceCaseIds $SourceCaseIds -Matches $Matches
    }
}

function Invoke-LongMemEvalUsedCaseAudit {
    param(
        [Parameter(Mandatory = $true)][string[]]$AuditRoots,
        [Parameter(Mandatory = $true)][object]$SourceIndex,
        [string]$ExcludedPath = ""
    )

    $resolvedRoots = [System.Collections.Generic.List[string]]::new()
    $filesByPath = [System.Collections.Generic.Dictionary[string, object]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    foreach ($root in $AuditRoots) {
        $resolvedRoot = (Resolve-Path -LiteralPath $root).Path
        $resolvedRoots.Add($resolvedRoot)
        foreach ($file in @(Get-ChildItem -LiteralPath $resolvedRoot -Recurse -File -Filter "*.json")) {
            if (-not [string]::IsNullOrWhiteSpace($ExcludedPath) -and
                (Test-LongMemEvalPathWithin -CandidatePath $file.FullName -RootPath $ExcludedPath)) {
                continue
            }
            if (-not $filesByPath.ContainsKey($file.FullName)) {
                $filesByPath.Add($file.FullName, $file)
            }
        }
    }

    $usedIds = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    $caseSources = [System.Collections.Generic.Dictionary[string, object]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    $matchedSources = [System.Collections.Generic.List[object]]::new()
    foreach ($file in @($filesByPath.Values | Sort-Object FullName)) {
        $matches = [System.Collections.Generic.HashSet[string]]::new(
            [System.StringComparer]::OrdinalIgnoreCase)
        foreach ($document in @(Read-LongMemEvalJsonRecords -Path $file.FullName)) {
            Add-LongMemEvalMatchingIds -Node $document -SourceCaseIds $SourceIndex.CaseIds -Matches $matches
        }
        if ($matches.Count -eq 0) {
            continue
        }
        $normalizedPath = ConvertTo-LongMemEvalNormalizedPath $file.FullName
        $matchedSources.Add([pscustomobject][ordered]@{
            path = $normalizedPath
            sha256 = Get-LongMemEvalFileSha256 $file.FullName
            matchedCaseCount = $matches.Count
        })
        foreach ($caseId in @($matches | Sort-Object)) {
            [void]$usedIds.Add($caseId)
            if (-not $caseSources.ContainsKey($caseId)) {
                $caseSources.Add($caseId, [System.Collections.Generic.List[string]]::new())
            }
            $caseSources[$caseId].Add($normalizedPath)
        }
    }

    return [pscustomobject]@{
        Roots = @($resolvedRoots | ForEach-Object { ConvertTo-LongMemEvalNormalizedPath $_ })
        UsedIds = @($usedIds | Sort-Object)
        CaseSources = $caseSources
        MatchedSources = @($matchedSources | Sort-Object path)
    }
}
