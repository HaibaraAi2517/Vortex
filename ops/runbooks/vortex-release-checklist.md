# Vortex Release Checklist

Create one copy of this checklist for every candidate. A candidate with any open
P0 item is `NO-GO`.

- [ ] Candidate commit is clean; tag, POM version, OpenAPI version, image tag, and release notes match.
- [ ] `mvn -B clean verify -Pdist` passes from a clean worktree.
- [ ] `./ops/run-integration-tests.ps1` passes without changing an existing Compose project.
- [ ] Quickstart passes on the supported Windows, Linux, and macOS matrix.
- [ ] Unauthenticated business, Swagger, metrics, and detailed management requests are rejected.
- [ ] Cross-namespace read, write, delete, task, recovery, and listing attempts are rejected.
- [ ] Request limits, 413 handling, 5xx redaction, rate limiting, and audit events are verified.
- [ ] Execution ID completion-write failure and restart/TTL races have recorded results.
- [ ] WAL, DLQ, processed keys, checkpoints, and Redis records survive container replacement.
- [ ] Versioned OCI image and executable JAR work in a clean environment.
- [ ] Java and image SBOMs, SHA-256 checksums, image digest, signature, license, and third-party notices exist.
- [ ] Backup, restore, schema migration, and rollback drills pass.

```text
Release candidate:
Commit SHA:
Tag:
Date:
Operator:
OS / Docker / Java / Maven:

Unit/package result:
Integration result:
Quickstart result:
Security result:
Execution-ID fault result:
Backup/restore result:
Artifact pull result:
License review result:

Known exceptions:
Evidence paths:
Final decision: GO / NO-GO
```
