param(
  [ValidateSet("demo", "worker")]
  [string]$Mode = "demo",
  [string]$BaseUrl = $(if ($env:VORTEX_BASE_URL) { $env:VORTEX_BASE_URL } else { "http://localhost:8080" }),
  [string]$Namespace = "",
  [string]$StateFile = "",
  [switch]$StartQuickstart
)

$ErrorActionPreference = "Stop"

function Write-Section {
  param([Parameter(Mandatory = $true)][string]$Text)
  Write-Host ""
  Write-Host "== $Text =="
}

function Invoke-VortexJson {
  param(
    [Parameter(Mandatory = $true)][ValidateSet("Get", "Post", "Put", "Delete")][string]$Method,
    [Parameter(Mandatory = $true)][string]$Path,
    [object]$Body = $null,
    [int]$TimeoutSeconds = 60
  )

  $uri = "$BaseUrl$Path"
  if ($null -eq $Body) {
    return Invoke-RestMethod -Uri $uri -Method $Method -TimeoutSec $TimeoutSeconds
  }

  $json = $Body | ConvertTo-Json -Depth 10
  return Invoke-RestMethod -Uri $uri -Method $Method -ContentType "application/json" -Body $json -TimeoutSec $TimeoutSeconds
}

function Wait-Vortex {
  param([int]$TimeoutSeconds = 120)

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    try {
      $health = Invoke-VortexJson -Method Get -Path "/actuator/health" -TimeoutSeconds 5
      if ($health.status -eq "UP") {
        return
      }
    } catch {
    }
    Start-Sleep -Seconds 2
  }

  throw "Vortex is not reachable at $BaseUrl. Start it with: docker compose -f docker-compose.quickstart.yml up --build -d"
}

function Get-FragmentContents {
  param([object]$RecallResponse)

  $contents = @()
  foreach ($item in @($RecallResponse.fragments)) {
    if ($null -ne $item.fragment -and $item.fragment.content) {
      $contents += $item.fragment.content
    } elseif ($item.content) {
      $contents += $item.content
    }
  }
  return $contents
}

function Invoke-RecallWithRetry {
  param(
    [Parameter(Mandatory = $true)][string]$Query,
    [Parameter(Mandatory = $true)][string]$Namespace
  )

  for ($attempt = 1; $attempt -le 10; $attempt++) {
    $recall = Invoke-VortexJson -Method Post -Path "/api/v1/memory/recall" -Body @{
      query = $Query
      namespace = $Namespace
      topK = 5
      tokenBudget = 512
    } -TimeoutSeconds 120

    if (@($recall.fragments).Count -gt 0) {
      return $recall
    }

    Start-Sleep -Seconds 1
  }

  throw "Vortex recall returned no fragments for namespace $Namespace"
}

function Invoke-WorkerMode {
  if ([string]::IsNullOrWhiteSpace($Namespace)) {
    throw "Worker mode requires -Namespace."
  }
  if ([string]::IsNullOrWhiteSpace($StateFile)) {
    throw "Worker mode requires -StateFile."
  }

  Wait-Vortex -TimeoutSeconds 120

  $task = Invoke-VortexJson -Method Post -Path "/api/v1/tasks" -Body @{
    description = "Quickstart agent crash recovery demo"
    namespace = $Namespace
  }

  $node = Invoke-VortexJson -Method Post -Path ("/api/v1/tasks/{0}/nodes" -f $task.taskId) -Body @{
    type = "THOUGHT"
    content = "Step 1 finished: inspect the project and prepare the launch checklist."
  }

  $checkpoint = Invoke-VortexJson -Method Post -Path ("/api/v1/tasks/{0}/checkpoint" -f $task.taskId)

  $state = @{
    namespace = $Namespace
    taskId = $task.taskId
    firstNodeId = $node.nodeId
    checkpointId = $checkpoint.checkpointId
  } | ConvertTo-Json -Depth 5

  $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
  [System.IO.File]::WriteAllText($StateFile, $state, $utf8NoBom)

  while ($true) {
    Start-Sleep -Seconds 5
  }
}

if ($Mode -eq "worker") {
  Invoke-WorkerMode
  return
}

$scriptRoot = Split-Path -Parent $PSCommandPath
$repoRoot = Resolve-Path (Join-Path $scriptRoot "..\..")

if ([string]::IsNullOrWhiteSpace($Namespace)) {
  $Namespace = "quickstart-agent-" + (Get-Date -Format "yyyyMMddHHmmss")
}

if ($StartQuickstart) {
  Write-Section "Starting quickstart stack"
  Push-Location $repoRoot
  try {
    docker compose -f docker-compose.quickstart.yml up --build -d
  } finally {
    Pop-Location
  }
}

