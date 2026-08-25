param(
    [Parameter(Mandatory = $true)]
    [string]$ApiKey,

    [string]$BaseUrl = "https://sub2.congmingai.com",

    [string]$Model = "gpt-5.2",

    [ValidateRange(1, 10)]
    [int]$Rounds = 5,

    [string]$AuditStamp = "",

    [string]$DatasetLocation = "classpath:llm-memory-eval-set-v2.json",

    [string]$BaselineProfile = "",

    [string]$StrictVerifierProfile = "",

    [string]$BgeModelPath = "E:/1projects/claude/Vortex/models/bge-small-zh",

    [int]$L1MaxTokens = 96,

    [string]$Modes = "BASELINE_NO_MEMORY,VORTEX_MEMORY,VORTEX_RECOVERED_MEMORY",

    [ValidateRange(1, 128)]
    [int]$EvalParallelism = 1,

    [string]$ReportRoot = "ops/eval-reports",

    [switch]$SkipComposeUp,

    [switch]$SkipPackage,

    [switch]$SkipGenerationPreflight,

    [switch]$ForceRerunExisting,

    [ValidateRange(0, 50)]
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

function Get-NumericValues {
    param(
        [object]$Items,
        [string]$PropertyName
    )
    return @($Items | ForEach-Object {
        if ($null -eq $_) {
            return
        }
        $property = $_.PSObject.Properties[$PropertyName]
        if ($null -ne $property -and $null -ne $property.Value) {
            [double]$property.Value
        }
    })
}

function Get-MinValue {
    param($Values)
    $items = @($Values)
    if ($items.Count -eq 0) {
        return $null
    }
    return ($items | Measure-Object -Minimum).Minimum
}

function Get-MaxValue {
    param($Values)
    $items = @($Values)
    if ($items.Count -eq 0) {
        return $null
    }
    return ($items | Measure-Object -Maximum).Maximum
}

function New-VerifyResult {
    param(
        [int]$ExitCode,
        [string]$Output,
        [string]$Profile = "",
        [bool]$Skipped = $false
    )
    [pscustomobject]@{
        Profile = $Profile
        Skipped = $Skipped
        Passed = (-not $Skipped -and $ExitCode -eq 0)
        ExitCode = $ExitCode
        Output = $Output.Trim()
    }
}

function Get-DatasetVersion {
    param([string]$Location)
    if ($Location -eq "classpath:llm-memory-eval-set-v3-1-real-agent-workload.json") {
        return "v3.1-real-agent-workload"
    }
    if ($Location -eq "classpath:llm-memory-eval-set-v3-real-agent-workload.json") {
        return "v3-real-agent-workload"
    }
    if ($Location -eq "classpath:llm-memory-eval-set-v2-1-extended.json") {
        return "v2.1-extended"
    }
    if ($Location -eq "classpath:llm-memory-eval-set-v2-1.json") {
        return "v2.1"
    }
    if ($Location -eq "classpath:llm-memory-eval-set-v2.json") {
        return "v2"
    }
    if ($Location -eq "classpath:llm-memory-eval-set.json") {
        return "v1"
    }
    return "custom"
}

function Get-AuditBaselineProfile {
    param([string]$Location)
    if ($Location -eq "classpath:llm-memory-eval-set-v3-1-real-agent-workload.json") {
        return "official-v3.1-real-agent-workload-strict"
    }
    if ($Location -eq "classpath:llm-memory-eval-set-v3-real-agent-workload.json") {
        return "official-v3-real-agent-workload-strict"
    }
    if ($Location -eq "classpath:llm-memory-eval-set-v2-1-extended.json") {
        return "official-v2.1-extended-strict"
    }
    if ($Location -eq "classpath:llm-memory-eval-set-v2-1.json") {
        return "official-v2.1-strict"
    }
    if ($Location -eq "classpath:llm-memory-eval-set-v2.json") {
        return "audit-v2-stability"
    }
    return "custom"
}

function Get-StrictVerifierProfile {
    param([string]$Location)
    if ($Location -eq "classpath:llm-memory-eval-set-v3-1-real-agent-workload.json") {
        return "official-v3.1-real-agent-workload-strict"
    }
    if ($Location -eq "classpath:llm-memory-eval-set-v3-real-agent-workload.json") {
        return "official-v3-real-agent-workload-strict"
    }
    if ($Location -eq "classpath:llm-memory-eval-set-v2-1-extended.json") {
        return "official-v2.1-extended-strict"
    }
    if ($Location -eq "classpath:llm-memory-eval-set-v2-1.json") {
        return "official-v2.1-strict"
    }
    if ($Location -eq "classpath:llm-memory-eval-set-v2.json") {
        return "official-v2-strict"
    }
    return ""
}

function Get-BaselineIdForProfile {
    param([string]$Profile)
    if ($Profile -eq "official-v3.1-real-agent-workload-strict") {
        return "20260603-v3-1-real-agent-workload-candidate-audit-003"
    }
    if ($Profile -eq "candidate-v3.1-real-agent-workload") {
        return "candidate-v3.1-real-agent-workload"
    }
    if ($Profile -eq "official-v3-real-agent-workload-strict") {
        return "20260603-v3-real-agent-workload-audit-002"
    }
    if ($Profile -eq "audit-v3-real-agent-workload") {
        return "candidate-v3-real-agent-workload"
    }
    if ($Profile -eq "official-v2.1-extended-strict") {
        return "20260602-v2-1-extended-candidate-audit-generation-retry-001"
    }
    if ($Profile -eq "candidate-v2.1-extended") {
        return "candidate-v2.1-extended"
    }
    if ($Profile -eq "official-v2.1-strict") {
        return "20260601-v2-009-contract-audit-5x-net"
    }
    if ($Profile -eq "contract-v2.1-candidate") {
        return "20260601-v2-009-contract-audit-5x-net"
    }
    if ($Profile -eq "audit-v2-stability") {
        return "20260601-mode-scoped-l2-wait-audit-5x-net"
    }
    if ($Profile -eq "official-v2-strict") {
        return "20260529-real-bge-v2-006"
    }
    return $Profile
}

function Get-BaselineProfileDefinition {
    param([string]$Profile)
    $normalized = if ([string]::IsNullOrWhiteSpace($Profile)) { "" } else { $Profile.Trim().ToLowerInvariant() }
    if ($normalized -eq "official-v2-strict") {
        return [pscustomobject]@{
            Id = "official-v2-strict"
            BaselineId = "20260529-real-bge-v2-006"
            DatasetVersion = "v2"
            DatasetLocation = "classpath:llm-memory-eval-set-v2.json"
            StrictReportProfile = $true
        }
    }
    if ($normalized -eq "audit-v2-stability") {
        return [pscustomobject]@{
            Id = "audit-v2-stability"
            BaselineId = "20260601-mode-scoped-l2-wait-audit-5x-net"
            DatasetVersion = "v2"
            DatasetLocation = "classpath:llm-memory-eval-set-v2.json"
            StrictReportProfile = $false
        }
    }
    if ($normalized -eq "official-v2.1-strict") {
        return [pscustomobject]@{
            Id = "official-v2.1-strict"
            BaselineId = "20260601-v2-009-contract-audit-5x-net"
            DatasetVersion = "v2.1"
            DatasetLocation = "classpath:llm-memory-eval-set-v2-1.json"
            StrictReportProfile = $true
        }
    }
    if ($normalized -eq "contract-v2.1-candidate") {
        return [pscustomobject]@{
            Id = "contract-v2.1-candidate"
            BaselineId = "20260601-v2-009-contract-audit-5x-net"
            DatasetVersion = "v2.1"
            DatasetLocation = "classpath:llm-memory-eval-set-v2-1.json"
            StrictReportProfile = $true
        }
    }
    if ($normalized -eq "official-v2.1-extended-strict") {
        return [pscustomobject]@{
            Id = "official-v2.1-extended-strict"
            BaselineId = "20260602-v2-1-extended-candidate-audit-generation-retry-001"
            DatasetVersion = "v2.1-extended"
            DatasetLocation = "classpath:llm-memory-eval-set-v2-1-extended.json"
            StrictReportProfile = $true
        }
    }
    if ($normalized -eq "candidate-v2.1-extended") {
        return [pscustomobject]@{
            Id = "candidate-v2.1-extended"
            BaselineId = "candidate-v2.1-extended"
            DatasetVersion = "v2.1-extended"
            DatasetLocation = "classpath:llm-memory-eval-set-v2-1-extended.json"
            StrictReportProfile = $false
        }
    }
    if ($normalized -eq "official-v3-real-agent-workload-strict") {
        return [pscustomobject]@{
            Id = "official-v3-real-agent-workload-strict"
            BaselineId = "20260603-v3-real-agent-workload-audit-002"
            DatasetVersion = "v3-real-agent-workload"
            DatasetLocation = "classpath:llm-memory-eval-set-v3-real-agent-workload.json"
            StrictReportProfile = $true
        }
    }
    if ($normalized -eq "audit-v3-real-agent-workload") {
        return [pscustomobject]@{
            Id = "audit-v3-real-agent-workload"
            BaselineId = "candidate-v3-real-agent-workload"
            DatasetVersion = "v3-real-agent-workload"
            DatasetLocation = "classpath:llm-memory-eval-set-v3-real-agent-workload.json"
            StrictReportProfile = $false
        }
    }
    if ($normalized -eq "official-v3.1-real-agent-workload-strict") {
        return [pscustomobject]@{
            Id = "official-v3.1-real-agent-workload-strict"
            BaselineId = "20260603-v3-1-real-agent-workload-candidate-audit-003"
            DatasetVersion = "v3.1-real-agent-workload"
            DatasetLocation = "classpath:llm-memory-eval-set-v3-1-real-agent-workload.json"
            StrictReportProfile = $true
        }
    }
    if ($normalized -eq "candidate-v3.1-real-agent-workload") {
        return [pscustomobject]@{
            Id = "candidate-v3.1-real-agent-workload"
            BaselineId = "candidate-v3.1-real-agent-workload"
            DatasetVersion = "v3.1-real-agent-workload"
            DatasetLocation = "classpath:llm-memory-eval-set-v3-1-real-agent-workload.json"
            StrictReportProfile = $false
        }
    }
    return $null
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
        [string]$Details = "",
        [bool]$AffectsGate = $true
    )
    return [pscustomobject]@{
        Name = $Name
        Passed = $Passed
        AffectsGate = $AffectsGate
        Expected = $Expected
        Actual = $Actual
        Details = $Details
    }
}

