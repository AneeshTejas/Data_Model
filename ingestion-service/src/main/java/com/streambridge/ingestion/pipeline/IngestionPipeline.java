package com.streambridge.ingestion.pipeline;

import com.streambridge.ingestion.filter.EventFilter;
import com.streambridge.ingestion.grpc.TransformClient;
import com.streambridge.ingestion.model.EventType;
import com.streambridge.ingestion.model.ProcessedEvent;
import com.streambridge.ingestion.model.RawEvent;
import com.streambridge.ingestion.parser.EventParser;
import com.streambridge.ingestion.sink.EventSink;
import com.streambridge.ingestion.util.PayloadCleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * IngestionPipeline is the main orchestrator.
 * It ties together: Parser → Filter → Type routing → Cleaner → Sink
 *
 * This is a simplified version of what a Hevo connector does internally:
 * read from source → validate → transform → write to destination.
 *
 * JAVA CONCEPTS HERE:
 *
 * 1. Record as a return type (PipelineStats):
 *    Wrapping multiple return values in a record is cleaner than returning
 *    a Map or an array. Python equivalent: a NamedTuple or dataclass.
 *
 * 2. JAVA 17: Switch expression as a statement.
 *    The switch assigns to a variable. No fall-through. Exhaustiveness-checked
 *    for sealed types. This eliminates entire classes of bugs.
 *
 * 3. List accumulation + batch flush:
 *    Accumulate events in memory, then write in one batch.
 *    This is a standard pipeline pattern for throughput optimisation.
 */
public class IngestionPipeline {

    private static final Logger log = LoggerFactory.getLogger(IngestionPipeline.class);
    private static final int BATCH_SIZE = 100;  // flush to DB every N events

    private final EventParser parser;
    private final EventFilter filter;
    private final PayloadCleaner cleaner;
    private final EventSink sink;

    // null = use local PayloadCleaner (default). Non-null = delegate to gRPC service.
    private final TransformClient transformClient;

    /**
     * Original constructor — existing tests use this. Falls back to local PayloadCleaner.
     * Delegates to the 5-arg constructor with null so behaviour is identical to before.
     */
    public IngestionPipeline(EventParser parser, EventFilter filter,
                             PayloadCleaner cleaner, EventSink sink) {
        this(parser, filter, cleaner, sink, null);
    }

    /**
     * Phase 2 constructor — pass a TransformClient to delegate payload cleaning
     * to the remote gRPC TransformService instead of the local PayloadCleaner.
     * If transformClient is null this behaves exactly like the 4-arg constructor.
     */
    public IngestionPipeline(EventParser parser, EventFilter filter,
                             PayloadCleaner cleaner, EventSink sink,
                             TransformClient transformClient) {
        this.parser = parser;
        this.filter = filter;
        this.cleaner = cleaner;
        this.sink = sink;
        this.transformClient = transformClient;
    }

    /**
     * Runs the full pipeline for a given input file.
     * Returns statistics about what was processed, filtered, and written.
     */
    public PipelineStats run(Path inputFile) throws IOException, SQLException {
        log.info("Starting pipeline run for: {}", inputFile);

        int total = 0, passed = 0, filtered = 0, errors = 0;
        var batch = new ArrayList<ProcessedEvent>(BATCH_SIZE);

        // STREAM PIPELINE:
        // parseFile() returns a Stream<RawEvent>. We iterate it here.
        // In a real system this would be a stream of records from Kafka or a DB cursor.
        var eventStream = parser.parseFile(inputFile);

        // forEach on a stream — terminal operation.
        // Python equivalent: for event in parse_file(path): ...
        var rawEvents = eventStream.toList();  // materialise to handle checked exceptions cleanly

        for (var raw : rawEvents) {
            total++;
            try {
                var processed = processOne(raw);

                if (processed.status() == ProcessedEvent.ProcessingStatus.FILTERED_OUT) {
                    filtered++;
                } else {
                    batch.add(processed);
                    passed++;
                }

                // Flush batch to DB when it reaches the threshold
                if (batch.size() >= BATCH_SIZE) {
                    sink.writeBatch(batch);
                    batch.clear();
                }

            } catch (Exception e) {
                errors++;
                log.error("Error processing event id={}: {}", raw.id(), e.getMessage());
            }
        }

        // Flush remaining events (the last partial batch)
        if (!batch.isEmpty()) {
            sink.writeBatch(batch);
        }

        var stats = new PipelineStats(total, passed, filtered, errors);
        log.info("Pipeline complete: {}", stats);
        return stats;
    }

    /**
     * Processes a single RawEvent through the full chain.
     *
     * JAVA 17: Switch expression to get routing key label for logging.
     * Notice: switch is an EXPRESSION (returns a value), not a statement.
     * There's no 'break'. Each case uses '->' (arrow syntax).
     */
    private ProcessedEvent processOne(RawEvent raw) {
        // 1. Classify event type using sealed interface + pattern matching
        var eventType = EventType.from(raw);

        // 2. Apply standard filter rules
        boolean passesStandard = filter.standardPipeline().test(raw);
        if (!passesStandard) {
            return ProcessedEvent.filtered(raw, eventType);
        }

        // 3. Apply type-specific rules using pattern matching instanceof
        boolean passesTypeRules = filter.passesTypeSpecificRules(eventType);
        if (!passesTypeRules) {
            return ProcessedEvent.filtered(raw, eventType);
        }

        // 4. Clean/transform the payload.
        // If a TransformClient is wired in, delegate to the remote gRPC service.
        // Otherwise fall back to the local PayloadCleaner — preserves existing behaviour.
        var cleanedPayload = (transformClient != null)
                ? transformClient.transform(raw)
                : cleaner.clean(raw.payload());

        // 5. Log the routing decision using switch expression
        // JAVA 17: switch expression (returns a value, no 'break')
        String routingDescription = switch (eventType) {
            case EventType.UserEvent ue    -> "users table (user_id=%s)".formatted(ue.userId());
            case EventType.OrderEvent oe   -> "orders table (order_id=%s)".formatted(oe.orderId());
            case EventType.SystemEvent se  -> "system_logs table (severity=%s)".formatted(se.severity());
            case EventType.UnknownEvent ue -> "unknown_events table (type=%s)".formatted(ue.rawType());
        };

        log.debug("Event id={} routed to: {}", raw.id(), routingDescription);

        return ProcessedEvent.success(raw, eventType, cleanedPayload);
    }

    /**
     * Pipeline statistics returned after a run.
     * JAVA 17: Record as a simple result container.
     */
    public record PipelineStats(int total, int passed, int filtered, int errors) {

        public double successRate() {
            return total == 0 ? 0.0 : (double) passed / total * 100;
        }

        @Override
        public String toString() {
            return """
                    PipelineStats { total=%d, passed=%d, filtered=%d, errors=%d, successRate=%.1f%% }
                    """.formatted(total, passed, filtered, errors, successRate()).strip();
        }
    }
}
