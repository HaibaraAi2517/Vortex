# Vortex Quickstart

This quickstart is the container-first path for trying Vortex without any LLM
API key. It starts Vortex plus Milvus, MinIO, Redis, and etcd.

Validation status in this branch:

- `docker compose -f docker-compose.quickstart.yml config --quiet` passed.
- Clean one-command startup was verified on 2026-07-26 with `docker compose -f docker-compose.quickstart.yml up --build -d`.
- The quickstart stack started Vortex, Milvus, MinIO, Redis, and etcd from the quickstart compose file.
- `/actuator/health` returned `UP`.
- Memory store/recall was verified through HTTP with one stored fragment and one recalled fragment.
- Task checkpoint/recover was verified through HTTP with one recovered task node.
- Maven tests were not rerun as part of this container quickstart verification.

## Prerequisites

- Docker Desktop or Docker Engine with Compose
- At least 6 GB available memory for the app and storage services

## Start Everything

```powershell
docker compose -f docker-compose.quickstart.yml up --build
```

Wait until the `vortex` service logs show that Spring Boot has started, then
open:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`
- Prometheus: `http://localhost:8080/actuator/prometheus`
- MinIO console: `http://localhost:9001` with `minioadmin` / `minioadmin`

## Run The Agent Demo

With the quickstart stack running, run the focused no-key comparison demo:

```powershell
.\examples\quickstart-agent\run.ps1
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
