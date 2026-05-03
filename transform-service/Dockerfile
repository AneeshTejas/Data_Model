# ARG declared before any FROM is a "global" ARG — visible in FROM value
# expressions and re-declared within each stage to be usable in RUN commands.
ARG BUILD_TOOL=gradle

# ═════════════════════════════════════════════════════════════════════════════
# Stage 1: builder
#
# WHY maven:3.9-eclipse-temurin-21 instead of the previous eclipse-temurin:21-jdk?
# This image bundles both Maven 3.9 and JDK 21 on Ubuntu Jammy (glibc), giving
# us a single builder image that supports either build tool:
#   • Gradle path: the project's gradlew wrapper is self-contained — no install
#   • Maven path:  the pre-installed `mvn` binary is already on PATH
#
# WHY NOT alpine here:
# The protoc-gen-grpc-java native binary is a glibc ELF. Alpine uses musl libc
# so the OS can't find the dynamic linker (/lib/ld-linux-x86-64.so.2) and
# refuses to execute it — even with chmod +x. Ubuntu Jammy has glibc, so protoc
# runs fine. The runtime stage stays Alpine — protoc is never needed at runtime.
# ═════════════════════════════════════════════════════════════════════════════
FROM maven:3.9-eclipse-temurin-21 AS builder
# Re-declare ARG to bring the build-time value into this stage's scope.
# ARGs declared before FROM are not automatically available inside stages.
ARG BUILD_TOOL=gradle

WORKDIR /workspace

# ── Gradle wrapper (cached until wrapper version changes) ─────────────────────
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
RUN chmod +x gradlew

# ── Build descriptors ─────────────────────────────────────────────────────────
# Copy before source so this layer — and the slow dependency-resolution layer
# below — is invalidated only when build files change, not on every code edit.
COPY settings.gradle.kts build.gradle.kts ./
COPY transform-service/build.gradle.kts transform-service/
COPY transform-service/pom.xml           transform-service/
COPY ingestion-service/build.gradle.kts  ingestion-service/

# ── Pre-warm dependency cache ─────────────────────────────────────────────────
# Both tools fail gracefully (|| true) if proto stubs don't exist yet — that's
# expected at this layer. We just want the JARs in the local cache so the
# compile layer below is faster.
RUN if [ "$BUILD_TOOL" = "maven" ]; then \
        mvn -f transform-service/pom.xml dependency:go-offline 2>&1 || true; \
    else \
        ./gradlew :transform-service:dependencies --no-daemon 2>&1 || true; \
    fi

# ── Full source (invalidates on any source change — keep last) ────────────────
COPY transform-service/src/ transform-service/src/
COPY ingestion-service/src/  ingestion-service/src/

# ── Build and normalise the fat JAR to /dist.jar ──────────────────────────────
#
# GRADLE PATH (BUILD_TOOL=gradle, the default):
#   Step 1 — generateProto || true
#     Gradle downloads protoc + protoc-gen-grpc-java into ~/.gradle/caches/.
#     The task FAILS because downloaded .exe files land without the execute bit
#     (Gradle's file downloader does not chmod). That's intentional — we want
#     the download to happen, not the task to succeed yet.
#   Step 2 — find … chmod +x
#     Sets the execute bit on every downloaded .exe file.
#   Step 3 — shadowJar
#     Gradle sees generateProto's output is stale and reruns it. protoc is now
#     executable → stubs generated → full compile → fat JAR produced at
#     transform-service/build/libs/transform-service-0.1.0-all.jar.
#
# MAVEN PATH (BUILD_TOOL=maven):
#   protobuf-maven-plugin handles its own chmod when it downloads protoc — no
#   manual step required. It runs at generate-sources (before compile) in the
#   fixed Maven lifecycle. maven-shade-plugin runs at package and produces the
#   -shaded classifier JAR at transform-service/target/transform-service-0.1.0-shaded.jar.
#
# Both paths normalise their output to /dist.jar so the runtime stage never
# needs to know which build tool was used.
RUN if [ "$BUILD_TOOL" = "maven" ]; then \
        mvn -f transform-service/pom.xml package -DskipTests -U \
        && cp transform-service/target/transform-service-0.1.0-shaded.jar /dist.jar; \
    else \
        ./gradlew :transform-service:generateProto --no-daemon 2>&1 || true \
        && find /root/.gradle -name "*.exe" -exec chmod +x {} \; \
        && ./gradlew :transform-service:shadowJar --no-daemon \
        && cp transform-service/build/libs/transform-service-0.1.0-all.jar /dist.jar; \
    fi

# ═════════════════════════════════════════════════════════════════════════════
# Stage 2: runtime
#
# Lean JRE only — no compiler, no Gradle, no Maven, no build caches.
# eclipse-temurin:21-jre-alpine is ~100 MB vs ~800 MB for the builder image.
# ═════════════════════════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jre-alpine AS runtime

# -XX:+UseContainerSupport: read cgroup memory limit, not host RAM.
#   Without this flag (Java < 10), the JVM on a 512 MB container attached to a
#   64 GB host would size its heap to 16 GB and get OOM-killed immediately.
# -XX:MaxRAMPercentage=75.0: use up to 75 % of the container limit for heap;
#   the remaining 25 % covers OS overhead, JVM internals, thread stacks, and
#   Netty's off-heap I/O buffers.
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

# netcat for the TCP health check below
RUN apk add --no-cache netcat-openbsd

WORKDIR /app

# The fat JAR is the only artefact we need from the builder stage.
COPY --from=builder /dist.jar ./transform-service.jar

EXPOSE 50051

# Health check: attempt a TCP connection to port 50051.
# --start-period=30s: don't count failures during JVM cold-start.
# docker-compose depends_on: condition: service_healthy waits for this to pass.
HEALTHCHECK --interval=10s --timeout=5s --start-period=30s --retries=3 \
  CMD nc -z localhost 50051 || exit 1

# Use sh -c so the shell expands $JAVA_OPTS before exec'ing java.
# exec replaces the shell so Java becomes PID 1 and receives SIGTERM directly.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/transform-service.jar"]
