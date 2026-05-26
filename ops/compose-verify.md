# Docker Compose Verification

## Goal

Verify the compose-backed runtime in three layers:

1. default automated regression via Maven
2. manual startup plus observability endpoint checks
3. optional API walkthrough

## Steps

### Recommended: default automated regression

Run:

```bash
mvn verify -pl vortex-app -am
```

This will automatically:

- bring `docker-compose.yml` dependencies up
- wait for them to become healthy
- run the default app integration tests, including memory health / catalog / Prometheus consistency checks
- tear the stack down again

### Manual runtime verification

1. Start dependencies and wait for health:

```bash
bash ops/compose-up.sh
```

2. Start the application:

```bash
mvn spring-boot:run -pl vortex-app
```

3. Check the observability surfaces:

```bash
curl http://localhost:8080/api/v1/memory/health
curl http://localhost:8080/api/v1/memory/health/catalog
curl http://localhost:8080/actuator/prometheus
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
