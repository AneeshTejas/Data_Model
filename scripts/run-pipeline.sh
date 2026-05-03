#!/bin/sh
# run-pipeline.sh — Convenience wrapper to build and run the full StreamBridge stack.
#
# Usage (from project root):
#   chmod +x scripts/run-pipeline.sh
#   ./scripts/run-pipeline.sh
#
# DIFFERENCE BETWEEN 'up' AND 'run':
#   docker compose up -d <services>
#     Starts long-running services in the BACKGROUND (detached).
#     They keep running after this script exits.
#     Use this for postgres, adminer, and transform-service.
#
#   docker compose run --rm <service>
#     Starts a ONE-SHOT container, streams its logs to the terminal,
#     waits for it to finish, then removes the container (--rm).
#     Use this for the ingestion pipeline — it runs once and exits.
#     docker compose run also respects depends_on health checks, so it
#     won't start ingestion-service until postgres and transform-service
#     report healthy.
#
# WHY NOT 'docker compose up' for everything:
#   'docker compose up' would also start ingestion-service in the background.
#   A one-shot job that exits with code 0 in the background is silent — you
#   get no terminal output and no easy way to know when it finished or if it
#   succeeded. 'run' keeps you in the loop.

set -e  # exit immediately if any command fails
cd "$(dirname "$0")/.."  # run from the project root regardless of call site

echo "==> Building images and starting background services..."
docker compose up --build -d postgres adminer transform-service

echo "==> Running ingestion pipeline (streaming logs)..."
docker compose run --rm ingestion-service

echo ""
echo "==> Pipeline complete!"
echo "    Adminer UI : http://localhost:8080"
echo "      Server   : postgres"
echo "      User     : streambridge"
echo "      Password : streambridge"
echo "      Database : streambridge"
echo ""
echo "    Tail transform-service logs : docker compose logs -f transform-service"
echo "    Tear down (keep data)       : docker compose down"
echo "    Tear down + wipe DB         : docker compose down -v"
