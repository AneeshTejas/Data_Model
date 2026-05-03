package com.streambridge.ingestion.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * PayloadCleaner normalises raw event payloads before they hit the DB.
 * Common cleaning tasks: trim strings, remove PII fields, coerce types.
 *
 * JAVA 17 CONCEPTS HERE:
 *
 * 1. var keyword (local variable type inference)
 *    var x = new HashMap<String, Object>()  — compiler infers the type.
 *    Like Python's dynamic typing, BUT it's still statically typed.
 *    var is only for local variables, not fields or method return types.
 *    Use it when the type is obvious from the right-hand side.
 *
 * 2. Text blocks (triple-quoted strings)
 *    String sql = """
 *        SELECT *
 *        FROM events
 *        """;
 *    Python equivalent: """...""" — almost identical!
 *
 * 3. Map.entry() and Set.of() — immutable collections factory methods.
 */
public class PayloadCleaner {

    private static final Logger log = LoggerFactory.getLogger(PayloadCleaner.class);

    // Fields containing PII — will be redacted from cleaned payload
    private static final Set<String> PII_FIELDS = Set.of(
            "email", "phone", "ssn", "credit_card", "password",
            "ip_address", "date_of_birth"
    );

    // Fields to rename for consistency across sources
    // Python equivalent: a dict {old_name: new_name}
    private static final Map<String, String> FIELD_RENAMES = Map.of(
            "userId",   "user_id",    // snake_case normalisation
            "orderId",  "order_id",
            "createdAt","created_at",
            "updatedAt","updated_at"
    );

    /**
     * Cleans and normalises a raw payload.
     *
     * JAVA CONCEPT: Map.Entry<K,V> — represents a key-value pair.
     * entrySet() gives you all key-value pairs as a Set.
     * Python equivalent: dict.items()
     */
    public Map<String, Object> clean(Map<String, Object> rawPayload) {
        if (rawPayload == null) return Map.of();

        // var here: type is obviously HashMap<String, Object>
        var cleaned = new HashMap<String, Object>();

        for (var entry : rawPayload.entrySet()) {  // var entry = Map.Entry<String, Object>
            var key = entry.getKey();
            var value = entry.getValue();

            // Skip PII fields
            if (PII_FIELDS.contains(key.toLowerCase())) {
                log.debug("Redacting PII field: {}", key);
                cleaned.put(key, "[REDACTED]");
                continue;
            }

            // Apply field rename if applicable
            var canonicalKey = FIELD_RENAMES.getOrDefault(key, key);

            // Normalise string values: trim whitespace
            if (value instanceof String str) {       // JAVA 16+: pattern matching in instanceof
                cleaned.put(canonicalKey, str.strip()); // .strip() like Python's .strip()
            } else {
                cleaned.put(canonicalKey, value);
            }
        }

        return Map.copyOf(cleaned);  // return immutable copy
    }

    /**
     * Generates a SQL INSERT statement for an event (for learning — normally use an ORM).
     *
     * JAVA 17: Text blocks.
     * The leading whitespace is stripped relative to the closing """.
     * Indentation in the block is relative — doesn't end up in the string.
     *
     * Python equivalent:
     *   sql = f"""
     *       INSERT INTO {table} (id, source, processed_at, payload)
     *       VALUES (%s, %s, %s, %s)
     *   """
     */
    public String buildInsertSql(String tableName) {
        // Text block — notice the indentation lines up with closing """
        return """
                INSERT INTO %s (event_id, source, processed_at, routing_key, payload)
                VALUES (?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (event_id) DO NOTHING
                """.formatted(tableName);  // .formatted() is String.format() but called on the string
    }
}
