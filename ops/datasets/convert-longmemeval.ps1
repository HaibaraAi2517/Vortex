param(
    [Parameter(Mandatory = $true)]
    [string]$InputPath,

    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [int]$Limit = 0,

    [int]$PerCategoryLimit = 0,

    [string]$IncludeCaseIdsFrom = "",

    [string]$ExcludeCaseIdsFrom = "",

    [string]$Namespace = "longmemeval-public",

    [switch]$NamespacePerCategory,

    [switch]$NamespacePerCase,

    [switch]$AllowOpenEndedAnswers
)

$ErrorActionPreference = "Stop"

function Read-JsonRecords {
    param([string]$Path)
    $raw = Get-Content -LiteralPath $Path -Raw
    $trimmed = $raw.Trim()
    if ($trimmed.StartsWith("[")) {
        $parsed = $trimmed | ConvertFrom-Json
        foreach ($record in @($parsed)) {
            $record
        }
        return
    }
    $records = New-Object System.Collections.Generic.List[object]
    Get-Content -LiteralPath $Path | ForEach-Object {
        $line = $_.Trim()
        if (-not [string]::IsNullOrWhiteSpace($line)) {
            $records.Add(($line | ConvertFrom-Json))
        }
    }
    return $records.ToArray()
}
function Get-FirstPropertyValue {
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

function Convert-ToText {
    param([object]$Value)
    if ($null -eq $Value) {
        return ""
    }
    if ($Value -is [string]) {
        return $Value.Trim()
    }
    if ($Value.PSObject.Properties.Name -contains "content") {
        return [string]$Value.content
    }
    if ($Value.PSObject.Properties.Name -contains "text") {
        return [string]$Value.text
    }
    if (($Value.PSObject.Properties.Name -contains "role") -and ($Value.PSObject.Properties.Name -contains "content")) {
        return ("{0}: {1}" -f $Value.role, $Value.content).Trim()
    }
    return ($Value | ConvertTo-Json -Depth 20 -Compress)
}

function Convert-MessagesToFragments {
    param(
        [object[]]$Messages,
        [string]$CaseId,
        [string[]]$BaseTags = @()
    )
    $fragments = New-Object System.Collections.Generic.List[object]
    $index = 0
    foreach ($message in @($Messages)) {
        $text = Convert-ToText $message
        if ([string]::IsNullOrWhiteSpace($text)) {
            continue
        }
        $index++
        $role = Get-FirstPropertyValue $message @("role", "speaker", "author")
        $hasAnswer = $false
        $hasAnswerValue = Get-FirstPropertyValue $message @("has_answer", "hasAnswer", "is_evidence", "isEvidence")
        if ($null -ne $hasAnswerValue) {
            $hasAnswer = [System.Convert]::ToBoolean($hasAnswerValue)
        }
        $tags = @($BaseTags)
        if (-not [string]::IsNullOrWhiteSpace([string]$role)) {
            $tags += ("role-" + ([string]$role).ToLowerInvariant())
        }
        if ($hasAnswer) {
            $tags += "longmemeval-evidence"
        }
        $fragments.Add([pscustomobject][ordered]@{
            fragmentId = ("{0}-turn-{1:0000}" -f $CaseId, $index)
            content = $text
            tags = $tags
            hasAnswer = $hasAnswer
        })
    }
    return $fragments.ToArray()
}

function Convert-RecordToCase {
    param(
        [object]$Record,
        [int]$Index,
        [string]$Namespace,
        [bool]$AllowOpenEndedAnswers
    )
    $caseId = Get-FirstPropertyValue $Record @("id", "question_id", "sample_id", "qid", "uuid")
    if ([string]::IsNullOrWhiteSpace([string]$caseId)) {
        $caseId = "longmemeval-{0:00000}" -f $Index
    }
    $caseId = ([string]$caseId).Trim() -replace "[^A-Za-z0-9_.-]", "-"

    $question = Get-FirstPropertyValue $Record @("question", "query", "input", "prompt")
    $answer = Get-FirstPropertyValue $Record @("answer", "target", "gold_answer", "reference_answer", "expected_answer", "final_answer")
    if ($answer -is [array]) {
        $answer = ($answer | Select-Object -First 1)
    }
    if ($answer -and -not ($answer -is [string])) {
        $answer = Convert-ToText $answer
    }

    $messages = Get-FirstPropertyValue $Record @("haystack_sessions", "haystack", "sessions", "conversation", "conversations", "messages", "context", "contexts", "memory", "memories")
    if ($null -eq $messages) {
        throw "Record $caseId has no recognizable LongMemEval context/messages field."
    }
    if ($messages -is [string]) {
        $messages = @($messages)
    }

    $flattened = New-Object System.Collections.Generic.List[object]
    foreach ($item in @($messages)) {
        if ($item -is [array]) {
            foreach ($nested in @($item)) { $flattened.Add($nested) }
            continue
        }
        if ($item.PSObject.Properties.Name -contains "messages") {
            foreach ($nested in @($item.messages)) { $flattened.Add($nested) }
            continue
        }
        if ($item.PSObject.Properties.Name -contains "turns") {
            foreach ($nested in @($item.turns)) { $flattened.Add($nested) }
            continue
        }
        $flattened.Add($item)
    }

    $category = Get-FirstPropertyValue $Record @("category", "question_type", "type")
    $difficulty = Get-FirstPropertyValue $Record @("difficulty", "level")
    $tags = @("longmemeval", "public-dataset")
    if (-not [string]::IsNullOrWhiteSpace([string]$category)) {
        $tags += ("category-" + ([string]$category).ToLowerInvariant().Replace(" ", "-"))
    }

    $fragments = Convert-MessagesToFragments -Messages $flattened.ToArray() -CaseId $caseId -BaseTags $tags
    if ($fragments.Count -eq 0) {
        throw "Record $caseId produced zero memory fragments."
    }
    if ([string]::IsNullOrWhiteSpace([string]$question)) {
        throw "Record $caseId has no recognizable question field."
    }
    if ([string]::IsNullOrWhiteSpace([string]$answer)) {
        if (-not $AllowOpenEndedAnswers) {
            throw "Record $caseId has no recognizable answer field. Pass -AllowOpenEndedAnswers to emit empty expected answers."
        }
        $answer = "open-ended"
    }

    $expectedFragments = @($fragments | Where-Object { $_.hasAnswer } | Select-Object -ExpandProperty fragmentId)
    $memoryFragments = @($fragments | ForEach-Object {
        [pscustomobject][ordered]@{
            fragmentId = $_.fragmentId
            content = $_.content
            tags = $_.tags
        }
    })

    return [ordered]@{
        caseId = $caseId
        namespace = $Namespace
        memoryFragments = $memoryFragments
        question = ([string]$question).Trim()
        expectedAnswer = ([string]$answer).Trim()
        mustContain = @(([string]$answer).Trim())
        mustNotContain = @()
        expectedFragments = $expectedFragments
        failureCategories = @($category)
        tags = $tags
        difficulty = if ([string]::IsNullOrWhiteSpace([string]$difficulty)) { "public" } else { [string]$difficulty }
    }
}

$inputFullPath = (Resolve-Path -LiteralPath $InputPath).Path
$records = @(Read-JsonRecords -Path $inputFullPath)
if ($records.Count -eq 0) {
    throw "No records found in $InputPath"
}
if ($Limit -gt 0 -and $PerCategoryLimit -gt 0) {
    throw "Limit and PerCategoryLimit are mutually exclusive."
}
if (-not [string]::IsNullOrWhiteSpace($IncludeCaseIdsFrom) -and
    ($Limit -gt 0 -or $PerCategoryLimit -gt 0)) {
    throw "IncludeCaseIdsFrom cannot be combined with Limit or PerCategoryLimit."
}
if ($NamespacePerCategory.IsPresent -and $NamespacePerCase.IsPresent) {
    throw "NamespacePerCategory and NamespacePerCase are mutually exclusive."
}

$includedCaseIds = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase)
$includedCaseIdOrder = [System.Collections.Generic.List[string]]::new()
if (-not [string]::IsNullOrWhiteSpace($IncludeCaseIdsFrom)) {
    $includePath = (Resolve-Path -LiteralPath $IncludeCaseIdsFrom).Path
    foreach ($includedCase in @(Read-JsonRecords -Path $includePath)) {
        $includedId = Get-FirstPropertyValue $includedCase @("caseId", "id", "question_id")
        if ([string]::IsNullOrWhiteSpace([string]$includedId)) {
            throw "Include file '$includePath' contains an entry without caseId, id, or question_id."
        }
        $includedId = ([string]$includedId).Trim()
        if (-not $includedCaseIds.Add($includedId)) {
            throw "Include file '$includePath' contains duplicate case ID '$includedId'."
        }
        $includedCaseIdOrder.Add($includedId)
    }
    if ($includedCaseIdOrder.Count -eq 0) {
        throw "Include file '$includePath' contains zero case IDs."
    }

    $recordsById = [System.Collections.Generic.Dictionary[string, object]]::new(
        [System.StringComparer]::OrdinalIgnoreCase)
    foreach ($record in $records) {
        $recordId = Get-FirstPropertyValue $record @("id", "question_id", "sample_id", "qid", "uuid")
        if ([string]::IsNullOrWhiteSpace([string]$recordId)) {
            continue
        }
        $recordId = ([string]$recordId).Trim()
        if ($includedCaseIds.Contains($recordId)) {
            if ($recordsById.ContainsKey($recordId)) {
                throw "Input contains duplicate requested case ID '$recordId'."
            }
            $recordsById.Add($recordId, $record)
        }
    }

    $selectedByInclude = [System.Collections.Generic.List[object]]::new()
    foreach ($includedId in $includedCaseIdOrder) {
        if (-not $recordsById.ContainsKey($includedId)) {
            throw "Included case ID '$includedId' was not found in '$inputFullPath'."
        }
        $selectedByInclude.Add($recordsById[$includedId])
    }
    $records = $selectedByInclude.ToArray()
}

