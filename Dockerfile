FROM python:3.12-slim AS model
WORKDIR /workspace
COPY models/manifest.json models/manifest.json
COPY ops/download-model.py ops/download-model.py
RUN python ops/download-model.py

FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY vortex-common/pom.xml vortex-common/pom.xml
COPY vortex-storage/pom.xml vortex-storage/pom.xml
COPY vortex-kernel/pom.xml vortex-kernel/pom.xml
COPY vortex-langchain4j/pom.xml vortex-langchain4j/pom.xml
COPY vortex-app/pom.xml vortex-app/pom.xml

RUN mvn -B -DskipTests dependency:go-offline

COPY vortex-common vortex-common
COPY vortex-storage vortex-storage
COPY vortex-kernel vortex-kernel
COPY vortex-app vortex-app

RUN mvn -B -DskipTests package -pl vortex-app -am

FROM eclipse-temurin:21-jre

ARG VERSION=0.2.0
ARG VCS_REF=unknown

LABEL org.opencontainers.image.title="Vortex" \
      org.opencontainers.image.description="AI agent memory and state management runtime" \
      org.opencontainers.image.version=$VERSION \
      org.opencontainers.image.revision=$VCS_REF \
      org.opencontainers.image.source="https://github.com/HaibaraAi2517/Vortex" \
      org.opencontainers.image.licenses="Apache-2.0"

WORKDIR /app

RUN groupadd --gid 10001 vortex \
    && useradd --uid 10001 --gid vortex --no-create-home --shell /usr/sbin/nologin vortex \
    && mkdir -p /var/lib/vortex/wal /var/lib/vortex/persistence \
    && chown -R vortex:vortex /app /var/lib/vortex

COPY --from=build --chown=vortex:vortex /workspace/vortex-app/target/vortex-app-*-exec.jar /app/vortex-app.jar
COPY --from=model --chown=vortex:vortex /workspace/models/bge-small-zh /app/models/bge-small-zh

USER 10001:10001

EXPOSE 8080

VOLUME ["/var/lib/vortex"]

ENTRYPOINT ["java", "--enable-preview", "-jar", "/app/vortex-app.jar"]
