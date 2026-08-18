# Vortex Deployment Operations

## Supported Boundary

The intended distribution is the versioned `vortex-app` OCI image plus the
matching `docker-compose.quickstart.yml`. Until a version appears in a completed
GitHub release and GHCR, build it from the matching clean tag. Quickstart is a trusted-host trial
configuration, not a public multi-tenant deployment. Internet-facing deployments
must terminate TLS before Vortex and replace the shared Bearer token with an
identity-aware gateway or equivalent service authentication.

## Required Secrets And Ports

Start from `.env.example`. Store real values in a secret manager or an ignored
`.env.local`; never bake them into an image or commit them. Quickstart publishes
only `127.0.0.1:${VORTEX_HTTP_PORT}:8080`. Redis, Milvus, MinIO, etcd, and
management ports remain on the Compose network.

The Bearer token is mapped to `VORTEX_SECURITY_NAMESPACE_PATTERNS`. Requests for
a namespace outside those patterns return 403. Use one independently rotated
credential and namespace scope per integration boundary.

## Persistent State Contract

| State | Location | Required persistence |
| --- | --- | --- |
| Vortex WAL | `/var/lib/vortex/wal` | Required for task replay after container replacement |
| Persistence DLQ | `/var/lib/vortex/persistence/dlq.jsonl` | Required until failures are replayed or adjudicated |
| Processed keys | `/var/lib/vortex/persistence/processed-keys.txt` | Required to avoid replaying acknowledged persistence work |
| Execution IDs | Redis append-only data | Required for cross-process replay and `UNKNOWN` state retention |
| Memory fragments/checkpoints | MinIO data | Required |
| Vector collections | Milvus data plus etcd metadata | Required |

Quickstart mounts `/var/lib/vortex` and named volumes for every external store.
Its one-shot `vortex-data-init` service runs as root only long enough to create
the state directories and migrate their ownership to uid/gid `10001:10001`
before the non-root Vortex container starts. Keep this initializer enabled when
upgrading volumes created by pre-hardening images that ran as root.
The semantic page table is derived state and uses a versioned object key. When
its serialized format changes, allocate a new key and rebuild from Milvus;
never overwrite the only key an older rollback image expects to deserialize.
Do not replace a container with anonymous volumes or an empty host directory.
Execution-ID `IN_PROGRESS` reservations intentionally have no automatic TTL;
the configured retention TTL starts after `COMPLETED` or `UNKNOWN`. Investigate
and explicitly adjudicate abandoned `IN_PROGRESS` records rather than deleting
them and automatically repeating an external side effect.

## Backup

Quiesce writes or place the API behind a maintenance response before taking a
coordinated backup. Record the release version, image digest, embedding dimension,
Milvus collection name, checkpoint format, and UTC timestamp with every backup.

1. Redis: set `REDISCLI_AUTH` and run `redis-cli SAVE`, then copy `/data/dump.rdb`
   and the append-only files from the Redis volume.
2. MinIO: use a pinned `mc` client to `mc mirror` the complete Vortex bucket,
   including checkpoint metadata and object versions when enabled.
3. Milvus: use the Milvus Backup tool version compatible with the deployed
   Milvus server. Back up both collection data and etcd metadata; copying a live
   Milvus volume alone is not a supported logical backup.
4. Vortex: after writes stop, archive `/var/lib/vortex` so WAL, DLQ, and processed
   keys share the same backup boundary.
5. Store SHA-256 checksums for every backup artifact and protect backup credentials
   separately from runtime credentials.

## Restore Drill

Restore into an isolated Compose project and unused host port. Do not overwrite a
running environment during a drill.

1. Restore MinIO and verify checkpoint objects can be listed and read.
2. Restore Milvus plus etcd and verify the expected collection schema and row count.
3. Restore Redis and verify execution-ID records, including `COMPLETED`,
   `IN_PROGRESS`, and `UNKNOWN`, survive restart.
4. Restore `/var/lib/vortex` with uid/gid `10001:10001` ownership.
5. Start the exact recorded image digest.
6. Recall known test fragments, recover a checkpointed task, and replay a completed
   Execution ID without executing its side effect again.
7. Record commands, artifact hashes, results, and cleanup evidence in the release
   verification record.

## Schema And Embedding Migration

Never set `MILVUS_DROP_COLLECTION=true` against the only production collection.
For an embedding-dimension or collection-schema change:

1. Create a new versioned collection and object/checkpoint namespace.
2. Re-embed or transform data with a pinned model revision and record model hashes.
3. Validate counts, recall quality, latency, and namespace isolation.
4. Switch readers only after the old and new collections are both recoverable.
5. Retain the old collection through the rollback window, then delete it through a
   separately approved operation.

Checkpoint-format changes require a reader that can load the previous release's
format or an offline conversion with before/after hashes. Treat conversions that
discard fields or change embedding dimensions as irreversible.

## Rollback

Rollback is supported only while the previous image can read the current WAL,
checkpoint, Redis record, and collection formats. Roll back by image digest, not a
floating tag. If a release performed an irreversible migration, restore the full
pre-migration backup instead of starting older code against newer data.
