FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml ./
COPY vortex-common/pom.xml vortex-common/pom.xml
COPY vortex-storage/pom.xml vortex-storage/pom.xml
COPY vortex-kernel/pom.xml vortex-kernel/pom.xml
COPY vortex-app/pom.xml vortex-app/pom.xml

RUN mvn -B -DskipTests dependency:go-offline

COPY vortex-common vortex-common
COPY vortex-storage vortex-storage
COPY vortex-kernel vortex-kernel
COPY vortex-app vortex-app
COPY models models

RUN mvn -B -DskipTests package -pl vortex-app -am

FROM eclipse-temurin:21-jre

WORKDIR /app

COPY --from=build /workspace/vortex-app/target/vortex-app-0.1.0-SNAPSHOT-exec.jar /app/vortex-app.jar
COPY --from=build /workspace/models /app/models

EXPOSE 8080

ENTRYPOINT ["java", "--enable-preview", "-jar", "/app/vortex-app.jar"]
