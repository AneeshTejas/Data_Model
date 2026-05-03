@echo off
REM run-pipeline.bat — Windows equivalent of run-pipeline.sh
REM
REM Usage (from project root):
REM   scripts\run-pipeline.bat
REM
REM See run-pipeline.sh for a detailed explanation of 'up' vs 'run'.

echo =^> Building images and starting background services...
docker compose up --build -d postgres adminer transform-service
if ERRORLEVEL 1 (
    echo ERROR: Failed to start background services.
    exit /b 1
)

echo =^> Running ingestion pipeline (streaming logs)...
docker compose run --rm ingestion-service
if ERRORLEVEL 1 (
    echo ERROR: Ingestion pipeline failed. Check logs above.
    exit /b 1
)

echo.
echo =^> Pipeline complete!
echo     Adminer UI : http://localhost:8080
echo       Server   : postgres
echo       User     : streambridge
echo       Password : streambridge
echo       Database : streambridge
echo.
echo     Tail transform-service logs : docker compose logs -f transform-service
echo     Tear down (keep data)       : docker compose down
echo     Tear down + wipe DB         : docker compose down -v