$excludedCaseIds = [System.Collections.Generic.HashSet[string]]::new(
    [System.StringComparer]::OrdinalIgnoreCase)
if (-not [string]::IsNullOrWhiteSpace($ExcludeCaseIdsFrom)) {
    $excludePath = (Resolve-Path -LiteralPath $ExcludeCaseIdsFrom).Path
    foreach ($excludedCase in @(Read-JsonRecords -Path $excludePath)) {
        $excludedId = Get-FirstPropertyValue $excludedCase @("caseId", "id", "question_id")
        if (-not [string]::IsNullOrWhiteSpace([string]$excludedId)) {
            $excludedId = ([string]$excludedId).Trim()
            if ($includedCaseIds.Contains($excludedId)) {
                throw "Case ID '$excludedId' is present in both include and exclude files."
            }
            [void]$excludedCaseIds.Add($excludedId)
        }
    }
    $records = @($records | Where-Object {
        $recordId = Get-FirstPropertyValue $_ @("id", "question_id", "sample_id", "qid", "uuid")
        [string]::IsNullOrWhiteSpace([string]$recordId) -or
            -not $excludedCaseIds.Contains(([string]$recordId).Trim())
    })
}

if ($PerCategoryLimit -gt 0) {
    $selectedRecords = New-Object System.Collections.Generic.List[object]
    $groups = @($records | Group-Object {
        [string](Get-FirstPropertyValue $_ @("category", "question_type", "type"))
    } | Sort-Object Name)
    foreach ($group in $groups) {
        if ($group.Count -lt $PerCategoryLimit) {
            throw "Category '$($group.Name)' has only $($group.Count) records after exclusions; requested $PerCategoryLimit."
        }
        foreach ($record in @($group.Group | Select-Object -First $PerCategoryLimit)) {
            $selectedRecords.Add($record)
        }
    }
    $records = $selectedRecords.ToArray()
} elseif ($Limit -gt 0) {
    $records = @($records | Select-Object -First $Limit)
}

