# MinIO dependency image

The distributed development, integration-test and Quickstart stacks build this
image locally. The MinIO version remains `RELEASE.2024-07-04T14-25-45Z`; this change
replaces an unavailable image distribution, not the storage implementation.

On 2026-11-17, Docker Hub returned 404 for `minio/minio`; Quay's anonymous token
had no pull permission and its same-tag manifest returned 401. The official
[MinIO repository](https://github.com/minio/minio) now documents source-only
distribution. The old `dl.min.io` archive URLs returned 410. Consequently this
recipe downloads from the **official historical GitHub release**, not a mirror:

- [linux/amd64 binary](https://github.com/minio/minio/releases/download/RELEASE.2024-07-04T14-25-45Z/minio.linux-amd64.RELEASE.2024-07-04T14-25-45Z)
- [linux/arm64 binary](https://github.com/minio/minio/releases/download/RELEASE.2024-07-04T14-25-45Z/minio.linux-arm64.RELEASE.2024-07-04T14-25-45Z)
- [Original source and release](https://github.com/minio/minio/releases/tag/RELEASE.2024-07-04T14-25-45Z)

Both files were downloaded and their hashes independently recomputed against the
corresponding official `.sha256sum` release assets. The fixed results are in
[`checksums.sha256`](checksums.sha256); the Docker build verifies these committed
values rather than trusting a newly downloaded checksum at build time. Only
Linux amd64 and arm64 are supported by this recipe. The official Alpine 3.22.3
base is pinned to its multi-platform OCI index digest; curl and CA certificates
are installed from Alpine's package repositories. Existing curl health checks
continue to work.

Compose builds the local `vortex-minio:RELEASE.2024-07-04T14-25-45Z` image. It is
not published to a registry. `FullLifecycleIT` builds the same two-file recipe
through Testcontainers `ImageFromDockerfile`, so it needs no preloaded MinIO image.

For a release-image Quickstart that uses `up --no-build`, build this dependency
first, using the same environment file as the eventual startup:

```sh
docker compose --env-file .env.local -f docker-compose.quickstart.yml build minio
docker compose --env-file .env.local -f docker-compose.quickstart.yml up --no-build -d --wait
```

The first build requires access to Docker Hub's official Alpine image, Alpine
package repositories, and GitHub release assets. Subsequent builds can reuse the
Docker cache. This preserves a historical compatibility dependency; it is not a
claim that the 2024 MinIO release is current or receives upstream updates.
