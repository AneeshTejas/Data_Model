package com.streambridge.ingestion.model;

/**
 * EventType models the different event categories the pipeline handles.
 *
 * JAVA 17 CONCEPT: Sealed Interfaces + Pattern Matching
 * ──────────────────────────────────────────────────────
 * A "sealed" type restricts which classes can implement/extend it.
 * The compiler KNOWS exactly which subtypes exist — so switch expressions
 * can be exhaustiveness-checked. If you add a new subtype and forget to
 * handle it in a switch, it's a compile error, not a runtime bug.
 *
 * Python equivalent (PEP 634, Python 3.10+):
 *
 *   class UserEvent(TypedDict): ...
 *   class OrderEvent(TypedDict): ...
 *   class SystemEvent(TypedDict): ...
 *
 *   match event_type:
 *       case UserEvent(): ...
 *       case OrderEvent(): ...
 *       case SystemEvent(): ...
 *
 * Java's version is stricter: the compiler enforces exhaustiveness.
 * This is a HUGE safety win for event-driven systems like Hevo's pipelines.
 */
public sealed interface EventType permits
        EventType.UserEvent,
        EventType.OrderEvent,
        EventType.SystemEvent,
        EventType.UnknownEvent {

    /**
     * Returns the routing key used to determine which DB table to write to.
     * Think of this as the "destination selector" in a pipeline.
     */
    String routingKey();

    // ── Permitted subtypes ──────────────────────────────────────────────────

    /** Events related to user lifecycle: signup, update, deletion */
    record UserEvent(String userId, String action) implements EventType {
        @Override
        public String routingKey() { return "users"; }
    }

    /** Events from an order management system */
    record OrderEvent(String orderId, String status) implements EventType {
        @Override
        public String routingKey() { return "orders"; }
    }

    /** Internal system/health events */
    record SystemEvent(String component, String severity) implements EventType {
        @Override
        public String routingKey() { return "system_logs"; }
    }

    /** Catch-all for events we don't recognise yet */
    record UnknownEvent(String rawType) implements EventType {
        @Override
        public String routingKey() { return "unknown_events"; }
    }

    // ── Factory method ──────────────────────────────────────────────────────

    /**
     * JAVA 17 CONCEPT: Pattern matching with switch expressions.
     * Switch expressions return a VALUE (unlike the old switch statements).
     * No more break statements. No more fall-through bugs.
     *
     * Python equivalent:
     *   match type_str.split(".")[0]:
     *       case "user":   return UserEvent(...)
     *       case "order":  return OrderEvent(...)
     *       ...
     */
    static EventType from(RawEvent event) {
        // Text block (Java 15+) — multi-line strings without escape hell
        // Python equivalent: triple-quoted strings """..."""
        String type = event.type();

        return switch (type.split("\\.")[0]) {  // split "user.created" → ["user", "created"]
            case "user"   -> new UserEvent(
                    extractString(event, "user_id"),
                    type.contains(".") ? type.split("\\.")[1] : "unknown"
            );
            case "order"  -> new OrderEvent(
                    extractString(event, "order_id"),
                    extractString(event, "status")
            );
            case "system" -> new SystemEvent(
                    extractString(event, "component"),
                    extractString(event, "severity")
            );
            // The 'default' here handles everything not in the cases above.
            // For sealed interfaces in switch, if ALL permits are listed, default isn't needed.
            default       -> new UnknownEvent(type);
        };
    }

    private static String extractString(RawEvent event, String key) {
        if (event.payload() == null) return "unknown";
        Object val = event.payload().get(key);
        return val != null ? val.toString() : "unknown";
    }
}
