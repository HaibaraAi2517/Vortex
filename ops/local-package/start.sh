#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
port="${VORTEX_HTTP_PORT:-8080}"
export VORTEX_DATA_DIR="${VORTEX_DATA_DIR:-./data/vortex}"
echo "Vortex: http://127.0.0.1:$port/"
echo "Data: $VORTEX_DATA_DIR"
exec java --enable-preview -jar vortex-app.jar --spring.profiles.active=local --server.address=127.0.0.1 "--server.port=$port" "--management.server.port=$port"