function New-ProfileGateCheck {
    param(
        [string]$Name,
        [bool]$Passed,
        [string]$Expected,
        [string]$Actual,
        [string]$Details = ""
    )
    return New-AuditGateCheck -Name $Name -Passed $Passed -Expected $Expected -Actual $Actual -Details $Details
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

function Get-RunMismatchDetails {
    param(
        [System.Collections.IEnumerable]$Runs,
        [string]$PropertyName,
        [string]$ExpectedValue
    )
    $mismatches = @($Runs | Where-Object {
        [string]$_.PSObject.Properties[$PropertyName].Value -ne $ExpectedValue
    } | ForEach-Object {
        "round {0}: {1}" -f $_.RoundIndex, ([string]$_.PSObject.Properties[$PropertyName].Value)
    })
    return ($mismatches -join "; ")
}

function Test-BaselineProfileIdMatches {
    param(
        [string]$Actual,
        [string]$Expected,
        [string]$DatasetLocation,
        [switch]$AllowEmptyActual
    )
    if ($Actual -eq $Expected) {
        return $true
    }
    if ($DatasetLocation -eq "classpath:llm-memory-eval-set-v2-1.json" `
            -and $Expected -eq "official-v2.1-strict" `
            -and $Actual -eq "contract-v2.1-candidate") {
        return $true
    }
    if ($DatasetLocation -eq "classpath:llm-memory-eval-set-v2-1-extended.json" `
            -and $Expected -eq "official-v2.1-extended-strict" `
            -and $Actual -eq "candidate-v2.1-extended") {
        return $true
    }
    if ($DatasetLocation -eq "classpath:llm-memory-eval-set-v2-1-extended.json" `
            -and $Expected -eq "official-v2.1-extended-strict" `
            -and $AllowEmptyActual `
            -and [string]::IsNullOrWhiteSpace($Actual)) {
        return $true
    }
    if ($DatasetLocation -eq "classpath:llm-memory-eval-set-v3-real-agent-workload.json" `
            -and $Expected -eq "official-v3-real-agent-workload-strict" `
            -and $Actual -eq "audit-v3-real-agent-workload") {
        return $true
    }
    if ($DatasetLocation -eq "classpath:llm-memory-eval-set-v3-real-agent-workload.json" `
            -and $Expected -eq "official-v3-real-agent-workload-strict" `
            -and $AllowEmptyActual `
            -and [string]::IsNullOrWhiteSpace($Actual)) {
        return $true
    }
    if ($DatasetLocation -eq "classpath:llm-memory-eval-set-v3-1-real-agent-workload.json" `
            -and $Expected -eq "official-v3.1-real-agent-workload-strict" `
            -and $Actual -eq "candidate-v3.1-real-agent-workload") {
        return $true
    }
    if ($DatasetLocation -eq "classpath:llm-memory-eval-set-v3-1-real-agent-workload.json" `
            -and $Expected -eq "official-v3.1-real-agent-workload-strict" `
            -and $AllowEmptyActual `
            -and [string]::IsNullOrWhiteSpace($Actual)) {
        return $true
    }
    return $false
}

function Get-RunProfileMismatchDetails {
    param(
        [System.Collections.IEnumerable]$Runs,
        [string]$PropertyName,
        [string]$ExpectedValue,
        [string]$DatasetLocation
    )
    $mismatches = @($Runs | Where-Object {
        -not (Test-BaselineProfileIdMatches `
            -Actual ([string]$_.PSObject.Properties[$PropertyName].Value) `
            -Expected $ExpectedValue `
            -DatasetLocation $DatasetLocation)
    } | ForEach-Object {
        "round {0}: {1}" -f $_.RoundIndex, ([string]$_.PSObject.Properties[$PropertyName].Value)
    })
    return ($mismatches -join "; ")
}

