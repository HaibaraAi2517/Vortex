# Docker Compose Verification

## Goal

Verify the compose-backed runtime in three layers:

1. default unit/package verification via Maven
2. manual startup plus observability endpoint checks
3. optional API walkthrough

## Steps

### Default verification without Docker lifecycle changes

Run:

```bash
mvn -B verify
```

This runs the normal reactor verification without starting, stopping, or removing
any Compose project.

### Isolated Compose-backed integration verification

```powershell
./ops/run-integration-tests.ps1
```

The script creates a unique `vortex-it-*` Compose project, uses random host ports
and credentials, validates resource ownership labels, and removes only resources
created for that run. Two invocations can run concurrently without sharing
project names, ports, volumes, collections, object prefixes, WAL, or processed keys.

### Manual runtime verification

1. Copy `.env.example` to an ignored `.env.local`, replace the secrets, then start dependencies:

```bash
docker compose --env-file .env.local up -d --wait
```

2. Start the application:

```bash
mvn spring-boot:run -pl vortex-app
```

3. Check the observability surfaces:

```bash
curl -H "Authorization: Bearer $VORTEX_SECURITY_BEARER_TOKEN" http://localhost:8080/api/v1/memory/health
curl -H "Authorization: Bearer $VORTEX_SECURITY_BEARER_TOKEN" http://localhost:8080/api/v1/memory/health/catalog
curl -H "Authorization: Bearer $VORTEX_SECURITY_BEARER_TOKEN" http://localhost:8080/actuator/prometheus
```

Expected checks:

- `/api/v1/memory/health` returns `status`, `summary`, `statusReason`, and `details`
- `/api/v1/memory/health/catalog` returns `dictionaryVersion=memory-health-v2`, `migrationGuide`, and `compatibility`
- `/actuator/prometheus` exposes both `vortex_hmc_slo_checkpoint_recovery_success_rate` and `vortex_hmc_slo_persistence_success_rate`

4. Optional: run the API walkthrough:

```bash
BASE_URL=http://localhost:8080 bash ops/demo.sh
```

## Health Endpoints

- `http://localhost:2379/health` for etcd
- `http://localhost:9000/minio/health/live` for MinIO
- `http://localhost:9091/healthz` for Milvus

## Observability Assets

- Prometheus rules: `ops/prometheus/vortex-memory-slo-alerts.yml`
- Alertmanager route example: `ops/alertmanager/memory-health-routes.yml`
- Grafana query reference: `ops/grafana/memory-health-queries.md`
- Signal runbook: `ops/runbooks/memory-health-signals.md`
- Migration guide: `ops/runbooks/memory-health-migration.md`
