# Makefile for StreamBridge
# Works on Linux, macOS, and WSL. On native Windows use gradlew.bat and
# docker compose commands directly (Make requires a POSIX shell).

GRADLE  := ./gradlew --no-daemon
COMPOSE := docker compose

.PHONY: test build build-maven docker-build docker-build-maven up down clean

## Run unit tests for both services
test:
	$(GRADLE) :transform-service:test :ingestion-service:test

## Build fat JARs locally with Gradle
##   transform-service → build/libs/transform-service-0.1.0-all.jar  (shadowJar)
##   ingestion-service → build/install/ingestion-service/             (installDist)
build:
	$(GRADLE) :transform-service:shadowJar :ingestion-service:installDist

## Build transform-service fat JAR with Maven (parity check against pom.xml)
##   output → transform-service/target/transform-service-0.1.0-shaded.jar
build-maven:
	mvn -f transform-service/pom.xml package -DskipTests

## Build Docker images via docker compose (Gradle path, default BUILD_TOOL)
docker-build:
	$(COMPOSE) build

## Build the transform-service Docker image using the Maven build path
docker-build-maven:
	docker build --build-arg BUILD_TOOL=maven \
	  -f transform-service/Dockerfile \
	  -t streambridge/transform-service:maven-test \
	  .

## Start the full stack (postgres + transform-service + ingestion-service)
## Rebuilds images if source has changed.
up:
	$(COMPOSE) up --build

## Stop the stack and remove named volumes (postgres data is wiped)
down:
	$(COMPOSE) down -v

## Remove all Gradle build outputs
clean:
	$(GRADLE) clean
