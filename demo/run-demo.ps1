$ErrorActionPreference = "Stop"
$scriptRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$projectRoot = Resolve-Path (Join-Path $scriptRoot "..")

function Wait-HttpOk {
  param(
    [Parameter(Mandatory = $true)][string]$Url,
    [int]$TimeoutSeconds = 120,
    [int]$IntervalSeconds = 2
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    try {
      Invoke-RestMethod -Uri $Url -Method Get -TimeoutSec 5 | Out-Null
      return
    } catch {
      Start-Sleep -Seconds $IntervalSeconds
    }
  }

  throw "Timed out waiting for $Url"
}

function Wait-PortOpen {
  param(
    [Parameter(Mandatory = $true)][string]$Host,
    [Parameter(Mandatory = $true)][int]$Port,
    [int]$TimeoutSeconds = 120
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  while ((Get-Date) -lt $deadline) {
    try {
      $client = [System.Net.Sockets.TcpClient]::new()
      $async = $client.BeginConnect($Host, $Port, $null, $null)
      if ($async.AsyncWaitHandle.WaitOne(2000) -and $client.Connected) {
        $client.Close()
        return
      }
      $client.Close()
    } catch {
    }
    Start-Sleep -Seconds 2
  }

  throw "Timed out waiting for $Host`:$Port"
}

Write-Host "=== Vortex Demo: AI Agent Memory Kernel ==="

Write-Host "[1/7] Starting infrastructure with docker compose"
Push-Location $projectRoot
try {
  docker compose up -d

  Write-Host "Waiting for MinIO and Milvus..."
  Wait-HttpOk -Url "http://localhost:9000/minio/health/live" -TimeoutSeconds 120
  Wait-HttpOk -Url "http://localhost:9091/healthz" -TimeoutSeconds 180

  Write-Host "[2/7] Building application jar"
  mvn -q -DskipTests package -pl vortex-app -am

  $env:MILVUS_HOST = "localhost"
  $env:MILVUS_PORT = "19530"
  $env:MINIO_ENDPOINT = "http://localhost:9000"
  $env:MINIO_ACCESS_KEY = "minioadmin"
  $env:MINIO_SECRET_KEY = "minioadmin"
  $env:MINIO_BUCKET = "vortex"
  $env:BGE_MODEL_PATH = if ($env:BGE_MODEL_PATH) { $env:BGE_MODEL_PATH } else {
    (Join-Path $projectRoot "models\bge-small-zh")
  }

  $jarPath = Join-Path $projectRoot "vortex-app\target\vortex-app-0.2.0-exec.jar"
  if (-not (Test-Path $jarPath)) {
    throw "Jar not found: $jarPath"
  }

  Write-Host "[3/7] Starting Vortex application"
  $appProcess = Start-Process `
    -FilePath "java" `
    -ArgumentList "-jar", $jarPath `
    -PassThru `
    -WindowStyle Hidden

  try {
    Wait-HttpOk -Url "http://localhost:8080/actuator/health" -TimeoutSeconds 120
    Wait-PortOpen -Host "localhost" -Port 8080 -TimeoutSeconds 30

    Write-Host "[4/7] Storing demo memories"
    $storeBody = @{
      content = "Vortex uses three-tier memory: L1 Caffeine for hot cache, L2 Milvus for vector retrieval, L3 MinIO for cold durable storage."
      namespace = "demo"
      tags = @("architecture", "memory")
    } | ConvertTo-Json
    $storeResponse = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/memory/store" -Method Post -ContentType "application/json" -Body $storeBody
    $storeResponse | ConvertTo-Json -Depth 6

    Write-Host "[5/7] Running semantic recall"
    $recallBody = @{
      query = "What storage layers does Vortex use?"
      namespace = "demo"
      topK = 3
      tokenBudget = 512
    } | ConvertTo-Json
    $recallResponse = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/memory/recall" -Method Post -ContentType "application/json" -Body $recallBody
    $recallResponse | ConvertTo-Json -Depth 8

    Write-Host "[6/7] Creating task, checkpointing, recovering"
    $task = Invoke-RestMethod -Uri "http://localhost:8080/api/v1/tasks" -Method Post -ContentType "application/json" -Body (@{
      description = "Demo reasoning task"
      namespace = "demo"
    } | ConvertTo-Json)
    Write-Host ("Created task: {0}" -f $task.taskId)

    Invoke-RestMethod -Uri ("http://localhost:8080/api/v1/tasks/{0}/nodes" -f $task.taskId) -Method Post -ContentType "application/json" -Body (@{
      type = "THOUGHT"
      content = "Analyzing the request..."
    } | ConvertTo-Json) | Out-Null

    $checkpoint = Invoke-RestMethod -Uri ("http://localhost:8080/api/v1/tasks/{0}/checkpoint" -f $task.taskId) -Method Post
    $checkpoint | ConvertTo-Json -Depth 6

    $recovered = Invoke-RestMethod -Uri ("http://localhost:8080/api/v1/tasks/{0}/recover" -f $task.taskId) -Method Post -ContentType "application/json" -Body (@{
      checkpointId = $checkpoint.checkpointId
    } | ConvertTo-Json)
    $recovered | ConvertTo-Json -Depth 8

    Write-Host "[7/7] Reading health"
    Invoke-RestMethod -Uri "http://localhost:8080/api/v1/memory/health" -Method Get | ConvertTo-Json -Depth 6

    Write-Host "Demo complete. Run 'docker compose down' when finished."
  } finally {
    if ($appProcess -and !$appProcess.HasExited) {
      Stop-Process -Id $appProcess.Id -Force
    }
  }
} finally {
  Pop-Location
}
