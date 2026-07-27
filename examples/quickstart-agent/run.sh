#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BASE_URL="${BASE_URL:-http://localhost:8080}"
MODE="${1:-demo}"

wait_vortex() {
  local timeout="${1:-120}"
  local deadline=$((SECONDS + timeout))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if curl -sf "$BASE_URL/actuator/health" | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("status") == "UP" else 1)' >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "ERROR: Vortex is not reachable at $BASE_URL" >&2
  echo "Start it with: docker compose -f docker-compose.quickstart.yml up --build -d" >&2
  exit 1
}

post_json() {
  local path="$1"
  local body="$2"
  curl -sf -X POST "$BASE_URL$path" -H 'Content-Type: application/json' -d "$body"
}

json_field() {
  local field="$1"
  python3 -c "import json,sys; print(json.load(sys.stdin)$field)"
}

worker_mode() {
  local namespace="$1"
  local state_file="$2"

  wait_vortex 120

  local task_json task_id node_json node_id checkpoint_json checkpoint_id
  task_json="$(post_json /api/v1/tasks "{\"description\":\"Quickstart agent crash recovery demo\",\"namespace\":\"$namespace\"}")"
  task_id="$(printf '%s' "$task_json" | json_field "['taskId']")"

  node_json="$(post_json "/api/v1/tasks/$task_id/nodes" '{"type":"THOUGHT","content":"Step 1 finished: inspect the project and prepare the launch checklist."}')"
  node_id="$(printf '%s' "$node_json" | json_field "['nodeId']")"

  checkpoint_json="$(post_json "/api/v1/tasks/$task_id/checkpoint" '')"
  checkpoint_id="$(printf '%s' "$checkpoint_json" | json_field "['checkpointId']")"

  python3 -c 'import json,sys; json.dump({"namespace":sys.argv[1],"taskId":sys.argv[2],"firstNodeId":sys.argv[3],"checkpointId":sys.argv[4]}, open(sys.argv[5], "w"))' \
    "$namespace" "$task_id" "$node_id" "$checkpoint_id" "$state_file"

  while true; do
    sleep 5
  done
}

if [ "$MODE" = "worker" ]; then
  if [ "$#" -ne 4 ]; then
    echo "ERROR: worker mode expects: worker <base-url> <namespace> <state-file>" >&2
    exit 1
  fi
  BASE_URL="$2"
  worker_mode "$3" "$4"
fi

if [ "${START_QUICKSTART:-false}" = "true" ]; then
  echo
  echo "== Starting quickstart stack =="
  (cd "$PROJECT_ROOT" && docker compose -f docker-compose.quickstart.yml up --build -d)
fi

wait_vortex 180

NAMESPACE="quickstart-agent-$(date +%Y%m%d%H%M%S)"
STATE_FILE="${TMPDIR:-/tmp}/vortex-quickstart-agent-$NAMESPACE.json"

cleanup() {
  if [ -n "${WORKER_PID:-}" ]; then
    kill "$WORKER_PID" >/dev/null 2>&1 || true
  fi
  rm -f "$STATE_FILE"
}
trap cleanup EXIT

echo "Vortex base URL: $BASE_URL"
echo "Demo namespace: $NAMESPACE"

echo
echo "== 1. Memory off vs memory on =="
MEMORY='Demo session facts: project codename is Aurora Ledger; launch goal is a star-ready GitHub README; preferred stack is Java 21 with Milvus and MinIO.'
STORE_JSON="$(post_json /api/v1/memory/store "{\"content\":\"$MEMORY\",\"namespace\":\"$NAMESPACE\",\"tags\":[\"demo\",\"memory-on-off\"]}")"
echo "Stored fragments: $(printf '%s' "$STORE_JSON" | json_field "['count']")"
echo "Question: What is the project codename and launch goal?"
echo "NO MEMORY: I only see the current question, so I do not know the codename or launch goal."

RECALL_JSON="$(post_json /api/v1/memory/recall "{\"query\":\"What is the project codename and launch goal?\",\"namespace\":\"$NAMESPACE\",\"topK\":5,\"tokenBudget\":512}")"
RECALL_COUNT="$(printf '%s' "$RECALL_JSON" | python3 -c 'import json,sys; print(len(json.load(sys.stdin).get("fragments", [])))')"
if [ "$RECALL_COUNT" -eq 0 ]; then
  echo "ERROR: Vortex recall returned no fragments for namespace $NAMESPACE" >&2
  exit 1
fi

echo "WITH VORTEX: recalled durable memory:"
printf '%s' "$RECALL_JSON" | python3 -c 'import json,sys
payload=json.load(sys.stdin)
for item in payload.get("fragments", []):
    fragment=item.get("fragment") or item
    content=fragment.get("content")
    if content:
        print(f"- {content}")'

echo
echo "== 2. Local crash vs Vortex recovery =="
bash "$SCRIPT_DIR/run.sh" worker "$BASE_URL" "$NAMESPACE" "$STATE_FILE" &
WORKER_PID=$!

for _ in $(seq 1 120); do
  if [ -f "$STATE_FILE" ]; then
    break
  fi
  if ! kill -0 "$WORKER_PID" >/dev/null 2>&1; then
    echo "ERROR: worker exited before writing checkpoint state" >&2
    exit 1
  fi
  sleep 0.5
done

if [ ! -f "$STATE_FILE" ]; then
  echo "ERROR: timed out waiting for worker checkpoint state" >&2
  exit 1
fi

echo "Worker process reached checkpoint; killing PID $WORKER_PID."
kill -9 "$WORKER_PID" >/dev/null 2>&1 || true
wait "$WORKER_PID" >/dev/null 2>&1 || true
unset WORKER_PID

echo "NO VORTEX: a local-only worker lost its in-process task state and would restart at step 1."
TASK_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["taskId"])' "$STATE_FILE")"
CHECKPOINT_ID="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["checkpointId"])' "$STATE_FILE")"
RECOVER_JSON="$(post_json "/api/v1/tasks/$TASK_ID/recover" "{\"checkpointId\":\"$CHECKPOINT_ID\"}")"
NODE_COUNT="$(printf '%s' "$RECOVER_JSON" | json_field "['nodeCount']")"
if [ "$NODE_COUNT" -lt 1 ]; then
  echo "ERROR: recovered task did not contain the checkpointed node" >&2
  exit 1
fi

echo "WITH VORTEX: recovered task $TASK_ID from checkpoint $CHECKPOINT_ID; nodeCount=$NODE_COUNT."
RESUMED_NODE_JSON="$(post_json "/api/v1/tasks/$TASK_ID/nodes" '{"type":"ACTION","content":"Step 2 resumed after crash: continue from the recovered launch checklist."}')"
NEXT_CHECKPOINT_JSON="$(post_json "/api/v1/tasks/$TASK_ID/checkpoint" '')"
echo "Resumed node: $(printf '%s' "$RESUMED_NODE_JSON" | json_field "['nodeId']")"
echo "Next checkpoint: $(printf '%s' "$NEXT_CHECKPOINT_JSON" | json_field "['checkpointId']")"

echo
echo "== Demo complete =="
echo "No external LLM API key was used."