function Get-VerifyProfileMismatchDetails {
    param(
        [System.Collections.IEnumerable]$Runs,
        [string]$ExpectedValue,
        [string]$DatasetLocation
    )
    $mismatches = @($Runs | Where-Object {
        if ($null -eq $_.Verify) {
            $true
        } else {
            -not (Test-BaselineProfileIdMatches `
                -Actual ([string]$_.Verify.Profile) `
                -Expected $ExpectedValue `
                -DatasetLocation $DatasetLocation `
                -AllowEmptyActual)
        }
    } | ForEach-Object {
        $actual = if ($null -eq $_.Verify) { "" } else { [string]$_.Verify.Profile }
        "round {0}: {1}" -f $_.RoundIndex, $actual
    })
    return ($mismatches -join "; ")
}

function New-ProfileGateResult {
    param(
        [System.Collections.IEnumerable]$Runs,
        [int]$RequestedRounds,
        [string]$DatasetLocation,
        [string]$DatasetVersion,
        [string]$BaselineProfile,
        [string]$StrictVerifierProfile
    )
    $completedRuns = @($Runs | Where-Object { $_.Status -eq "completed" })
    $expectedDatasetVersion = Get-DatasetVersion -Location $DatasetLocation
    $expectedBaselineProfile = Get-AuditBaselineProfile -Location $DatasetLocation
    $expectedStrictVerifierProfile = Get-StrictVerifierProfile -Location $DatasetLocation
    $baselineDefinition = Get-BaselineProfileDefinition -Profile $BaselineProfile
    $strictDefinition = if ([string]::IsNullOrWhiteSpace($StrictVerifierProfile)) {
        $null
    } else {
        Get-BaselineProfileDefinition -Profile $StrictVerifierProfile
    }
    $customDataset = $expectedDatasetVersion -eq "custom"
    $baselineProfileKnownOrCustom = $null -ne $baselineDefinition -or ($customDataset -and $BaselineProfile -eq "custom")
    $strictProfileKnownOrCustom = $null -ne $strictDefinition `
        -or ($customDataset -and [string]::IsNullOrWhiteSpace($StrictVerifierProfile)) `
        -or ([string]::IsNullOrWhiteSpace($expectedStrictVerifierProfile) -and [string]::IsNullOrWhiteSpace($StrictVerifierProfile))
    $baselineProfileDatasetMatches = if ($null -ne $baselineDefinition) {
        $baselineDefinition.DatasetLocation -eq $DatasetLocation -and $baselineDefinition.DatasetVersion -eq $DatasetVersion
    } else {
        $customDataset -and $BaselineProfile -eq "custom"
    }
    $strictProfileDatasetMatches = if ([string]::IsNullOrWhiteSpace($StrictVerifierProfile)) {
        [string]::IsNullOrWhiteSpace($expectedStrictVerifierProfile)
    } elseif ($null -ne $strictDefinition) {
        $strictDefinition.StrictReportProfile -and $strictDefinition.DatasetLocation -eq $DatasetLocation -and $strictDefinition.DatasetVersion -eq $DatasetVersion
    } else {
        $false
    }
    $runDatasetLocationMatches = $completedRuns.Count -eq $RequestedRounds -and @($completedRuns | Where-Object { [string]$_.DatasetLocation -ne $DatasetLocation }).Count -eq 0
    $runDatasetVersionMatches = $completedRuns.Count -eq $RequestedRounds -and @($completedRuns | Where-Object { [string]$_.DatasetVersion -ne $DatasetVersion }).Count -eq 0
    $runBaselineProfileMatches = $completedRuns.Count -eq $RequestedRounds -and @($completedRuns | Where-Object {
        -not (Test-BaselineProfileIdMatches `
            -Actual ([string]$_.BaselineProfileId) `
            -Expected $BaselineProfile `
            -DatasetLocation $DatasetLocation)
    }).Count -eq 0
    $runStrictProfileMatches = $completedRuns.Count -eq $RequestedRounds -and @($completedRuns | Where-Object {
        -not (Test-BaselineProfileIdMatches `
            -Actual ([string]$_.StrictVerifierProfileId) `
            -Expected $StrictVerifierProfile `
            -DatasetLocation $DatasetLocation `
            -AllowEmptyActual)
    }).Count -eq 0
    $runVerifyProfileMatches = $completedRuns.Count -eq $RequestedRounds -and @($completedRuns | Where-Object {
        $null -eq $_.Verify -or -not (Test-BaselineProfileIdMatches `
            -Actual ([string]$_.Verify.Profile) `
            -Expected $StrictVerifierProfile `
            -DatasetLocation $DatasetLocation `
            -AllowEmptyActual)
    }).Count -eq 0
    $strictProfileDefinitionExpected = if ([string]::IsNullOrWhiteSpace($expectedStrictVerifierProfile)) {
        "no strict verifier profile for {0}/{1}" -f $DatasetVersion, $DatasetLocation
    } else {
        "strict-report profile for {0}/{1}" -f $DatasetVersion, $DatasetLocation
    }

    $checks = @(
        New-ProfileGateCheck `
            -Name "datasetVersion" `
            -Passed ($DatasetVersion -eq $expectedDatasetVersion) `
            -Expected $expectedDatasetVersion `
            -Actual $DatasetVersion
        New-ProfileGateCheck `
            -Name "baselineProfileForDataset" `
            -Passed (Test-BaselineProfileIdMatches -Actual $BaselineProfile -Expected $expectedBaselineProfile -DatasetLocation $DatasetLocation) `
            -Expected $expectedBaselineProfile `
            -Actual $BaselineProfile
        New-ProfileGateCheck `
            -Name "strictVerifierProfileForDataset" `
            -Passed (Test-BaselineProfileIdMatches -Actual $StrictVerifierProfile -Expected $expectedStrictVerifierProfile -DatasetLocation $DatasetLocation) `
            -Expected $expectedStrictVerifierProfile `
            -Actual $StrictVerifierProfile
        New-ProfileGateCheck `
            -Name "baselineProfileDefinition" `
            -Passed ($baselineProfileKnownOrCustom -and $baselineProfileDatasetMatches) `
            -Expected ("known profile for {0}/{1}" -f $DatasetVersion, $DatasetLocation) `
            -Actual $BaselineProfile
        New-ProfileGateCheck `
            -Name "strictVerifierProfileDefinition" `
            -Passed ($strictProfileKnownOrCustom -and $strictProfileDatasetMatches) `
            -Expected $strictProfileDefinitionExpected `
            -Actual $StrictVerifierProfile
        New-ProfileGateCheck `
            -Name "runDatasetLocation" `
            -Passed $runDatasetLocationMatches `
            -Expected $DatasetLocation `
            -Actual ("completedRuns={0}" -f $completedRuns.Count) `
            -Details (Get-RunMismatchDetails -Runs $completedRuns -PropertyName "DatasetLocation" -ExpectedValue $DatasetLocation)
        New-ProfileGateCheck `
            -Name "runDatasetVersion" `
            -Passed $runDatasetVersionMatches `
            -Expected $DatasetVersion `
            -Actual ("completedRuns={0}" -f $completedRuns.Count) `
            -Details (Get-RunMismatchDetails -Runs $completedRuns -PropertyName "DatasetVersion" -ExpectedValue $DatasetVersion)
        New-ProfileGateCheck `
            -Name "runBaselineProfileId" `
            -Passed $runBaselineProfileMatches `
            -Expected $BaselineProfile `
            -Actual ("completedRuns={0}" -f $completedRuns.Count) `
            -Details (Get-RunProfileMismatchDetails -Runs $completedRuns -PropertyName "BaselineProfileId" -ExpectedValue $BaselineProfile -DatasetLocation $DatasetLocation)
        New-ProfileGateCheck `
            -Name "runStrictVerifierProfileId" `
            -Passed $runStrictProfileMatches `
            -Expected $StrictVerifierProfile `
            -Actual ("completedRuns={0}" -f $completedRuns.Count) `
            -Details (Get-RunProfileMismatchDetails -Runs $completedRuns -PropertyName "StrictVerifierProfileId" -ExpectedValue $StrictVerifierProfile -DatasetLocation $DatasetLocation)
        New-ProfileGateCheck `
            -Name "runVerifyProfile" `
            -Passed $runVerifyProfileMatches `
            -Expected $StrictVerifierProfile `
            -Actual ("completedRuns={0}" -f $completedRuns.Count) `
            -Details (Get-VerifyProfileMismatchDetails -Runs $completedRuns -ExpectedValue $StrictVerifierProfile -DatasetLocation $DatasetLocation)
    )
    return [pscustomobject]@{
        Passed = @($checks | Where-Object { -not $_.Passed }).Count -eq 0
        Expected = [pscustomobject]@{
            DatasetLocation = $DatasetLocation
            DatasetVersion = $expectedDatasetVersion
            BaselineProfile = $expectedBaselineProfile
            StrictVerifierProfile = $expectedStrictVerifierProfile
        }
        Actual = [pscustomobject]@{
            DatasetLocation = $DatasetLocation
            DatasetVersion = $DatasetVersion
            BaselineProfile = $BaselineProfile
            StrictVerifierProfile = $StrictVerifierProfile
            CompletedRuns = $completedRuns.Count
        }
        Checks = $checks
    }
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
    $thresholdEpsilon = 0.0000001
    $baselineMax = if (@($BaselineCorrectValues).Count -eq 0) { $null } else { ($BaselineCorrectValues | Measure-Object -Maximum).Maximum }
    $actualGenerationModelSequences = @($completedRuns | ForEach-Object {
        (@($_.ActualGenerationModels) | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Sort-Object -Unique) -join ","
    })
    $actualGenerationModelRecordedSequences = @($actualGenerationModelSequences | Where-Object {
        -not [string]::IsNullOrWhiteSpace([string]$_)
    })
    $actualGenerationModels = @($completedRuns | ForEach-Object {
        @($_.ActualGenerationModels)
    } | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Sort-Object -Unique)
    $actualGenerationModelsStable = $actualGenerationModelRecordedSequences.Count -eq 0 `
        -or @($actualGenerationModelRecordedSequences | Sort-Object -Unique).Count -eq 1
    $actualGenerationModelLabel = if ($actualGenerationModels.Count -eq 0) {
        "not recorded"
    } else {
        $actualGenerationModels -join ","
    }
    $environmentStable = $completedRuns.Count -gt 0 `
        -and (Get-DistinctRunValueCount -Runs $completedRuns -PropertyName "DatasetLocation") -eq 1 `
        -and (Get-DistinctRunValueCount -Runs $completedRuns -PropertyName "GenerationBaseUrl") -eq 1 `
        -and (Get-DistinctRunValueCount -Runs $completedRuns -PropertyName "GenerationModel") -eq 1 `
        -and (Get-DistinctRunValueCount -Runs $completedRuns -PropertyName "L1MaxTokens") -eq 1 `
        -and (Get-DistinctRunValueCount -Runs $completedRuns -PropertyName "EvalSystemPromptSha256") -eq 1 `
        -and (Get-DistinctRunValueCount -Runs $completedRuns -PropertyName "EvalParallelism") -eq 1 `
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
            -Expected "all completed runs share dataset/baseUrl/requestedModel/l1MaxTokens/promptSha/parallelism/modes" `
            -Actual ("completedRuns={0}; actualModels={1}; actualModelRunCount={2}" -f `
                $completedRuns.Count, `
                $actualGenerationModelLabel, `
                $actualGenerationModelRecordedSequences.Count)
        New-AuditGateCheck `
            -Name "actualGenerationModelsStable" `
            -Passed $actualGenerationModelsStable `
            -Expected "stable when recorded; diagnostic only" `
            -Actual ("actualModels={0}; actualModelRunCount={1}" -f `
                $actualGenerationModelLabel, `
                $actualGenerationModelRecordedSequences.Count) `
            -Details "does not affect AuditGate.Passed" `
            -AffectsGate $false
        New-AuditGateCheck `
            -Name "baselineNoMemoryMaxCorrect" `
            -Passed ($null -ne $baselineMax -and [double]$baselineMax -le $BaselineNoMemoryMaxCorrect -and @($BaselineCorrectValues).Count -eq $RequestedRounds) `
            -Expected ("max <= {0}" -f $BaselineNoMemoryMaxCorrect) `
            -Actual ("values=[{0}]" -f (Format-MetricSequence -Values $BaselineCorrectValues))
        New-AuditGateCheck `
            -Name "vortexMemoryMeanAccuracy" `
            -Passed ($null -ne $memoryMean -and ($memoryMean + $thresholdEpsilon) -ge $MinVortexMemoryMeanAccuracy -and @($MemoryAccuracyValues).Count -eq $RequestedRounds) `
            -Expected (">= " + (Format-Decimal $MinVortexMemoryMeanAccuracy)) `
            -Actual (Format-Decimal $memoryMean)
        New-AuditGateCheck `
            -Name "recoveredMeanAccuracy" `
            -Passed ($null -ne $recoveredMean -and ($recoveredMean + $thresholdEpsilon) -ge $MinRecoveredMeanAccuracy -and @($RecoveredAccuracyValues).Count -eq $RequestedRounds) `
            -Expected (">= " + (Format-Decimal $MinRecoveredMeanAccuracy)) `
            -Actual (Format-Decimal $recoveredMean)
        New-AuditGateCheck `
            -Name "recoveredL2MeanHitRate" `
            -Passed ($null -ne $recoveredL2Mean -and ($recoveredL2Mean + $thresholdEpsilon) -ge $MinRecoveredL2MeanHitRate -and @($RecoveredL2HitRateValues).Count -eq $RequestedRounds) `
            -Expected (">= " + (Format-Decimal $MinRecoveredL2MeanHitRate)) `
            -Actual (Format-Decimal $recoveredL2Mean)
    )
    return [pscustomobject]@{
        Passed = @($checks | Where-Object { $_.AffectsGate -ne $false -and -not $_.Passed }).Count -eq 0
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
            ActualGenerationModels = $actualGenerationModels
            ActualGenerationModelRunCount = $actualGenerationModelRecordedSequences.Count
            ActualGenerationModelsStable = $actualGenerationModelsStable
        }
        Checks = $checks
    }
}

