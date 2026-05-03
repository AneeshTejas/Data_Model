package com.streambridge.ingestion.model;

import java.time.Instant;
import java.util.Map;

/**
 * ProcessedEvent is what the pipeline produces AFTER parsing and filtering.
 * It's richer than RawEvent — has typed fields, a routing key, and metadata.
 *
 * In Hevo terms, this is the "transformed event" ready to be loaded into the destination.
 *
 * JAVA 17 CONCEPT: Records can have static factory methods.
 * This is a common Java pattern: keep the constructor simple, use named factory methods
 * to make construction readable.
 */
public record ProcessedEvent(
        String id,
        EventType eventType,         // typed, not just a string
        String source,
        Instant processedAt,
        Map<String, Object> cleanedPayload,
        ProcessingStatus status,
        String routingKey            // determines which DB table this goes to
) {

    /**
     * JAVA 17 CONCEPT: Enums — much more powerful than Python's Enum.
     * Java enums can have methods, fields, and constructors.
     * Python equivalent: class ProcessingStatus(Enum): ...
     */
    public enum ProcessingStatus {
        SUCCESS("Event processed and ready for sink"),
        FILTERED_OUT("Event did not pass filter rules"),
        PARSE_ERROR("Event could not be parsed");

        private final String description;

        ProcessingStatus(String description) {
            this.description = description;
        }

        public String description() { return description; }
    }

    // Static factory — named constructors are cleaner than overloaded constructors
    public static ProcessedEvent success(RawEvent raw, EventType type, Map<String, Object> payload) {
        return new ProcessedEvent(
                raw.id(),
                type,
                raw.source(),
                Instant.now(),
                payload,
                ProcessingStatus.SUCCESS,
                type.routingKey()
        );
    }

    public static ProcessedEvent filtered(RawEvent raw, EventType type) {
        return new ProcessedEvent(
                raw.id(),
                type,
                raw.source(),
                Instant.now(),
                Map.of(),  // empty payload — Java 9+ immutable map factory
                ProcessingStatus.FILTERED_OUT,
                type.routingKey()
        );
    }
}
