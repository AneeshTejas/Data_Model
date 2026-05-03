# ═════════════════════════════════════════════════════════════════════════════
# Stage 1: builder
#
# The ingestion-service depends on :transform-service (for gRPC stubs), so
# Gradle needs both modules present. We copy the entire project and build
# only the ingestion-service distribution — Gradle compiles transform-service
# automatically as a dependency.
# ═════════════════════════════════════════════════════════════════════════════
# Builder uses Ubuntu (glibc) — required for the protoc-gen-grpc-java native binary.
# See transform-service/Dockerfile for the full explanation.
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
RUN chmod +x gradlew

# Build files first (dependency cache layer — cached until build files change)
COPY settings.gradle.kts build.gradle.kts ./
COPY transform-service/build.gradle.kts transform-service/
COPY ingestion-service/build.gradle.kts ingestion-service/

RUN ./gradlew :ingestion-service:dependencies --no-daemon 2>&1 || true

# Full source (invalidates on source change)
COPY transform-service/src/ transform-service/src/
COPY ingestion-service/src/ ingestion-service/src/

# Same three-step pattern as transform-service/Dockerfile:
# generateProto downloads protoc binaries → chmod makes them executable →
# installDist re-runs generateProto (now succeeds) and builds the distribution.
RUN ./gradlew :transform-service:generateProto --no-daemon 2>&1 || true \
    && find /root/.gradle -name "*.exe" -exec chmod +x {} \; \
    && ./gradlew :ingestion-service:installDist --no-daemon

# ═════════════════════════════════════════════════════════════════════════════
# Stage 2: runtime
# ═════════════════════════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jre-alpine AS runtime

# See transform-service/Dockerfile for the explanation of these flags.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

WORKDIR /app

COPY --from=builder /workspace/ingestion-service/build/install/ingestion-service/ .

# Bake the sample events file into the image so the pipeline has data to process.
# In a real system this would be a volume mount or a Kafka consumer — no file needed.
COPY ingestion-service/sample-events.ndjson ./sample-events.ndjson

# No EXPOSE — this is a one-shot job (runs the pipeline and exits), not a server.
# No HEALTHCHECK — docker-compose depends_on handles startup ordering via the
# postgres and transform-service health checks before this container even starts.

# Config is injected entirely via env vars set in docker-compose.yml:
#   DB_TYPE, DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD
#   TRANSFORM_HOST, TRANSFORM_PORT, USE_GRPC, INPUT_FILE
ENTRYPOINT ["/app/bin/ingestion-service"]
