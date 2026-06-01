#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "${ROOT_DIR}"

echo "Starting docker compose services"
docker compose up -d

cat <<'EOF'

Services are running.

To stop and keep containers visible in Docker Desktop:
  docker compose stop

To remove containers from Docker Desktop:
  docker compose down
EOF
