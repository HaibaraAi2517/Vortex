# Vortex Quickstart

This quickstart is the container-first path for trying Vortex without any LLM
API key. It starts Vortex plus Milvus, MinIO, Redis, and etcd.

The Quickstart is intended for a trusted local machine. It binds only the Vortex
API to `127.0.0.1`; Redis, Milvus, MinIO, and their management ports remain on the
Compose network. Swagger UI and its OpenAPI document are anonymous so the
browser can load the documentation. Business APIs, metrics, and detailed
management endpoints require a Bearer token; enter that token through Swagger's
`Authorize` dialog before calling an API.

## Prerequisites

- Docker Desktop or Docker Engine with Compose
- At least 6 GB available memory for the app and storage services
- Windows: Windows PowerShell 5.1 or later
- Linux/macOS: `bash`, `curl`, `python3`, `openssl`, and standard `seq`

## Run The End-To-End Quickstart

PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\examples\quickstart-agent\run.ps1 -StartQuickstart
```

Linux/macOS:

```bash
START_QUICKSTART=true bash examples/quickstart-agent/run.sh
```

These commands build the current checkout and start the stack, wait for Vortex
health, then run the memory recall and worker-recovery demo. When starting the stack, the scripts
generate process-local MinIO, Redis, and Bearer credentials if they are not
already set.

## Start The Stack Only

Create a local secret file and replace every placeholder first:

```powershell
Copy-Item .env.example .env.local
docker compose --env-file .env.local -f docker-compose.quickstart.yml pull
docker compose --env-file .env.local -f docker-compose.quickstart.yml up --no-build -d --wait
```

Compose reads `.env.local` for container interpolation, but it does not export
those values into the current host shell. Load them before running the demo,
curl commands, or Java integration examples.

PowerShell:

```powershell
Get-Content .env.local | ForEach-Object {
  if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2], "Process")
  }
}
```

Bash:

```bash
set -a
. ./.env.local
set +a
```

This path pulls the fixed `ghcr.io/haibaraai2517/vortex:0.2.0` release image
declared by `.env.example`; it does not rebuild application code. To validate a
source checkout instead, run the same `up` command with `--build` in place of
`--no-build`.

Wait until the `vortex` service is ready, then open:

- Swagger UI: `http://localhost:8080/swagger-ui.html`; use `Authorize` before calling an API
- Health: `http://localhost:8080/actuator/health`
- Prometheus: `http://localhost:8080/actuator/prometheus` with the configured Bearer token

MinIO, Redis, Milvus, and the MinIO/Milvus management ports are intentionally not
published by Quickstart.

The default Compose project is `vortex-quickstart`, separate from the development
stack. To run another Quickstart concurrently, set both a unique
`COMPOSE_PROJECT_NAME` and a different `VORTEX_HTTP_PORT`; container-internal
ports do not change.

## Run The Agent Demo

With the quickstart stack running, run the focused no-key comparison demo:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\examples\quickstart-agent\run.ps1
```

```bash
bash examples/quickstart-agent/run.sh
```

The demo deliberately requests `VECTOR_ONLY` with the additional reranker
disabled to keep its historical comparison deterministic. The public Recall
contract defaults to guarded `HYBRID + RRF`; `VECTOR_ONLY` is an explicit
rollback and comparison mode, not the product default.

The demo stores a session memory, recalls it for the memory-on path, kills a worker process after checkpointing step 1, and resumes the task from Vortex recovery state.

## Try Memory Store And Recall

Store one memory:

```bash
curl -X POST http://localhost:8080/api/v1/memory/store \
  -H "Authorization: Bearer $VORTEX_SECURITY_BEARER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "content": "Vortex uses L1 Caffeine, L2 Milvus, and L3 MinIO for tiered agent memory.",
    "namespace": "quickstart-demo",
    "tags": ["architecture", "memory"]
  }'
```

Recall it:

```bash
curl -X POST http://localhost:8080/api/v1/memory/recall \
  -H "Authorization: Bearer $VORTEX_SECURITY_BEARER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Which storage layers does Vortex use?",
    "namespace": "quickstart-demo",
    "topK": 3,
    "tokenBudget": 512
  }'
```

## Try Task Checkpoint And Recovery

Create a task:

```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Authorization: Bearer $VORTEX_SECURITY_BEARER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"description":"Quickstart task","namespace":"quickstart-demo"}'
```

Use the returned `taskId` to add a node, create a checkpoint, and recover:

```bash
curl -X POST http://localhost:8080/api/v1/tasks/<taskId>/nodes \
  -H "Authorization: Bearer $VORTEX_SECURITY_BEARER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"type":"THOUGHT","content":"The task reached step one."}'

curl -X POST http://localhost:8080/api/v1/tasks/<taskId>/checkpoint \
  -H "Authorization: Bearer $VORTEX_SECURITY_BEARER_TOKEN"

curl -X POST http://localhost:8080/api/v1/tasks/<taskId>/recover \
  -H "Authorization: Bearer $VORTEX_SECURITY_BEARER_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"checkpointId":"<checkpointId>"}'
```

## Scripted No-Key Demo

The repository also includes broader host-run demo scripts that start infrastructure,
build the app, store memory, run recall, checkpoint a task, and recover it
without any external LLM API key:

```powershell
.\demo\run-demo.ps1
```

```bash
./demo/run-demo.sh
```

These scripts use the tracked local BGE model and the existing `docker-compose.yml` infrastructure stack.

## Persistent State Ownership

Quickstart stores the Vortex WAL, persistence DLQ, and processed-key state in a
named volume mounted at `/var/lib/vortex`. Before the application starts, the
one-shot `vortex-data-init` service repairs that volume to uid/gid
`10001:10001`. Keep this initializer enabled when upgrading from older images
that ran as root and created root-owned WAL files.

Quickstart also stores the rebuildable semantic page table under the versioned
key `system/semantic-page-table-v2.bin`. Older releases retain their original
key, so rolling back does not make the old Kryo reader consume newer derived
cache data. Memory fragments and checkpoints remain in their existing stores.

## Stop And Clean Up

Stop services:

```powershell
docker compose --env-file .env.local -f docker-compose.quickstart.yml down
```

Remove quickstart volumes:

```powershell
docker compose --env-file .env.local -f docker-compose.quickstart.yml down -v
```

## No API Key Required

This quickstart uses the tracked local BGE model under `models/bge-small-zh/`.
External LLM generation is disabled by default with `VORTEX_GENERATION_ENABLED=false`.
