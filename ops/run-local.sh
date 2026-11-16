#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -z "${BGE_MODEL_PATH:-}" ]]; then
  "${VORTEX_PYTHON:-python3}" ops/download-model.py
fi
if [[ "${SKIP_BUILD:-false}" != "true" ]]; then
  mvn -B -q -DskipTests package -pl vortex-app -am
fi
shopt -s nullglob
jars=(vortex-app/target/vortex-app-*-exec.jar)
if (( ${#jars[@]} != 1 )); then
  echo 'Expected one application jar; run mvn clean package -pl vortex-app -am first.' >&2
  exit 1
fi
echo "Vortex memory workbench: http://127.0.0.1:${VORTEX_HTTP_PORT:-8080}/"
echo "API documentation: http://127.0.0.1:${VORTEX_HTTP_PORT:-8080}/swagger-ui.html"
echo "Persistent data: ${VORTEX_DATA_DIR:-./data/vortex}"
exec java --enable-preview -jar "${jars[0]}" --spring.profiles.active=local "--server.port=${VORTEX_HTTP_PORT:-8080}"
