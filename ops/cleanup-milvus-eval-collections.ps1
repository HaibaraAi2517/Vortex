param(
    [string]$MilvusEndpoint = "http://localhost:19530",

    [string]$Token = "root:Milvus",

    [string]$Database = "_default",

    [string]$EvalCollectionPrefix = "vortex_memory_eval_",

    [ValidateRange(1, 1000)]
    [int]$MaxDeleteCount = 200,

    [switch]$Execute
)

$ErrorActionPreference = "Stop"

function Invoke-MilvusRequest {
    param(
        [string]$Path,
        [object]$Body
    )
    $endpoint = $MilvusEndpoint.TrimEnd("/")
    $headers = @{
        Authorization = "Bearer $Token"
        "Content-Type" = "application/json"
    }
    $json = $Body | ConvertTo-Json -Depth 10
    $response = Invoke-RestMethod `
        -Uri "$endpoint$Path" `
        -Method Post `
        -Headers $headers `
        -Body $json `
        -TimeoutSec 30
    if ($null -eq $response -or $response.code -ne 0) {
        $message = if ($null -eq $response) { "empty response" } else { $response.message }
        throw "Milvus request failed for ${Path}: $message"
    }
    return $response
}

function Get-MilvusCollections {
    $body = @{}
    if (-not [string]::IsNullOrWhiteSpace($Database)) {
        $body.dbName = $Database
    }
    $response = Invoke-MilvusRequest -Path "/v2/vectordb/collections/list" -Body $body
    return @($response.data | ForEach-Object { [string]$_ })
}

function Assert-EvalCollectionName {
    param([string]$CollectionName)
    if (-not $CollectionName.StartsWith($EvalCollectionPrefix, [System.StringComparison]::Ordinal)) {
        throw "Refusing to drop non-eval Milvus collection: $CollectionName"
    }
    if ($CollectionName -eq "vortex_memory" -or $CollectionName.StartsWith("vortex_memory_it_", [System.StringComparison]::Ordinal)) {
        throw "Refusing to drop protected Milvus collection: $CollectionName"
    }
}

function Drop-MilvusCollection {
    param([string]$CollectionName)
    Assert-EvalCollectionName -CollectionName $CollectionName
    $body = @{
        collectionName = $CollectionName
    }
    if (-not [string]::IsNullOrWhiteSpace($Database)) {
        $body.dbName = $Database
    }
    Invoke-MilvusRequest -Path "/v2/vectordb/collections/drop" -Body $body | Out-Null
}

Write-Host "Inspecting Milvus collections"
Write-Host "  Endpoint : $MilvusEndpoint"
Write-Host "  Database : $Database"
Write-Host "  Prefix   : $EvalCollectionPrefix"
Write-Host "  Mode     : $(if ($Execute) { 'execute' } else { 'dry-run' })"

$collectionsBefore = @(Get-MilvusCollections)
$evalCollections = @($collectionsBefore |
    Where-Object { $_.StartsWith($EvalCollectionPrefix, [System.StringComparison]::Ordinal) } |
    Sort-Object)
$protectedCollections = @($collectionsBefore |
    Where-Object { $_ -eq "vortex_memory" -or $_.StartsWith("vortex_memory_it_", [System.StringComparison]::Ordinal) } |
    Sort-Object)

Write-Host "  Total collections before : $($collectionsBefore.Count)"
Write-Host "  Eval collections matched : $($evalCollections.Count)"
Write-Host "  Protected collections    : $($protectedCollections.Count)"

if ($evalCollections.Count -eq 0) {
    Write-Host "No eval collections matched. Nothing to clean."
    [pscustomobject]@{
        Endpoint = $MilvusEndpoint
        Database = $Database
        Execute = [bool]$Execute
        TotalBefore = $collectionsBefore.Count
        EvalMatched = 0
        Dropped = 0
        TotalAfter = $collectionsBefore.Count
        EvalRemaining = 0
    }
    return
}

if ($evalCollections.Count -gt $MaxDeleteCount) {
    throw "Refusing to process $($evalCollections.Count) eval collections because MaxDeleteCount is $MaxDeleteCount"
}

Write-Host "Eval collections:"
$evalCollections | ForEach-Object { Write-Host "  $_" }

$dropped = New-Object System.Collections.Generic.List[string]
if ($Execute) {
    foreach ($collection in $evalCollections) {
        Write-Host "Dropping $collection"
        Drop-MilvusCollection -CollectionName $collection
        $dropped.Add($collection) | Out-Null
    }
} else {
    Write-Host "Dry-run only. Re-run with -Execute to drop the matched eval collections."
}

$collectionsAfter = if ($Execute) { @(Get-MilvusCollections) } else { $collectionsBefore }
$evalRemaining = @($collectionsAfter |
    Where-Object { $_.StartsWith($EvalCollectionPrefix, [System.StringComparison]::Ordinal) })

Write-Host "  Total collections after  : $($collectionsAfter.Count)"
Write-Host "  Eval collections after   : $($evalRemaining.Count)"
Write-Host "  Dropped collections      : $($dropped.Count)"

[pscustomobject]@{
    Endpoint = $MilvusEndpoint
    Database = $Database
    Execute = [bool]$Execute
    TotalBefore = $collectionsBefore.Count
    EvalMatched = $evalCollections.Count
    Dropped = $dropped.Count
    TotalAfter = $collectionsAfter.Count
    EvalRemaining = $evalRemaining.Count
    DroppedCollections = @($dropped)
    ProtectedCollections = $protectedCollections
}
