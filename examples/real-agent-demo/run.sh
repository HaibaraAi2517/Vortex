#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
POM_PATH="$SCRIPT_DIR/pom.xml"
START_QUICKSTART="${START_QUICKSTART:-false}"
VORTEX_BASE_URL="${VORTEX_BASE_URL:-http://127.0.0.1:${VORTEX_HTTP_PORT:-8080}}"
MODEL_BASE_URL="${MODEL_BASE_URL:-https://api.deepseek.com/v1}"
MODEL_NAME="${MODEL_NAME:-deepseek-chat}"
TIMEOUT_SECONDS="${DEMO_TIMEOUT_SECONDS:-300}"

if [[ -z "${MODEL_API_KEY:-}" ]]; then
  echo "ERROR: set MODEL_API_KEY. For a local compatible endpoint, use a placeholder such as ollama." >&2
  exit 1
fi

RUN_ID="$(openssl rand -hex 6)"
VORTEX_NAMESPACE="${VORTEX_NAMESPACE:-quickstart-real-agent-$(date +%Y%m%d%H%M%S)-$RUN_ID}"
STATE_FILE="${TMPDIR:-/tmp}/vortex-real-agent-$RUN_ID.json"
PHASE_ONE_PID=""

cleanup() {
  if [[ -n "$PHASE_ONE_PID" ]] && kill -0 "$PHASE_ONE_PID" >/dev/null 2>&1; then
    kill -9 "$PHASE_ONE_PID" >/dev/null 2>&1 || true
  fi
  rm -f "$STATE_FILE" "$STATE_FILE.pending"
}
trap cleanup EXIT

if [[ "$START_QUICKSTART" == "true" ]]; then
  export VORTEX_SECURITY_BEARER_TOKEN="${VORTEX_SECURITY_BEARER_TOKEN:-$(openssl rand -hex 32)}"
  export MINIO_ROOT_USER="${MINIO_ROOT_USER:-vortex-local}"
  export MINIO_ROOT_PASSWORD="${MINIO_ROOT_PASSWORD:-$(openssl rand -hex 24)}"
  export REDIS_PASSWORD="${REDIS_PASSWORD:-$(openssl rand -hex 24)}"
  export VORTEX_SECURITY_NAMESPACE_PATTERNS="${VORTEX_SECURITY_NAMESPACE_PATTERNS:-quickstart-*}"
  echo "=== Starting the Vortex quickstart stack ==="
  (cd "$REPO_ROOT" && docker compose -f docker-compose.quickstart.yml up --build -d)
fi

if [[ -z "${VORTEX_SECURITY_BEARER_TOKEN:-}" ]]; then
  echo "ERROR: set VORTEX_SECURITY_BEARER_TOKEN or run with START_QUICKSTART=true." >&2
  exit 1
fi

echo "=== Waiting for Vortex ==="
deadline=$((SECONDS + TIMEOUT_SECONDS))
until curl -fsS "$VORTEX_BASE_URL/actuator/health" | grep -q '"status":"UP"'; do
  if (( SECONDS >= deadline )); then
    echo "ERROR: Vortex did not become healthy at $VORTEX_BASE_URL." >&2
    exit 1
  fi
  sleep 2
done

export VORTEX_BASE_URL VORTEX_NAMESPACE MODEL_BASE_URL MODEL_API_KEY MODEL_NAME
export DEMO_RUN_ID="$RUN_ID"
export DEMO_STATE_FILE="$STATE_FILE"
export DEMO_REPOSITORY_ROOT="$REPO_ROOT"
export DEMO_MODE="phase1"

echo "=== Phase 1: real model, tools, memory, and checkpoint ==="
(cd "$REPO_ROOT" && exec mvn -q -f "$POM_PATH" exec:java) &
PHASE_ONE_PID=$!

deadline=$((SECONDS + TIMEOUT_SECONDS))
while [[ ! -f "$STATE_FILE" ]]; do
  if ! kill -0 "$PHASE_ONE_PID" >/dev/null 2>&1; then
    wait "$PHASE_ONE_PID" || true
    echo "ERROR: phase-one Agent exited before writing checkpoint state." >&2
    exit 1
  fi
  if (( SECONDS >= deadline )); then
    echo "ERROR: timed out waiting for the phase-one checkpoint." >&2
    exit 1
  fi
  sleep 0.5
done

echo "=== Crash injection ==="
echo "Checkpoint is durable. Killing phase-one PID $PHASE_ONE_PID."
kill -9 "$PHASE_ONE_PID" >/dev/null 2>&1 || true
wait "$PHASE_ONE_PID" >/dev/null 2>&1 || true
PHASE_ONE_PID=""

echo "=== Phase 2: recover and continue in a new process ==="
export DEMO_MODE="phase2"
(cd "$REPO_ROOT" && mvn -q -f "$POM_PATH" exec:java)

echo "=== One-click demo finished ==="
echo "The Vortex stack remains running for inspection."
