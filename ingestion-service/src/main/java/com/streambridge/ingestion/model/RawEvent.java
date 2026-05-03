package com.streambridge.ingestion.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.Map;

/**
 * RawEvent represents an event as it arrives from a source — unvalidated, unclean.
 *
 * JAVA 17 CONCEPT: Records
 * ─────────────────────────
 * A record is an immutable data class. The compiler auto-generates:
 *   - A constructor with all fields
 *   - Getters (called "accessors") for each field: event.id(), event.type(), etc.
 *   - equals(), hashCode(), toString()
 *
 * Python equivalent:
 *   @dataclass(frozen=True)
 *   class RawEvent:
 *       id: str
 *       type: str
 *       ...
 *
 * KEY DIFFERENCE: Java records use event.id() — method call syntax, not event.id attribute.
 * This is because Java getters are methods. You'll see this pattern everywhere.
 */
@JsonIgnoreProperties(ignoreUnknown = true)  // Jackson: don't fail on unknown JSON fields
public record RawEvent(
        @JsonProperty("id")         String id,
        @JsonProperty("type")       String type,           // e.g. "user.created", "order.placed"
        @JsonProperty("source")     String source,         // e.g. "salesforce", "stripe"
        @JsonProperty("timestamp")  String timestamp,       // ISO-8601 string from source
        @JsonProperty("payload")    Map<String, Object> payload  // arbitrary event data
) {
    /**
     * Compact constructor — runs BEFORE the auto-generated constructor.
     * Use this for validation. Python equivalent: __post_init__ in a dataclass.
     *
     * JAVA 17 CONCEPT: Compact constructors in records.
     * You don't re-assign fields here — you just validate. Java handles the assignment.
     */
    public RawEvent {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("RawEvent id cannot be null or blank");
        }
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("RawEvent type cannot be null or blank");
        }
    }

    /**
     * Parsed timestamp as an Instant. Shows adding domain logic to records.
     * Python equivalent: a @property on a dataclass.
     */
    public Instant parsedTimestamp() {
        try {
            return Instant.parse(timestamp);
        } catch (Exception e) {
            return Instant.now();  // fallback to now if timestamp is malformed
        }
    }
}
