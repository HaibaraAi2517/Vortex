# Vortex Quickstart

This quickstart is the container-first path for trying Vortex without any LLM
API key. It starts Vortex plus Milvus, MinIO, Redis, and etcd.

The Quickstart is intended for a trusted local machine. It binds only the Vortex
API to `127.0.0.1`; Redis, Milvus, MinIO, and their management ports remain on the
Compose network. Business APIs, Swagger, metrics, and detailed management
endpoints require a Bearer token.

## Prerequisites

- Docker Desktop or Docker Engine with Compose
- At least 6 GB available memory for the app and storage services
- Windows: Windows PowerShell 5.1 or later
- Linux/macOS: `bash`, `curl`, and `python3`

## Run The End-To-End Quickstart

PowerShell:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\examples\quickstart-agent\run.ps1 -StartQuickstart
```

Linux/macOS:

```bash
START_QUICKSTART=true bash examples/quickstart-agent/run.sh
```

These commands build and start the stack, wait for Vortex health, then run the
memory recall and worker-recovery demo. When starting the stack, the scripts
generate process-local MinIO, Redis, and Bearer credentials if they are not
already set.

## Start The Stack Only

Create a local secret file and replace every placeholder first:

```powershell
Copy-Item .env.example .env.local
docker compose --env-file .env.local -f docker-compose.quickstart.yml up --build -d --wait
```

Wait until the `vortex` service is ready, then open:

- Swagger UI: `http://localhost:8080/swagger-ui.html` with the configured Bearer token
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