$cases = New-Object System.Collections.Generic.List[object]
for ($i = 0; $i -lt $records.Count; $i++) {
    $caseNamespace = $Namespace
    if ($NamespacePerCategory.IsPresent) {
        $category = [string](Get-FirstPropertyValue $records[$i] @("category", "question_type", "type"))
        if ([string]::IsNullOrWhiteSpace($category)) {
            throw "NamespacePerCategory requires every selected record to define a category."
        }
        $categorySlug = $category.Trim().ToLowerInvariant() -replace "[^a-z0-9]+", "-"
        $caseNamespace = "$Namespace-$categorySlug".TrimEnd("-")
    }
    $convertedCase = Convert-RecordToCase -Record $records[$i] -Index ($i + 1) -Namespace $caseNamespace -AllowOpenEndedAnswers:$AllowOpenEndedAnswers.IsPresent
    if ($NamespacePerCase.IsPresent) {
        $convertedCase.namespace = "$Namespace-$($convertedCase.caseId)"
    }
    $cases.Add($convertedCase)
}

if ([System.IO.Path]::IsPathRooted($OutputPath)) {
    $outputFullPath = [System.IO.Path]::GetFullPath($OutputPath)
} else {
    $outputFullPath = [System.IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputPath))
}
$outputDir = Split-Path -Parent $outputFullPath
if (-not [string]::IsNullOrWhiteSpace($outputDir)) {
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null
}
$outputJson = $cases | ConvertTo-Json -Depth 100
$outputJson = ($outputJson -replace "`r`n", "`n") + "`n"
[System.IO.File]::WriteAllText(
    $outputFullPath,
    $outputJson,
    [System.Text.UTF8Encoding]::new($false))

$fragmentCount = ($cases | ForEach-Object { $_.memoryFragments.Count } | Measure-Object -Sum).Sum
$categoryCounts = @($cases |
    ForEach-Object { $_.failureCategories | Select-Object -First 1 } |
    Group-Object |
    Sort-Object Name |
    ForEach-Object { "$($_.Name)=$($_.Count)" })
[pscustomobject]@{
    InputPath = $inputFullPath
    OutputPath = $outputFullPath
    Cases = $cases.Count
    MemoryFragments = $fragmentCount
    Namespace = $Namespace
    NamespaceCount = @($cases.namespace | Sort-Object -Unique).Count
    IncludedCaseIds = $includedCaseIds.Count
    ExcludedCaseIds = $excludedCaseIds.Count
    CategoryCounts = ($categoryCounts -join ",")
}
