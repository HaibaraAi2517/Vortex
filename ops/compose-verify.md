# Docker Compose Verification

## Goal

Verify the compose-backed runtime in two ways:

- default automated regression via Maven
- optional manual startup plus API walkthrough

## Steps

### Recommended: default automated regression

Run:

```bash
mvn verify -pl vortex-app -am
```

This will automatically:

- bring `docker-compose.yml` dependencies up
- wait for them to become healthy
- run the default app integration tests
- tear the stack down again

### Optional: manual startup and walkthrough

1. Start dependencies and wait for health:

```bash
bash ops/compose-up.sh
```

2. Start the application:

```bash
mvn spring-boot:run -pl vortex-app
```

3. Run the API walkthrough:

```bash
BASE_URL=http://localhost:8080 bash ops/demo.sh
```

## Health Endpoints

- `http://localhost:2379/health` for etcd
- `http://localhost:9000/minio/health/live` for MinIO
- `http://localhost:9091/healthz` for Milvus
- `docker compose exec -T redis redis-cli ping` for Redis
