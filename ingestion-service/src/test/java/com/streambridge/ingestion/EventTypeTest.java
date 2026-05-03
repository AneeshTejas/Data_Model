package com.streambridge.ingestion;

import com.streambridge.ingestion.model.EventType;
import com.streambridge.ingestion.model.RawEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for EventType sealed interface and EventFilter.
 *
 * EXTRA JAVA CONCEPT: @ParameterizedTest
 * Run the SAME test logic with multiple input/expected pairs.
 * Python equivalent: @pytest.mark.parametrize
 *
 * @CsvSource: each string is one row. Values split by comma.
 * Method params must match the CSV column count.
 */
class EventTypeTest {

    private RawEvent makeEvent(String type, String source, Map<String, Object> payload) {
        return new RawEvent("test-id", type, source, "2024-01-01T00:00:00Z", payload);
    }

    @ParameterizedTest(name = "type=''{0}'' → routingKey=''{1}''")
    @CsvSource({
            "user.created,  users",
            "user.deleted,  users",
            "order.placed,  orders",
            "order.cancelled, orders",
            "system.alert,  system_logs",
            "some.unknown,  unknown_events"
    })
    void routingKeyMatchesEventType(String type, String expectedKey) {
        var event = makeEvent(type, "test", Map.of("user_id", "u-1", "order_id", "o-1"));
        var eventType = EventType.from(event);

        assertThat(eventType.routingKey()).isEqualTo(expectedKey.strip());
    }

    @Test
    void userEventExtractsUserId() {
        var event = makeEvent("user.created", "salesforce", Map.of("user_id", "u-42"));
        var type = EventType.from(event);

        // Pattern matching instanceof in test assertions
        assertThat(type).isInstanceOf(EventType.UserEvent.class);

        if (type instanceof EventType.UserEvent ue) {
            assertThat(ue.userId()).isEqualTo("u-42");
            assertThat(ue.action()).isEqualTo("created");
        }
    }

    @Test
    void orderEventExtractsStatus() {
        var event = makeEvent("order.placed", "stripe",
                Map.of("order_id", "ord-99", "status", "pending"));
        var type = EventType.from(event);

        assertThat(type).isInstanceOf(EventType.OrderEvent.class);
        if (type instanceof EventType.OrderEvent oe) {
            assertThat(oe.orderId()).isEqualTo("ord-99");
            assertThat(oe.status()).isEqualTo("pending");
        }
    }

    @Test
    void unknownEventTypePreservesRawType() {
        var event = makeEvent("mystery.event", "test", Map.of("data", "x"));
        var type = EventType.from(event);

        assertThat(type).isInstanceOf(EventType.UnknownEvent.class);
        if (type instanceof EventType.UnknownEvent ue) {
            assertThat(ue.rawType()).isEqualTo("mystery.event");
        }
    }
}
