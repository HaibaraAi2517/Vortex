#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
DEMO_NAMESPACE="${DEMO_NAMESPACE:-demo-$(date +%s)}"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

json_field() {
  local path="$1"
  python - "$path" <<'PY'
import json
import sys

path = sys.argv[1].split(".")
data = json.load(sys.stdin)
value = data
for key in path:
    if isinstance(value, list):
        value = value[int(key)]
    else:
        value = value[key]
if isinstance(value, (dict, list)):
    print(json.dumps(value))
else:
    print(value)
PY
}

post_json() {
  local url="$1"
  local body="$2"
  curl -fsS -H "Content-Type: application/json" -d "$body" "${BASE_URL}${url}"
}

get_json() {
  local url="$1"
  curl -fsS "${BASE_URL}${url}"
}

require_command curl
require_command python

echo "Using BASE_URL=${BASE_URL}"
echo "Using DEMO_NAMESPACE=${DEMO_NAMESPACE}"

memory_store_response="$(post_json "/api/v1/memory/store" "{
  \"content\": \"java checkpoint recover dag node and thread safety walkthrough\",
  \"namespace\": \"${DEMO_NAMESPACE}\",
  \"tags\": [\"demo\", \"memory\"]
}")"
echo "Stored memory fragments: ${memory_store_response}"

task_response="$(post_json "/api/v1/tasks" "{
  \"description\": \"demo task lifecycle\",
  \"namespace\": \"${DEMO_NAMESPACE}\"
}")"
task_id="$(printf '%s' "${task_response}" | json_field "taskId")"
echo "Created task: ${task_id}"

append_response="$(post_json "/api/v1/tasks/${task_id}/nodes" "{
  \"type\": \"THOUGHT\",
  \"content\": \"before-checkpoint\"
}")"
echo "Appended node: ${append_response}"

checkpoint_response="$(curl -fsS -X POST "${BASE_URL}/api/v1/tasks/${task_id}/checkpoint")"
checkpoint_id="$(printf '%s' "${checkpoint_response}" | json_field "checkpointId")"
echo "Checkpoint created: ${checkpoint_id}"

append_after_checkpoint="$(post_json "/api/v1/tasks/${task_id}/nodes" "{
  \"type\": \"ACTION\",
  \"content\": \"after-checkpoint\"
}")"
echo "Appended post-checkpoint node: ${append_after_checkpoint}"

recover_response="$(post_json "/api/v1/tasks/${task_id}/recover" "{
  \"checkpointId\": \"${checkpoint_id}\"
}")"
echo "Recovered task: ${recover_response}"

recall_response="$(post_json "/api/v1/memory/recall" "{
  \"query\": \"java checkpoint recover node\",
  \"namespace\": \"${DEMO_NAMESPACE}\",
  \"topK\": 3,
  \"tokenBudget\": 128,
  \"scenario\": \"chat\"
}")"
recall_session_id="$(printf '%s' "${recall_response}" | json_field "recallSessionId")"
used_fragment_id="$(printf '%s' "${recall_response}" | json_field "fragments.0.fragment.id")"
echo "Recall result: ${recall_response}"

feedback_response="$(post_json "/api/v1/memory/feedback" "{
  \"recallSessionId\": \"${recall_session_id}\",
  \"usedFragmentIds\": [\"${used_fragment_id}\"],
  \"answerAccepted\": true
}")"
echo "Feedback accepted: ${feedback_response}"

dag_response="$(get_json "/api/v1/tasks/${task_id}/dag")"
echo "Current DAG:"
echo "${dag_response}"
