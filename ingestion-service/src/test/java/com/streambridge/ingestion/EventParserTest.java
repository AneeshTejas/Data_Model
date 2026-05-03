package com.streambridge.ingestion;

import com.streambridge.ingestion.model.RawEvent;
import com.streambridge.ingestion.parser.EventParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Tests for EventParser.
 *
 * JAVA TESTING CONCEPTS:
 *
 * 1. JUnit 5 annotations:
 *    @Test           = marks a test method (like pytest's test_ prefix, but explicit)
 *    @BeforeEach     = runs before each test (like pytest's setUp / fixtures)
 *    @DisplayName    = readable test name in reports (like pytest's test docstrings)
 *    @Nested         = groups related tests (like pytest classes)
 *
 * 2. AssertJ: fluent assertion library.
 *    Python equivalent: pytest's assert + hamcrest
 *    assertThat(actual).isEqualTo(expected)
 *    assertThat(list).hasSize(3).contains(item)
 *    assertThatThrownBy(() -> ...).isInstanceOf(Exception.class)
 *
 * 3. No return type, no parameters on test methods.
 *    Test methods are always void and take no arguments in JUnit 5.
 *    (JUnit 5 does support @ParameterizedTest for parameterised tests — more on that later)
 */
class EventParserTest {

    // Instance variable — created fresh before EACH test by @BeforeEach
    private EventParser parser;

    // Python equivalent: def setup_method(self): self.parser = EventParser()
    @BeforeEach
    void setUp() {
        parser = new EventParser();
    }

    @Test
    @DisplayName("parses a valid JSON event line correctly")
    void parsesValidEvent() {
        var json = """
                {"id":"test-1","type":"user.created","source":"salesforce",
                 "timestamp":"2024-06-01T10:00:00Z",
                 "payload":{"user_id":"u-1","plan":"pro"}}
                """;

        // parseLine returns Optional — we use assertThat from AssertJ
        var result = parser.parseLine(json.strip());

        // isPresent() checks the Optional is non-empty
        assertThat(result).isPresent();

        var event = result.get();
        assertThat(event.id()).isEqualTo("test-1");
        assertThat(event.type()).isEqualTo("user.created");
        assertThat(event.source()).isEqualTo("salesforce");
        assertThat(event.payload()).containsKey("user_id");
    }

    @Test
    @DisplayName("returns empty Optional for blank input")
    void returnsEmptyForBlankLine() {
        assertThat(parser.parseLine("")).isEmpty();
        assertThat(parser.parseLine("   ")).isEmpty();
        assertThat(parser.parseLine(null)).isEmpty();
    }

    @Test
    @DisplayName("returns empty Optional for malformed JSON")
    void returnsEmptyForMalformedJson() {
        assertThat(parser.parseLine("{not valid json")).isEmpty();
        assertThat(parser.parseLine("just a string")).isEmpty();
    }

    @Test
    @DisplayName("RawEvent record throws on null id")
    void recordValidatesNullId() {
        // assertThatThrownBy: verifies an exception is thrown
        // Python equivalent: with pytest.raises(ValueError): ...
        assertThatThrownBy(() ->
                new RawEvent(null, "user.created", "test", "2024-01-01T00:00:00Z", Map.of())
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id cannot be null");
    }

    @Test
    @DisplayName("parses multiple lines, skips failures")
    void parsesMultipleLinesSkippingBadOnes() {
        var lines = List.of(
                """
                {"id":"e1","type":"user.created","source":"test","timestamp":"2024-01-01T00:00:00Z","payload":{"k":"v"}}
                """.strip(),
                "{ bad json }",  // should be skipped
                """
                {"id":"e2","type":"order.placed","source":"stripe","timestamp":"2024-01-01T00:00:00Z","payload":{"k":"v"}}
                """.strip()
        );

        var results = parser.parseLines(lines);

        // hasSize + extracting: fluent chain — like Python's [e.id for e in results]
        assertThat(results).hasSize(2);
        assertThat(results)
                .extracting(RawEvent::id)  // method reference as a "key extractor"
                .containsExactly("e1", "e2");
    }

    @Test
    @DisplayName("parsed timestamp falls back to now for invalid format")
    void timestampFallsBackOnInvalidFormat() {
        var json = """
                {"id":"e3","type":"user.created","source":"test","timestamp":"not-a-date","payload":{"k":"v"}}
                """.strip();

        var event = parser.parseLine(json).orElseThrow();
        // Should not throw — fallback to Instant.now()
        assertThat(event.parsedTimestamp()).isNotNull();
    }
}
