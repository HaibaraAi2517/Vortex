FROM alpine:3.22.3@sha256:55ae5d250caebc548793f321534bc6a8ef1d116f334f18f4ada1b2daad3251b2

LABEL org.opencontainers.image.title="Vortex MinIO dependency" \
      org.opencontainers.image.version="RELEASE.2024-07-04T14-25-45Z" \
      org.opencontainers.image.source="https://github.com/minio/minio" \
      org.opencontainers.image.licenses="AGPL-3.0-only"

COPY checksums.sha256 /tmp/minio-checksums.sha256

# Keep the existing MinIO version, sourced from its official GitHub release.
# Docker Hub and Quay no longer provide the historical public MinIO image.
RUN set -eu; \
    apk add --no-cache ca-certificates curl; \
    case "$(apk --print-arch)" in \
      x86_64) minio_arch=amd64 ;; \
      aarch64) minio_arch=arm64 ;; \
      *) echo "Unsupported MinIO architecture" >&2; exit 1 ;; \
    esac; \
    minio_file="minio.linux-${minio_arch}.RELEASE.2024-07-04T14-25-45Z"; \
    cd /tmp; \
    curl --fail --show-error --location --retry 3 \
      "https://github.com/minio/minio/releases/download/RELEASE.2024-07-04T14-25-45Z/${minio_file}" \
      --output "${minio_file}"; \
    awk -v name="${minio_file}" '$2 == name { print }' minio-checksums.sha256 > selected-checksum.sha256; \
    test -s selected-checksum.sha256; \
    sha256sum -c selected-checksum.sha256; \
    mv "${minio_file}" /usr/local/bin/minio; \
    chmod 0755 /usr/local/bin/minio; \
    /usr/local/bin/minio --version; \
    rm minio-checksums.sha256 selected-checksum.sha256; \
    mkdir -p /data

EXPOSE 9000 9001
VOLUME ["/data"]
ENTRYPOINT ["/usr/local/bin/minio"]
CMD ["server", "/data", "--console-address", ":9001"]
