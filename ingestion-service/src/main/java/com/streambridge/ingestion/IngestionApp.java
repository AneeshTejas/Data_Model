package com.streambridge.ingestion;

import com.streambridge.ingestion.filter.EventFilter;
import com.streambridge.ingestion.grpc.TransformClient;
import com.streambridge.ingestion.parser.EventParser;
import com.streambridge.ingestion.pipeline.IngestionPipeline;
import com.streambridge.ingestion.sink.EventSink;
import com.streambridge.ingestion.util.PayloadCleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * IngestionApp is the entry point for the ingestion service.
 *
 * CONFIG PRIORITY (Phase 3):
 *   env var → CLI arg → hardcoded default
 *
 * Env vars are the primary mechanism in Docker — set them in docker-compose.yml
 * and the container picks them up automatically. CLI args remain usable for
 * local dev runs (Phase 1 / Phase 2 workflow still works unchanged).
 *
 * ENV VARS RECOGNISED:
 *   INPUT_FILE       — path to the .ndjson file (default: sample-events.ndjson)
 *   DB_TYPE          — "postgres" or "sqlite" (default: sqlite)
 *   DB_HOST/PORT/NAME/USER/PASSWORD — PostgreSQL connection (read by PostgresEventSink)
 *   TRANSFORM_HOST   — hostname of the TransformService (default: localhost)
 *   TRANSFORM_PORT   — port of the TransformService (default: 50051)
 *   USE_GRPC         — "true" to delegate cleaning to gRPC service (default: false)
 */
public class IngestionApp {

    private static final Logger log = LoggerFactory.getLogger(IngestionApp.class);

    public static void main(String[] args) {
        // ── Config resolution ─────────────────────────────────────────────────
        String inputPath = System.getenv().getOrDefault("INPUT_FILE",
                           args.length > 0 ? args[0] : "sample-events.ndjson");

        String dbType    = System.getenv().getOrDefault("DB_TYPE", "sqlite");
        // dbPath is only used for SQLite mode; ignored when DB_TYPE=postgres
        String dbPath    = args.length > 1 ? args[1] : "streambridge.db";

        // In Docker: TRANSFORM_HOST=transform-service (service name on the bridge network).
        // Locally: defaults to localhost (Phase 1 / Phase 2 behaviour).
        String transformHost = System.getenv().getOrDefault("TRANSFORM_HOST", "localhost");
        int    transformPort = Integer.parseInt(
                               System.getenv().getOrDefault("TRANSFORM_PORT", "50051"));

        // USE_GRPC env var takes priority; fall back to the Phase 2 "grpc" arg.
        boolean useGrpc = "true".equalsIgnoreCase(System.getenv("USE_GRPC")) ||
                          (args.length > 2 && "grpc".equals(args[2]));

        log.info("StreamBridge Ingestion Service starting up");
        log.info("Input file : {}", inputPath);
        log.info("DB type    : {}", dbType);
        log.info("Transform  : {}", useGrpc
                ? "gRPC TransformService (%s:%d)".formatted(transformHost, transformPort)
                : "local PayloadCleaner");

        // ── Wire up the pipeline ──────────────────────────────────────────────
        // Both TransformClient and EventSink are AutoCloseable.
        // try-with-resources guarantees close() even if the pipeline throws.
        // null is valid for TransformClient — Java skips close() for null resources.
        try (TransformClient transformClient = useGrpc
                     ? new TransformClient(transformHost, transformPort) : null;
             EventSink sink = "postgres".equalsIgnoreCase(dbType)
                     ? new EventSink.PostgresEventSink()
                     : new EventSink.SQLiteEventSink(dbPath)) {

            var pipeline = new IngestionPipeline(
                    new EventParser(),
                    new EventFilter(),
                    new PayloadCleaner(),
                    sink,
                    transformClient
            );

            var stats = pipeline.run(Path.of(inputPath));

            log.info("Run complete: {}", stats);
            log.info("Total events : {}", stats.total());
            log.info("Passed       : {}", stats.passed());
            log.info("Filtered out : {}", stats.filtered());
            log.info("Errors       : {}", stats.errors());
            log.info("Success rate : %.1f%%".formatted(stats.successRate()));

        } catch (Exception e) {
            log.error("Pipeline failed: {}", e.getMessage(), e);
            System.exit(1);
        }
    }
}