Wait-Vortex -TimeoutSeconds 180

Write-Host "Vortex base URL: $BaseUrl"
Write-Host "Demo namespace: $Namespace"

Write-Section "1. Memory off vs memory on"
$memory = "Demo session facts: project codename is Aurora Ledger; launch goal is a star-ready GitHub README; preferred stack is Java 21 with Milvus and MinIO."

$store = Invoke-VortexJson -Method Post -Path "/api/v1/memory/store" -Body @{
  content = $memory
  namespace = $Namespace
  tags = @("demo", "memory-on-off")
} -TimeoutSeconds 120

Write-Host ("Stored fragments: {0}" -f $store.count)
Write-Host "Question: What is the project codename and launch goal?"
Write-Host "NO MEMORY: I only see the current question, so I do not know the codename or launch goal."

$recall = Invoke-RecallWithRetry -Query "What is the project codename and launch goal?" -Namespace $Namespace
$contents = Get-FragmentContents -RecallResponse $recall

Write-Host "WITH VORTEX: recalled durable memory:"
foreach ($content in $contents) {
  Write-Host ("- {0}" -f $content)
}

Write-Section "2. Local crash vs Vortex recovery"
$stateFile = Join-Path ([System.IO.Path]::GetTempPath()) ("vortex-quickstart-agent-{0}.json" -f $Namespace)
if (Test-Path -LiteralPath $stateFile) {
  Remove-Item -LiteralPath $stateFile -Force
}

$powerShellExe = if ($PSVersionTable.PSEdition -eq "Core") { "pwsh" } else { "powershell" }
$workerArgs = @(
  "-NoProfile",
  "-ExecutionPolicy", "Bypass",
  "-File", $PSCommandPath,
  "-Mode", "worker",
  "-BaseUrl", $BaseUrl,
  "-Namespace", $Namespace,
  "-StateFile", $stateFile
)

$startParams = @{
  FilePath = $powerShellExe
  ArgumentList = $workerArgs
  PassThru = $true
}
$isWindowsHost = -not (Get-Variable -Name IsWindows -ErrorAction SilentlyContinue) -or $IsWindows
if ($isWindowsHost) {
  $startParams.WindowStyle = "Hidden"
}

$worker = Start-Process @startParams
try {
  $deadline = (Get-Date).AddSeconds(60)
  while ((Get-Date) -lt $deadline -and -not (Test-Path -LiteralPath $stateFile)) {
    if ($worker.HasExited) {
      throw "Worker exited before writing checkpoint state. ExitCode=$($worker.ExitCode)"
    }
    Start-Sleep -Milliseconds 500
  }

  if (-not (Test-Path -LiteralPath $stateFile)) {
    throw "Timed out waiting for worker checkpoint state."
  }

  Write-Host ("Worker process reached checkpoint; killing PID {0}." -f $worker.Id)
  Stop-Process -Id $worker.Id -Force
  $worker.WaitForExit()

  Write-Host "NO VORTEX: a local-only worker lost its in-process task state and would restart at step 1."

  $state = Get-Content -LiteralPath $stateFile -Raw | ConvertFrom-Json
  $recovered = Invoke-VortexJson -Method Post -Path ("/api/v1/tasks/{0}/recover" -f $state.taskId) -Body @{
    checkpointId = $state.checkpointId
  }

  if ($recovered.nodeCount -lt 1) {
    throw "Recovered task did not contain the checkpointed node."
  }

  Write-Host ("WITH VORTEX: recovered task {0} from checkpoint {1}; nodeCount={2}." -f $recovered.taskId, $state.checkpointId, $recovered.nodeCount)

  $resumedNode = Invoke-VortexJson -Method Post -Path ("/api/v1/tasks/{0}/nodes" -f $state.taskId) -Body @{
    type = "ACTION"
    content = "Step 2 resumed after crash: continue from the recovered launch checklist."
  }

  $nextCheckpoint = Invoke-VortexJson -Method Post -Path ("/api/v1/tasks/{0}/checkpoint" -f $state.taskId)
  Write-Host ("Resumed node: {0}" -f $resumedNode.nodeId)
  Write-Host ("Next checkpoint: {0}" -f $nextCheckpoint.checkpointId)
} finally {
  if ($worker -and -not $worker.HasExited) {
    Stop-Process -Id $worker.Id -Force
  }
  if (Test-Path -LiteralPath $stateFile) {
    Remove-Item -LiteralPath $stateFile -Force
  }
}

Write-Section "Demo complete"
Write-Host "No external LLM API key was used."
