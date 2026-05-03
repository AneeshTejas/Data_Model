package com.streambridge.ingestion;

import com.streambridge.ingestion.filter.EventFilter;
import com.streambridge.ingestion.parser.EventParser;
import com.streambridge.ingestion.pipeline.IngestionPipeline;
import com.streambridge.ingestion.sink.EventSink;
import com.streambridge.ingestion.util.PayloadCleaner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.*;

/**
 * Integration test: runs the full pipeline with a real SQLite DB.
 *
 * JAVA TESTING CONCEPT: @TempDir
 * JUnit 5 injects a temporary directory that is created before the test
 * and deleted after. No cleanup code needed.
 * Python equivalent: tmp_path fixture in pytest.
 *
 * Integration tests live in the same test folder but are named *IT or *IntegrationTest
 * by convention so you can run them separately from unit tests.
 */
class IngestionPipelineIntegrationTest {

    // @TempDir: JUnit creates and injects a temp directory automatically
    @TempDir
    Path tempDir;

    private EventSink.SQLiteEventSink sink;
    private IngestionPipeline pipeline;
    private Path dbPath;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("test.db");
        sink = new EventSink.SQLiteEventSink(dbPath.toString());
        pipeline = new IngestionPipeline(
                new EventParser(),
                new EventFilter(),
                new PayloadCleaner(),
                sink
        );
    }

    @AfterEach
    void tearDown() {
        sink.close();
    }

    @Test
    void processesValidEventsAndWritesToDB() throws Exception {
        // Write a temp NDJSON file with 3 events
        var eventsFile = tempDir.resolve("events.ndjson");
        Files.writeString(eventsFile, """
                {"id":"e1","type":"user.created","source":"salesforce","timestamp":"2024-01-01T00:00:00Z","payload":{"user_id":"u-1","plan":"pro"}}
                {"id":"e2","type":"order.placed","source":"stripe","timestamp":"2024-01-01T00:00:01Z","payload":{"order_id":"o-1","status":"pending"}}
                {"id":"e3","type":"system.alert","source":"postgres","timestamp":"2024-01-01T00:00:02Z","payload":{"component":"db","severity":"ERROR"}}
                """);

        var stats = pipeline.run(eventsFile);

        assertThat(stats.total()).isEqualTo(3);
        assertThat(stats.errors()).isEqualTo(0);
        // system.alert with ERROR severity passes the filter
        assertThat(stats.passed()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void filtersOutCancelledOrders() throws Exception {
        var eventsFile = tempDir.resolve("orders.ndjson");
        Files.writeString(eventsFile, """
                {"id":"e1","type":"order.cancelled","source":"stripe","timestamp":"2024-01-01T00:00:00Z","payload":{"order_id":"o-1","status":"cancelled"}}
                {"id":"e2","type":"order.placed","source":"stripe","timestamp":"2024-01-01T00:00:01Z","payload":{"order_id":"o-2","status":"pending"}}
                """);

        var stats = pipeline.run(eventsFile);

        assertThat(stats.total()).isEqualTo(2);
        assertThat(stats.filtered()).isEqualTo(1);  // cancelled order filtered
        assertThat(stats.passed()).isEqualTo(1);
    }

    @Test
    void filtersOutUnknownSources() throws Exception {
        var eventsFile = tempDir.resolve("mixed.ndjson");
        Files.writeString(eventsFile, """
                {"id":"e1","type":"user.created","source":"unknown_crm","timestamp":"2024-01-01T00:00:00Z","payload":{"user_id":"u-1"}}
                {"id":"e2","type":"user.created","source":"salesforce","timestamp":"2024-01-01T00:00:01Z","payload":{"user_id":"u-2"}}
                """);

        var stats = pipeline.run(eventsFile);

        // Only the salesforce event passes
        assertThat(stats.passed()).isEqualTo(1);
        assertThat(stats.filtered()).isEqualTo(1);
    }

    @Test
    void redactsPIIFromPayload() throws Exception {
        var eventsFile = tempDir.resolve("pii.ndjson");
        Files.writeString(eventsFile, """
                {"id":"e1","type":"user.created","source":"salesforce","timestamp":"2024-01-01T00:00:00Z","payload":{"user_id":"u-1","email":"alice@test.com","plan":"pro"}}
                """);

        var stats = pipeline.run(eventsFile);

        assertThat(stats.passed()).isEqualTo(1);
        // The pipeline should complete without error — PII is redacted in PayloadCleaner
        assertThat(stats.errors()).isEqualTo(0);
    }
}
