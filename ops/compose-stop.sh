#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "${ROOT_DIR}"

echo "Stopping docker compose services without removing containers"
docker compose stop

cat <<'EOF'

Services are stopped, but containers are still preserved.
You can start them again from Docker Desktop or with:
  docker compose start
EOF
