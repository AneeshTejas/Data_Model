package com.streambridge.ingestion.filter;

import com.streambridge.ingestion.model.EventType;
import com.streambridge.ingestion.model.RawEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Predicate;

/**
 * EventFilter decides which events should proceed through the pipeline.
 *
 * JAVA CONCEPTS TO LEARN HERE:
 *
 * 1. Predicate<T>: A functional interface — takes one argument, returns boolean.
 *    It's Java's way of representing "a function that tests something".
 *    Python equivalent: Callable[[RawEvent], bool] or just a lambda.
 *
 * 2. Predicate.and() / .or() / .negate(): compose predicates without if-else chains.
 *    Python equivalent: combining lambdas with `and`/`or`.
 *
 * 3. JAVA 17: Pattern matching in instanceof checks.
 *    Old: if (type instanceof UserEvent) { UserEvent ue = (UserEvent) type; ... }
 *    New: if (type instanceof UserEvent ue) { ... ue.userId() ... }
 *    The cast happens INLINE. This is huge — no more double-check casts.
 *    Python equivalent: isinstance(event, UserEvent) (but Python doesn't need the cast).
 */
public class EventFilter {

    private static final Logger log = LoggerFactory.getLogger(EventFilter.class);

    // List of allowed sources — in a real system, this comes from config/DB
    private final List<String> allowedSources;
    // List of event type prefixes to DROP (e.g. "system" events in dev)
    private final List<String> blockedTypePrefixes;

    public EventFilter(List<String> allowedSources, List<String> blockedTypePrefixes) {
        this.allowedSources = List.copyOf(allowedSources);      // defensive copy — immutable
        this.blockedTypePrefixes = List.copyOf(blockedTypePrefixes);
    }

    // Default constructor with sensible defaults for development
    public EventFilter() {
        this(
                List.of("salesforce", "stripe", "hubspot", "postgres", "test"),
                List.of()  // don't block anything by default
        );
    }

    // ── Predicate building blocks ────────────────────────────────────────────

    /**
     * Passes if the event source is in the allowlist.
     *
     * CONCEPT: Returning a Predicate lets the caller compose filters.
     * Python equivalent: lambda event: event.source in self.allowed_sources
     */
    public Predicate<RawEvent> sourceAllowed() {
        return event -> {
            boolean allowed = allowedSources.contains(event.source());
            if (!allowed) {
                log.debug("Dropping event id={} — source '{}' not in allowlist", event.id(), event.source());
            }
            return allowed;
        };
    }

    /**
     * Passes if the event type is not blocked.
     */
    public Predicate<RawEvent> typeNotBlocked() {
        return event -> {
            boolean blocked = blockedTypePrefixes.stream()
                    .anyMatch(prefix -> event.type().startsWith(prefix));
            if (blocked) {
                log.debug("Dropping event id={} — type '{}' is blocked", event.id(), event.type());
            }
            return !blocked;
        };
    }

    /**
     * Passes if the event has a non-empty payload.
     */
    public Predicate<RawEvent> hasPayload() {
        return event -> event.payload() != null && !event.payload().isEmpty();
    }

    // ── Composite filter ─────────────────────────────────────────────────────

    /**
     * The main filter: combines all rules with logical AND.
     *
     * JAVA CONCEPT: Predicate.and() — composes two predicates.
     * Equivalent to: lambda e: rule1(e) and rule2(e) and rule3(e)
     */
    public Predicate<RawEvent> standardPipeline() {
        return sourceAllowed()
                .and(typeNotBlocked())
                .and(hasPayload());
    }

    // ── Pattern matching application ─────────────────────────────────────────

    /**
     * Applies event-type-specific rules using Java 17 pattern matching in instanceof.
     *
     * JAVA 17: Pattern matching with instanceof
     *   Old way: if (eventType instanceof EventType.UserEvent) {
     *                UserEvent ue = (UserEvent) eventType;   // redundant cast
     *                ...
     *            }
     *   New way: if (eventType instanceof EventType.UserEvent ue) {
     *                // ue is already cast — use directly
     *            }
     *
     * This is especially useful when you have a sealed interface with many subtypes.
     */
    public boolean passesTypeSpecificRules(EventType eventType) {
        // JAVA 17: pattern matching instanceof — variable 'ue', 'oe', 'se' are in scope
        if (eventType instanceof EventType.UserEvent ue) {
            // For user events, drop if userId is "unknown" (means parsing failed)
            return !"unknown".equals(ue.userId());
        }

        if (eventType instanceof EventType.OrderEvent oe) {
            // Drop cancelled orders — we don't want them in the warehouse
            return !"cancelled".equals(oe.status());
        }

        if (eventType instanceof EventType.SystemEvent se) {
            // Only keep high-severity system events
            return "ERROR".equals(se.severity()) || "CRITICAL".equals(se.severity());
        }

        if (eventType instanceof EventType.UnknownEvent) {
            // Unknown events proceed — Claude will handle them in Phase 4
            return true;
        }

        return true;
    }
}
