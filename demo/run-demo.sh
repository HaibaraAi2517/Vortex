#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

wait_for_url() {
  local url="$1"
  local timeout="${2:-120}"
  local deadline=$((SECONDS + timeout))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if curl -sf "$url" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "ERROR: Timed out waiting for $url" >&2
  exit 1
}

echo "=== Vortex Demo: AI Agent Memory Kernel ==="

echo "[1/7] Starting infrastructure with docker compose"
cd "$PROJECT_ROOT"
docker compose up -d

echo "Waiting for MinIO and Milvus..."
wait_for_url "http://localhost:9000/minio/health/live" 120
wait_for_url "http://localhost:9091/healthz" 180

echo "[2/7] Building application jar"
mvn -q -DskipTests package -pl vortex-app -am

export MILVUS_HOST=localhost
export MILVUS_PORT=19530
export MINIO_ENDPOINT=http://localhost:9000
export MINIO_ACCESS_KEY=minioadmin
export MINIO_SECRET_KEY=minioadmin
export MINIO_BUCKET=vortex
export BGE_MODEL_PATH="${BGE_MODEL_PATH:-$PROJECT_ROOT/models/bge-small-zh}"

JAR_PATH="$PROJECT_ROOT/vortex-app/target/vortex-app-0.1.0-exec.jar"
if [ ! -f "$JAR_PATH" ]; then
  echo "ERROR: Jar not found: $JAR_PATH" >&2
  exit 1
fi

echo "[3/7] Starting Vortex application"
java -jar "$JAR_PATH" &
APP_PID=$!
trap 'kill "$APP_PID" 2>/dev/null || true' EXIT

wait_for_url "http://localhost:8080/actuator/health" 120

echo
echo "[4/7] Storing demo memories"
curl -s -X POST http://localhost:8080/api/v1/memory/store \
  -H 'Content-Type: application/json' \
  -d '{"content":"Vortex uses three-tier memory: L1 Caffeine for hot cache, L2 Milvus for vector retrieval, L3 MinIO for cold durable storage.","namespace":"demo","tags":["architecture","memory"]}' \
  | python3 -m json.tool || true

echo
echo "[5/7] Running semantic recall"
curl -s -X POST http://localhost:8080/api/v1/memory/recall \
  -H 'Content-Type: application/json' \
  -d '{"query":"What storage layers does Vortex use?","namespace":"demo","topK":3,"tokenBudget":512}' \
  | python3 -m json.tool || true

echo
echo "[6/7] Creating task, checkpointing, recovering"
TASK_ID=$(curl -s -X POST http://localhost:8080/api/v1/tasks \
  -H 'Content-Type: application/json' \
  -d '{"description":"Demo reasoning task","namespace":"demo"}' \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['taskId'])")
echo "Created task: $TASK_ID"

curl -s -X POST "http://localhost:8080/api/v1/tasks/$TASK_ID/nodes" \
  -H 'Content-Type: application/json' \
  -d '{"type":"THOUGHT","content":"Analyzing the request..."}' >/dev/null

CHECKPOINT_ID=$(curl -s -X POST "http://localhost:8080/api/v1/tasks/$TASK_ID/checkpoint" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)['checkpointId'])")
echo "Checkpoint: $CHECKPOINT_ID"

curl -s -X POST "http://localhost:8080/api/v1/tasks/$TASK_ID/recover" \
  -H 'Content-Type: application/json' \
  -d "{\"checkpointId\":\"$CHECKPOINT_ID\"}" \
  | python3 -m json.tool || true

echo
echo "[7/7] Reading health"
curl -s http://localhost:8080/api/v1/memory/health | python3 -m json.tool || true

echo
echo "=== Demo complete. Run 'docker compose down' when finished. ==="
