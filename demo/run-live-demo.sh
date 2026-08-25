#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SCENARIO_SCRIPT="$PROJECT_ROOT/examples/quickstart-agent/run.sh"
BASE_URL="${BASE_URL:-http://localhost:8080}"
RUNS="${RUNS:-1}"
MAX_RUN_SECONDS="${MAX_RUN_SECONDS:-300}"

if ! [[ "$RUNS" =~ ^[1-9][0-9]*$ ]] || [ "$RUNS" -gt 10 ]; then
  echo "ERROR: RUNS must be an integer from 1 to 10." >&2
  exit 1
fi
if ! [[ "$MAX_RUN_SECONDS" =~ ^[0-9]+$ ]] || [ "$MAX_RUN_SECONDS" -lt 30 ] || [ "$MAX_RUN_SECONDS" -gt 300 ]; then
  echo "ERROR: MAX_RUN_SECONDS must be an integer from 30 to 300." >&2
  exit 1
fi

if ! curl -sf "$BASE_URL/actuator/health" \
  | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("status") == "UP" else 1)' >/dev/null; then
  echo "ERROR: Vortex is not ready at $BASE_URL." >&2
  echo "Start the .env.local Quickstart stack first." >&2
  exit 1
fi

echo "=== Vortex live demo ==="
echo "Flow: store memory -> VectorOnly recall -> checkpoint -> kill worker -> recover -> continue"
echo "Run limit: $MAX_RUN_SECONDS seconds per run"

for run in $(seq 1 "$RUNS"); do
  namespace="quickstart-live-demo-$(date +%Y%m%d%H%M%S)-$run-$$-$RANDOM"
  started_at="$(date +%s)"

  echo
  echo "--- Run $run/$RUNS: $namespace ---"
  BASE_URL="$BASE_URL" NAMESPACE="$namespace" bash "$SCENARIO_SCRIPT"

  elapsed_seconds=$(( $(date +%s) - started_at ))
  if [ "$elapsed_seconds" -gt "$MAX_RUN_SECONDS" ]; then
    echo "ERROR: live demo run $run took $elapsed_seconds seconds, exceeding the limit." >&2
    exit 1
  fi

  echo "LIVE DEMO RUN $run PASS in $elapsed_seconds seconds."
done

echo
echo "LIVE DEMO PASS: $RUNS/$RUNS runs completed within $MAX_RUN_SECONDS seconds each."
