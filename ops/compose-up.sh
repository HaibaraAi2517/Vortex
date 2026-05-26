#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

wait_http() {
  local name="$1"
  local url="$2"
  local attempts="${3:-60}"
  local delay="${4:-2}"
  echo "Waiting for ${name} at ${url}"
  for ((i=1; i<=attempts; i++)); do
    if curl -fsS "$url" >/dev/null 2>&1; then
      echo "${name} is healthy"
      return 0
    fi
    sleep "${delay}"
  done
  echo "${name} did not become healthy: ${url}" >&2
  exit 1
}

require_command docker
require_command curl

cd "${ROOT_DIR}"

echo "Starting docker compose dependencies"
docker compose up -d

wait_http "etcd" "http://localhost:2379/health"
wait_http "MinIO" "http://localhost:9000/minio/health/live"
wait_http "Milvus" "http://localhost:9091/healthz" 90 3

cat <<'EOF'

Compose dependencies are ready.

Next steps:
1. Start the application:
   mvn spring-boot:run -pl vortex-app
2. Verify observability endpoints:
   curl http://localhost:8080/api/v1/memory/health
   curl http://localhost:8080/api/v1/memory/health/catalog
   curl http://localhost:8080/actuator/prometheus
3. Run the API walkthrough:
   BASE_URL=http://localhost:8080 bash ops/demo.sh

For the default automated regression, you usually do not need this script:
   mvn verify -pl vortex-app -am
EOF
