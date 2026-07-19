# syntax=docker/dockerfile:1.7
FROM eclipse-temurin:21-jdk-alpine@sha256:1ff763083f2993d57d0bf374ab10bb3e2cb873af6c13a04458ebbd3e0337dc76 AS builder

WORKDIR /workspace
COPY gradle gradle
COPY gradlew settings.gradle.kts build.gradle.kts gradle.lockfile settings-gradle.lockfile ./
COPY src src
COPY config config
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon clean bootJar

FROM eclipse-temurin:21-jre-alpine@sha256:3f08b13888f595cc49edabea7250ba69499ba25602b267da591720769400e08c AS runtime
ARG VERSION=1.0.0
ARG VCS_REF=unknown
ARG BUILD_DATE=unknown

LABEL org.opencontainers.image.title="Kira backend" \
      org.opencontainers.image.description="Signed source-config authority and Kira API" \
      org.opencontainers.image.source="https://github.com/kira-manga/Kira-backend" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${VCS_REF}" \
      org.opencontainers.image.created="${BUILD_DATE}" \
      org.opencontainers.image.licenses="Apache-2.0"

RUN addgroup -S -g 10001 kira && adduser -S -D -H -u 10001 -G kira kira
WORKDIR /app
COPY --from=builder --chown=kira:kira /workspace/build/libs/kira-backend-*.jar /app/app.jar

USER 10001:10001
EXPOSE 8080 9090
ENV SPRING_PROFILES_ACTIVE=prod \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/urandom"

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
  CMD wget -q -O /dev/null http://127.0.0.1:9090/actuator/health/liveness || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