function Get-AuditGateMarkdown {
    param([object]$AuditGate)
    $builder = New-Object System.Text.StringBuilder
    [void]$builder.AppendLine("| Check | Passed | Affects Gate | Expected | Actual | Details |")
    [void]$builder.AppendLine("| --- | --- | --- | --- | --- | --- |")
    foreach ($check in @($AuditGate.Checks)) {
        $affectsGate = if ($check.PSObject.Properties["AffectsGate"]) { $check.AffectsGate } else { $true }
        [void]$builder.AppendLine("| $(Format-MarkdownCell $check.Name) | $(Format-MarkdownCell $check.Passed) | $(Format-MarkdownCell $affectsGate) | $(Format-MarkdownCell $check.Expected) | $(Format-MarkdownCell $check.Actual) | $(Format-MarkdownCell $check.Details) |")
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
                FailureReason = [string]$result.failureReason
                RuntimeErrorType = if ($result.PSObject.Properties["runtimeErrorType"]) { [string]$result.runtimeErrorType } else { "" }
                TransientRuntimeError = if ($result.PSObject.Properties["transientRuntimeError"]) { [bool]$result.transientRuntimeError } else { $false }
                MissingMustContain = @($result.missingMustContain | ForEach-Object { [string]$_ })
                MatchedForbiddenTerms = @($result.matchedForbiddenTerms | ForEach-Object { [string]$_ })
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
        $failureReasons = @($group.Group | ForEach-Object { $_.FailureReason } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
        $runtimeErrorTypes = @($group.Group | ForEach-Object { $_.RuntimeErrorType } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
        $missingMustContain = @($group.Group | ForEach-Object { $_.MissingMustContain } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
        $matchedForbiddenTerms = @($group.Group | ForEach-Object { $_.MatchedForbiddenTerms } | Where-Object { -not [string]::IsNullOrWhiteSpace($_) } | Sort-Object -Unique)
        $returnedSets = @($group.Group | ForEach-Object { ($_.ReturnedFragmentIds -join ",") } | Sort-Object -Unique)
        $summary.Add([pscustomobject]@{
            CaseId = $first.CaseId
            Mode = $first.Mode
            FailureCount = @($group.Group).Count
            RoundCount = $RoundCount
            FailureRounds = @($group.Group | Sort-Object RoundIndex | ForEach-Object { $_.RoundIndex })
            FailureReasons = $failureReasons
            RuntimeErrorTypes = $runtimeErrorTypes
            TransientRuntimeFailureCount = @($group.Group | Where-Object { $_.TransientRuntimeError -eq $true }).Count
            RecallHitFailureCount = @($group.Group | Where-Object { $_.RecallHit -eq $true }).Count
            RecallMissFailureCount = @($group.Group | Where-Object { $_.RecallHit -eq $false }).Count
            MissingExpectedFragments = $missing
            MissingMustContain = $missingMustContain
            MatchedForbiddenTerms = $matchedForbiddenTerms
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
    [void]$builder.AppendLine("| Case | Mode | Failures | Reasons | Runtime Types | Transient Runtime | Rounds | Recall Hit | Recall Miss | Missing Expected Fragments | Missing Must Contain | Forbidden Terms | Expected Answer | Question |")
    [void]$builder.AppendLine("| --- | --- | ---: | --- | --- | ---: | --- | ---: | ---: | --- | --- | --- | --- | --- |")
    foreach ($item in $items) {
        [void]$builder.AppendLine("| $(Format-MarkdownCell $item.CaseId) | $(Format-MarkdownCell $item.Mode) | $($item.FailureCount)/$($item.RoundCount) | $(Format-MarkdownCell $item.FailureReasons) | $(Format-MarkdownCell $item.RuntimeErrorTypes) | $($item.TransientRuntimeFailureCount) | $(Format-MarkdownCell $item.FailureRounds) | $($item.RecallHitFailureCount) | $($item.RecallMissFailureCount) | $(Format-MarkdownCell $item.MissingExpectedFragments) | $(Format-MarkdownCell $item.MissingMustContain) | $(Format-MarkdownCell $item.MatchedForbiddenTerms) | $(Format-MarkdownCell $item.ExpectedAnswer) | $(Format-MarkdownCell $item.Question) |")
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
    [void]$builder.AppendLine("| Round | Case | Mode | Reason | Runtime Type | Transient Runtime | Recall Hit | Returned Fragments | Missing Expected Fragments | Missing Must Contain | Forbidden Terms | Generated Answer |")
    [void]$builder.AppendLine("| ---: | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    foreach ($item in ($items | Sort-Object RoundIndex, CaseId, Mode)) {
        $answerPreview = Limit-Text -Value $item.GeneratedAnswer
        [void]$builder.AppendLine("| $($item.RoundIndex) | $(Format-MarkdownCell $item.CaseId) | $(Format-MarkdownCell $item.Mode) | $(Format-MarkdownCell $item.FailureReason) | $(Format-MarkdownCell $item.RuntimeErrorType) | $(Format-MarkdownCell $item.TransientRuntimeError) | $(Format-MarkdownCell $item.RecallHit) | $(Format-MarkdownCell $item.ReturnedFragmentIds) | $(Format-MarkdownCell $item.MissingExpectedFragments) | $(Format-MarkdownCell $item.MissingMustContain) | $(Format-MarkdownCell $item.MatchedForbiddenTerms) | $(Format-MarkdownCell $answerPreview) |")
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
    $runDatasetLocation = [string]$report.environment.datasetLocation
    $runDatasetVersion = if ($report.environment.PSObject.Properties["datasetVersion"]) {
        [string]$report.environment.datasetVersion
    } else {
        Get-DatasetVersion -Location $runDatasetLocation
    }
    $runBaselineProfileId = if ($report.environment.PSObject.Properties["baselineProfileId"]) {
        [string]$report.environment.baselineProfileId
    } else {
        Get-AuditBaselineProfile -Location $runDatasetLocation
    }
    $runStrictVerifierProfileId = if ($report.environment.PSObject.Properties["strictVerifierProfileId"]) {
        [string]$report.environment.strictVerifierProfileId
    } else {
        Get-StrictVerifierProfile -Location $runDatasetLocation
    }
    $runEvalParallelism = if ($report.environment.PSObject.Properties["evalParallelism"]) {
        [int]$report.environment.evalParallelism
    } else {
        1
    }
    $actualGenerationModels = if ($report.environment.PSObject.Properties["actualGenerationModels"]) {
        @($report.environment.actualGenerationModels | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | ForEach-Object { [string]$_ })
    } else {
        @()
    }
    return [pscustomobject]@{
        Stamp = $RoundStamp
        ReportDir = $ReportDir
        ReportJsonPath = $reportJson.FullName
        ReportMarkdownPath = if ($null -eq $reportMarkdown) { $null } else { $reportMarkdown.FullName }
        GeneratedAt = $report.generatedAt
        TotalCases = $report.totalCases
        TotalRuns = $report.totalRuns
        DatasetLocation = $runDatasetLocation
        DatasetVersion = $runDatasetVersion
        BaselineProfileId = $runBaselineProfileId
        StrictVerifierProfileId = $runStrictVerifierProfileId
        GenerationBaseUrl = $report.environment.generationBaseUrl
        GenerationModel = $report.environment.generationModel
        ActualGenerationModels = $actualGenerationModels
        L1MaxTokens = $report.environment.l1MaxTokens
        EvalSystemPromptSha256 = $report.environment.evalSystemPromptSha256
        EvalParallelism = $runEvalParallelism
        Modes = @($report.environment.modes)
        ModeSummaries = $report.modeSummaries
        RuntimeTelemetry = if ($report.PSObject.Properties["runtimeTelemetry"]) { $report.runtimeTelemetry } else { $null }
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

function New-RuntimeTelemetryAggregate {
    param([System.Collections.IEnumerable]$Runs)
    $runsWithTelemetry = @($Runs | Where-Object {
        $_.Status -eq "completed" -and $null -ne $_.RuntimeTelemetry
    })
    $totalElapsedValues = Get-NumericValues -Items ($runsWithTelemetry | ForEach-Object { $_.RuntimeTelemetry }) -PropertyName "totalElapsedMs"
    $configuredParallelismValues = @($runsWithTelemetry | ForEach-Object {
        [int]$_.RuntimeTelemetry.configuredParallelism
    })
    $actualWorkerCountValues = @($runsWithTelemetry | ForEach-Object {
        [int]$_.RuntimeTelemetry.actualWorkerCount
    })
    $modePhasedParallelValues = @($runsWithTelemetry | ForEach-Object {
        [bool]$_.RuntimeTelemetry.modePhasedParallel
    } | Sort-Object -Unique)
    $phaseRows = @($runsWithTelemetry | ForEach-Object {
        $run = $_
        @($run.RuntimeTelemetry.modePhaseTimings) | ForEach-Object {
            [pscustomobject]@{
                RoundIndex = $run.RoundIndex
                ModeIndex = [int]$_.modeIndex
                Mode = [string]$_.mode
                CaseCount = [int]$_.caseCount
                ElapsedMs = [long]$_.elapsedMs
            }
        }
    })
    $phaseSummary = @($phaseRows | Group-Object ModeIndex, Mode | Sort-Object {
        [int]$_.Group[0].ModeIndex
    } | ForEach-Object {
        $elapsedValues = Get-NumericValues -Items $_.Group -PropertyName "ElapsedMs"
        [pscustomobject]@{
            ModeIndex = [int]$_.Group[0].ModeIndex
            Mode = [string]$_.Group[0].Mode
            RoundCount = @($_.Group).Count
            CaseCounts = @($_.Group | ForEach-Object { [int]$_.CaseCount } | Sort-Object -Unique)
            ElapsedMsValues = $elapsedValues
            MeanElapsedMs = Get-MeanValue -Values $elapsedValues
            MinElapsedMs = Get-MinValue -Values $elapsedValues
            MaxElapsedMs = Get-MaxValue -Values $elapsedValues
        }
    })

    return [pscustomobject]@{
        PresentRunCount = $runsWithTelemetry.Count
        MissingRunCount = @($Runs | Where-Object { $_.Status -eq "completed" -and $null -eq $_.RuntimeTelemetry }).Count
        ConfiguredParallelismValues = $configuredParallelismValues
        ActualWorkerCountValues = $actualWorkerCountValues
        ModePhasedParallelValues = $modePhasedParallelValues
        TotalElapsedMsValues = $totalElapsedValues
        MeanTotalElapsedMs = Get-MeanValue -Values $totalElapsedValues
        MinTotalElapsedMs = Get-MinValue -Values $totalElapsedValues
        MaxTotalElapsedMs = Get-MaxValue -Values $totalElapsedValues
        PhaseTimings = $phaseSummary
    }
}

function Get-RuntimeTelemetryMarkdown {
    param([object]$RuntimeTelemetry)
    if ($null -eq $RuntimeTelemetry -or $RuntimeTelemetry.PresentRunCount -eq 0) {
        return "No runtime telemetry was present in completed run reports."
    }

    $builder = New-Object System.Text.StringBuilder
    [void]$builder.AppendLine("- Runs with telemetry: $($RuntimeTelemetry.PresentRunCount)")
    [void]$builder.AppendLine("- Runs missing telemetry: $($RuntimeTelemetry.MissingRunCount)")
    [void]$builder.AppendLine("- Configured parallelism values: $(Format-MetricSequence -Values $RuntimeTelemetry.ConfiguredParallelismValues)")
    [void]$builder.AppendLine("- Actual worker count values: $(Format-MetricSequence -Values $RuntimeTelemetry.ActualWorkerCountValues)")
    [void]$builder.AppendLine("- Mode phased parallel values: $(Format-MetricSequence -Values $RuntimeTelemetry.ModePhasedParallelValues)")
    [void]$builder.AppendLine("- Total elapsed ms values: $(Format-MetricSequence -Values $RuntimeTelemetry.TotalElapsedMsValues)")
    [void]$builder.AppendLine("- Mean total elapsed ms: $(Format-Decimal $RuntimeTelemetry.MeanTotalElapsedMs)")
    [void]$builder.AppendLine()
    [void]$builder.AppendLine("| Mode Index | Mode | Rounds | Case Counts | Elapsed ms values | Mean ms | Min ms | Max ms |")
    [void]$builder.AppendLine("| ---: | --- | ---: | --- | --- | ---: | ---: | ---: |")
    foreach ($phase in @($RuntimeTelemetry.PhaseTimings)) {
        [void]$builder.AppendLine("| $($phase.ModeIndex) | $(Format-MarkdownCell $phase.Mode) | $($phase.RoundCount) | $(Format-MarkdownCell $phase.CaseCounts) | $(Format-MarkdownCell $phase.ElapsedMsValues) | $(Format-Decimal $phase.MeanElapsedMs) | $($phase.MinElapsedMs) | $($phase.MaxElapsedMs) |")
    }
    return $builder.ToString()
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
        $verifyLabel = if ($run.Verify.Skipped) {
            "SKIP"
        } elseif ($run.Verify.Passed) {
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
if ([string]::IsNullOrWhiteSpace($BaselineProfile)) {
    $BaselineProfile = Get-AuditBaselineProfile -Location $DatasetLocation
}
if ([string]::IsNullOrWhiteSpace($StrictVerifierProfile)) {
    $StrictVerifierProfile = Get-StrictVerifierProfile -Location $DatasetLocation
}
$datasetVersion = Get-DatasetVersion -Location $DatasetLocation
$baselineId = Get-BaselineIdForProfile -Profile $BaselineProfile

$normalizedReportRoot = $ReportRoot.TrimEnd('/').TrimEnd([char]92)
$auditDir = Join-Path $repoRoot ($normalizedReportRoot + "/" + $AuditStamp)
$runsRootRelative = ($normalizedReportRoot + "/" + $AuditStamp + "/runs")
$singleRunScript = Join-Path $repoRoot "ops/run-real-llm-memory-eval.ps1"
$evalCliJar = Join-Path $repoRoot "vortex-app/target/vortex-app-0.2.0-eval-cli.jar"
$auditJsonPath = Join-Path $auditDir "baseline-audit-summary.json"
$auditMarkdownPath = Join-Path $auditDir "baseline-audit-summary.md"
$datasetCaseMap = Get-DatasetCaseMap -Location $DatasetLocation -RepoRoot $repoRoot

Assert-PathExists -Path $singleRunScript -Label "Single-run eval script"

New-Item -ItemType Directory -Force -Path $auditDir | Out-Null

Write-Host "Starting LLM memory baseline audit"
Write-Host "  Audit Stamp : $AuditStamp"
Write-Host "  Rounds      : $Rounds"
Write-Host "  Dataset     : $DatasetLocation"
Write-Host "  Dataset Ver : $datasetVersion"
Write-Host "  Profile     : $BaselineProfile"
Write-Host "  Verifier    : $StrictVerifierProfile"
Write-Host "  Parallelism : $EvalParallelism"
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
            $singleRunArgs = @{
                ApiKey = $ApiKey
                BaseUrl = $BaseUrl
                Model = $Model
                Stamp = $roundStamp
                DatasetLocation = $DatasetLocation
                BgeModelPath = $BgeModelPath
                L1MaxTokens = $L1MaxTokens
                Modes = $Modes
                EvalParallelism = $EvalParallelism
                ReportRoot = $runsRootRelative
                SkipComposeUp = $true
                SkipPackage = $true
            }
            if ($SkipGenerationPreflight) {
                $singleRunArgs.SkipGenerationPreflight = $true
            }
            & $singleRunScript @singleRunArgs
            $singleRun = Import-ExistingRun -ReportDir $roundReportDir -RoundStamp $roundStamp
            if ($null -eq $singleRun) {
                throw "Eval completed but report could not be imported from: $roundReportDir"
            }
        }

        if ([string]::IsNullOrWhiteSpace($StrictVerifierProfile)) {
            $verify = New-VerifyResult `
                -ExitCode 0 `
                -Output "Strict verifier skipped: no strict verifier profile for dataset $DatasetLocation" `
                -Profile "" `
                -Skipped $true
        } else {
            $verifyInvocation = Invoke-ProcessCapture -FilePath "java" -ArgumentList @(
                "-jar",
                $evalCliJar,
                "verify",
                "--profile",
                $StrictVerifierProfile,
                $singleRun.ReportJsonPath
            )
            $verify = New-VerifyResult `
                -ExitCode $verifyInvocation.ExitCode `
                -Output $verifyInvocation.Output `
                -Profile $StrictVerifierProfile
        }

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
            DatasetVersion = if ($singleRun.PSObject.Properties["DatasetVersion"]) { $singleRun.DatasetVersion } else { $datasetVersion }
            BaselineProfileId = if ($singleRun.PSObject.Properties["BaselineProfileId"]) { $singleRun.BaselineProfileId } else { $BaselineProfile }
            StrictVerifierProfileId = if ($singleRun.PSObject.Properties["StrictVerifierProfileId"]) { $singleRun.StrictVerifierProfileId } else { $StrictVerifierProfile }
            GenerationBaseUrl = $singleRun.GenerationBaseUrl
            GenerationModel = $singleRun.GenerationModel
            ActualGenerationModels = @($singleRun.ActualGenerationModels)
            L1MaxTokens = $singleRun.L1MaxTokens
            EvalSystemPromptSha256 = $singleRun.EvalSystemPromptSha256
            EvalParallelism = if ($singleRun.PSObject.Properties["EvalParallelism"]) { $singleRun.EvalParallelism } else { $EvalParallelism }
            Modes = @($singleRun.Modes)
            ModeSummaries = ConvertTo-PlainObject $singleRun.ModeSummaries
            RuntimeTelemetry = if ($singleRun.PSObject.Properties["RuntimeTelemetry"]) { ConvertTo-PlainObject $singleRun.RuntimeTelemetry } else { $null }
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
            DatasetVersion = $datasetVersion
            BaselineProfileId = $BaselineProfile
            StrictVerifierProfileId = $StrictVerifierProfile
            GenerationBaseUrl = $BaseUrl
            GenerationModel = $Model
            ActualGenerationModels = @()
            L1MaxTokens = $L1MaxTokens
            EvalSystemPromptSha256 = $null
            EvalParallelism = $EvalParallelism
            Modes = @()
            ModeSummaries = $null
            RuntimeTelemetry = $null
            Verify = [pscustomobject]@{
                Profile = $StrictVerifierProfile
                Skipped = $false
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
    $verifierSkippedCount = @($completedRuns | Where-Object { $_.Verify.Skipped }).Count
    $evalSuccessCount = @($completedRuns | Where-Object { $_.Status -eq "completed" }).Count
    $strictVerifierPassed = ($completedRuns.Count -eq $Rounds) -and ($verifierPassCount -eq $Rounds)

    $baselineCorrectValues = Get-ModeMetricValues -Runs $completedRuns -ModeName "Baseline-NoMemory" -MetricName "correct"
    $memoryAccuracyValues = Get-ModeMetricValues -Runs $completedRuns -ModeName "Vortex-Memory" -MetricName "accuracy"
    $recoveredAccuracyValues = Get-ModeMetricValues -Runs $completedRuns -ModeName "Vortex-RecoveredMemory" -MetricName "recoveredAccuracy"
    $recoveredL2HitRateValues = Get-ModeMetricValues -Runs $completedRuns -ModeName "Vortex-RecoveredMemory" -MetricName "recoveredL2HitRate"
    $caseFailureDetails = Get-CaseFailureDetails -Runs $completedRuns -DatasetCases $datasetCaseMap
    $caseFailureSummary = Get-CaseFailureSummary -Failures $caseFailureDetails -RoundCount $Rounds
    $runtimeTelemetryAggregate = New-RuntimeTelemetryAggregate -Runs $completedRuns
    $actualGenerationModels = @($completedRuns | ForEach-Object {
        @($_.ActualGenerationModels)
    } | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Sort-Object -Unique)
    $actualGenerationModelRunCount = @($completedRuns | Where-Object {
        @($_.ActualGenerationModels | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) }).Count -gt 0
    }).Count
    $actualGenerationModelsStable = $actualGenerationModelRunCount -eq 0 `
        -or @($completedRuns | ForEach-Object {
            (@($_.ActualGenerationModels) | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Sort-Object -Unique) -join ","
        } | Where-Object { -not [string]::IsNullOrWhiteSpace([string]$_) } | Sort-Object -Unique).Count -eq 1
    $runtimeErrorTypeCounts = @($caseFailureDetails | Where-Object {
        $_.FailureReason -eq "runtime_error" -and -not [string]::IsNullOrWhiteSpace($_.RuntimeErrorType)
    } | Group-Object RuntimeErrorType | Sort-Object -Property @{Expression = "Count"; Descending = $true}, Name | ForEach-Object {
        [pscustomobject]@{
            RuntimeErrorType = $_.Name
            Count = $_.Count
            TransientCount = @($_.Group | Where-Object { $_.TransientRuntimeError -eq $true }).Count
        }
    })
    $transientRuntimeErrorCount = @($caseFailureDetails | Where-Object { $_.TransientRuntimeError -eq $true }).Count
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
    $profileGate = New-ProfileGateResult `
        -Runs $completedRuns `
        -RequestedRounds $Rounds `
        -DatasetLocation $DatasetLocation `
        -DatasetVersion $datasetVersion `
        -BaselineProfile $BaselineProfile `
        -StrictVerifierProfile $StrictVerifierProfile
    $overallPassed = $auditGate.Passed -and $profileGate.Passed

    Write-Host "Building audit summary object"
    $summary = [pscustomobject]@{
        AuditStamp = $AuditStamp
        GeneratedAt = (Get-Date).ToUniversalTime().ToString("o")
        BaselineId = $baselineId
        BaselineProfile = $BaselineProfile
        DatasetVersion = $datasetVersion
        StrictVerifierProfile = $StrictVerifierProfile
        OverallPassed = $overallPassed
        StrictVerifierPassed = $strictVerifierPassed
        Settings = [pscustomobject]@{
            BaseUrl = $BaseUrl
            Model = $Model
            DatasetLocation = $DatasetLocation
            DatasetVersion = $datasetVersion
            BaselineProfile = $BaselineProfile
            StrictVerifierProfile = $StrictVerifierProfile
            BgeModelPath = $BgeModelPath
            L1MaxTokens = $L1MaxTokens
            Modes = $Modes
            EvalParallelism = $EvalParallelism
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
            VerifierSkippedCount = $verifierSkippedCount
            VerifierFailCount = $Rounds - $verifierPassCount - $verifierSkippedCount
            BaselineNoMemoryCorrectValues = $baselineCorrectValues
            VortexMemoryAccuracyValues = $memoryAccuracyValues
            RecoveredAccuracyValues = $recoveredAccuracyValues
            RecoveredL2HitRateValues = $recoveredL2HitRateValues
            CaseFailureCount = @($caseFailureDetails).Count
            CaseFailureGroupCount = @($caseFailureSummary).Count
            RuntimeErrorTypeCounts = $runtimeErrorTypeCounts
            TransientRuntimeErrorCount = $transientRuntimeErrorCount
            ActualGenerationModels = $actualGenerationModels
            ActualGenerationModelRunCount = $actualGenerationModelRunCount
            ActualGenerationModelsStable = $actualGenerationModelsStable
            RuntimeTelemetry = $runtimeTelemetryAggregate
        }
        AuditGate = $auditGate
        ProfileGate = $profileGate
        CaseFailureSummary = $caseFailureSummary
        CaseFailureDetails = $caseFailureDetails
        Runs = $completedRuns
    }

    Write-Host "Writing audit summary json"
    $summary | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $auditJsonPath -Encoding UTF8

    Write-Host "Building audit summary markdown"
    $runtimeErrorTypeCountSummary = ($runtimeErrorTypeCounts | ForEach-Object {
        "{0}={1} transient={2}" -f $_.RuntimeErrorType, $_.Count, $_.TransientCount
    }) -join "; "
    $actualGenerationModelsMarkdown = if ($actualGenerationModels.Count -eq 0) {
        "not recorded"
    } else {
        $actualGenerationModels -join ", "
    }
    $markdown = @(
        "# LLM Memory Baseline Audit"
        ""
        "- Audit Stamp: $AuditStamp"
        "- GeneratedAt: $($summary.GeneratedAt)"
        "- Baseline Id: $($summary.BaselineId)"
        "- Baseline Profile: $($summary.BaselineProfile)"
        "- Dataset Version: $($summary.DatasetVersion)"
        "- Strict Verifier Profile: $($summary.StrictVerifierProfile)"
        "- Overall Passed: $($summary.OverallPassed)"
        "- Audit Gate Passed: $($summary.AuditGate.Passed)"
        "- Profile Gate Passed: $($summary.ProfileGate.Passed)"
        "- Strict Verifier Passed: $($summary.StrictVerifierPassed)"
        "- Requested Rounds: $Rounds"
        "- Eval Success Count: $evalSuccessCount"
        "- Verifier Pass Count: $verifierPassCount"
        "- Dataset Location: $DatasetLocation"
        "- Base URL: $BaseUrl"
        "- Model: $Model"
        "- L1 Max Tokens: $L1MaxTokens"
        "- Eval Parallelism: $EvalParallelism"
        "- Total Duration Seconds: $($summary.Aggregate.TotalDurationSeconds)"
        ""
        "## Aggregate"
        ""
        "- Baseline-NoMemory correct values: $(Format-MetricSequence -Values $baselineCorrectValues)"
        "- Vortex-Memory accuracy values: $(Format-MetricSequence -Values $memoryAccuracyValues)"
        "- RecoveredAccuracy values: $(Format-MetricSequence -Values $recoveredAccuracyValues)"
        "- RecoveredL2HitRate values: $(Format-MetricSequence -Values $recoveredL2HitRateValues)"
        "- Actual generation models: $actualGenerationModelsMarkdown"
        "- Actual generation model run count: $actualGenerationModelRunCount"
        "- Actual generation models stable: $actualGenerationModelsStable"
        "- Case failure count: $(@($caseFailureDetails).Count)"
        "- Case failure groups: $(@($caseFailureSummary).Count)"
        "- Transient runtime error count: $transientRuntimeErrorCount"
        "- Runtime error type counts: $runtimeErrorTypeCountSummary"
        ""
        "## Runtime Telemetry"
        ""
        (Get-RuntimeTelemetryMarkdown -RuntimeTelemetry $runtimeTelemetryAggregate)
        ""
        "## Audit Gate"
        ""
        (Get-AuditGateMarkdown -AuditGate $auditGate)
        ""
        "## Profile Gate"
        ""
        (Get-AuditGateMarkdown -AuditGate $profileGate)
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
Write-Host "  Profile Gate : $($profileGate.Passed)"
Write-Host "  Verify Passes: $verifierPassCount/$Rounds"

if ($FailOnAuditGateFailure -and -not $overallPassed) {
    exit 2
}

[pscustomobject]@{
    AuditStamp = $AuditStamp
    SummaryJsonPath = $auditJsonPath
    SummaryMarkdownPath = $auditMarkdownPath
    BaselineProfile = $BaselineProfile
    DatasetVersion = $datasetVersion
    StrictVerifierProfile = $StrictVerifierProfile
    OverallPassed = $overallPassed
    AuditGatePassed = $auditGate.Passed
    ProfileGatePassed = $profileGate.Passed
    StrictVerifierPassed = $strictVerifierPassed
    VerifierPassCount = $verifierPassCount
    RequestedRounds = $Rounds
}
