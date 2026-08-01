# Vortex Quickstart

This quickstart is the container-first path for trying Vortex without any LLM
API key. It starts Vortex plus Milvus, MinIO, Redis, and etcd.

Validation status for `v0.1.0` on 2026-08-01:

- The exact README PowerShell Quickstart command completed successfully.
- Docker built `vortex-app-0.1.0-exec.jar` and started Vortex, Milvus, MinIO,
  Redis, and etcd.
- `/actuator/health` returned `UP`.
- The demo stored and recalled one durable memory fragment.
- A worker was killed after checkpointing, then the task recovered with
  `nodeCount=1` and continued to the next checkpoint.
- `mvn -B clean verify` passed `548` tests with zero failures, errors, or skips.
- JaCoCo reported `74.25%` aggregate line coverage across the five code modules.

## Prerequisites

- Docker Desktop or Docker Engine with Compose
- At least 6 GB available memory for the app and storage services

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
memory recall and worker-recovery demo.

## Start The Stack Only

```powershell
docker compose -f docker-compose.quickstart.yml up --build -d
```

Wait until the `vortex` service is ready, then open:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`
- Prometheus: `http://localhost:8080/actuator/prometheus`
- MinIO console: `http://localhost:9001` with `minioadmin` / `minioadmin`

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
  -H "Content-Type: application/json" \
  -d '{
    "content": "Vortex uses L1 Caffeine, L2 Milvus, and L3 MinIO for tiered agent memory.",
    "namespace": "quickstart",
    "tags": ["architecture", "memory"]
  }'
```

Recall it:

```bash
curl -X POST http://localhost:8080/api/v1/memory/recall \
  -H "Content-Type: application/json" \
  -d '{
    "query": "Which storage layers does Vortex use?",
    "namespace": "quickstart",
    "topK": 3,
    "tokenBudget": 512
  }'
```

## Try Task Checkpoint And Recovery

Create a task:

```bash
curl -X POST http://localhost:8080/api/v1/tasks \
  -H "Content-Type: application/json" \
  -d '{"description":"Quickstart task","namespace":"quickstart"}'
```

Use the returned `taskId` to add a node, create a checkpoint, and recover:

```bash
curl -X POST http://localhost:8080/api/v1/tasks/<taskId>/nodes \
  -H "Content-Type: application/json" \
  -d '{"type":"THOUGHT","content":"The task reached step one."}'

curl -X POST http://localhost:8080/api/v1/tasks/<taskId>/checkpoint

curl -X POST http://localhost:8080/api/v1/tasks/<taskId>/recover \
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
docker compose -f docker-compose.quickstart.yml down
```

Remove quickstart volumes:

```powershell
docker compose -f docker-compose.quickstart.yml down -v
```

## No API Key Required

This quickstart uses the tracked local BGE model under `models/bge-small-zh/`.
External LLM generation is disabled by default with `VORTEX_GENERATION_ENABLED=false`.
