package com.streambridge.ingestion.sink;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.streambridge.ingestion.model.ProcessedEvent;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * EventSink writes ProcessedEvents to a database.
 *
 * JAVA CONCEPTS HERE:
 *
 * 1. Interface: Java's interface is like Python's ABC (Abstract Base Class).
 *    Defines a contract — any class implementing it MUST provide those methods.
 *    Python equivalent:
 *      from abc import ABC, abstractmethod
 *      class EventSink(ABC):
 *          @abstractmethod
 *          def write(self, event): ...
 *
 * 2. AutoCloseable / try-with-resources:
 *    Any class implementing AutoCloseable can be used in try(...) blocks.
 *    When the try block exits (success OR exception), close() is called automatically.
 *    Python equivalent: context managers with __enter__ / __exit__.
 *    Java: try (var conn = ds.getConnection()) { ... }  → conn.close() is guaranteed.
 *
 * 3. HikariCP: A connection pool. Instead of creating a new DB connection for every
 *    write (expensive), Hikari keeps a pool of connections ready.
 *    Python equivalent: SQLAlchemy's create_engine() with pool_size setting.
 *
 * 4. PreparedStatement: Parameterised SQL — prevents SQL injection and is faster
 *    (DB can cache the execution plan). Never concatenate user data into SQL strings.
 *    Python equivalent: cursor.execute("INSERT INTO t VALUES (%s, %s)", (val1, val2))
 */
public interface EventSink extends AutoCloseable {

    /** Write a single processed event to the destination */
    void write(ProcessedEvent event) throws SQLException;

    /** Write a batch of events — more efficient than calling write() in a loop */
    void writeBatch(List<ProcessedEvent> events) throws SQLException;

    // ── SQLite implementation (for local dev — no Docker needed yet) ─────────

    class SQLiteEventSink implements EventSink {

        private static final Logger log = LoggerFactory.getLogger(SQLiteEventSink.class);
        private final HikariDataSource dataSource;
        private final ObjectMapper mapper = new ObjectMapper();

        /**
         * HikariConfig: configure the connection pool.
         * For SQLite, pool size = 1 (SQLite doesn't support concurrent writes well).
         */
        public SQLiteEventSink(String dbPath) {
            var config = new HikariConfig();
            config.setJdbcUrl("jdbc:sqlite:" + dbPath);
            config.setMaximumPoolSize(1);
            config.setConnectionTestQuery("SELECT 1");

            this.dataSource = new HikariDataSource(config);
            initSchema();
            log.info("SQLite sink initialised at: {}", dbPath);
        }

        /** Creates the target table if it doesn't exist */
        private void initSchema() {
            // Text block for multi-line SQL — much cleaner than string concatenation
            var ddl = """
                    CREATE TABLE IF NOT EXISTS processed_events (
                        event_id     TEXT PRIMARY KEY,
                        event_type   TEXT NOT NULL,
                        source       TEXT NOT NULL,
                        routing_key  TEXT NOT NULL,
                        processed_at TEXT NOT NULL,
                        payload      TEXT NOT NULL,
                        status       TEXT NOT NULL
                    )
                    """;

            // try-with-resources: conn.close() is called automatically
            // Python equivalent:
            //   with connection.cursor() as cursor:
            //       cursor.execute(ddl)
            try (var conn = dataSource.getConnection();
                 var stmt = conn.createStatement()) {
                stmt.execute(ddl);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to initialise schema", e);
            }
        }

        @Override
        public void write(ProcessedEvent event) throws SQLException {
            var sql = """
                    INSERT INTO processed_events
                        (event_id, event_type, source, routing_key, processed_at, payload, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(event_id) DO NOTHING
                    """;

            // try-with-resources with multiple resources
            try (var conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                // JDBC uses 1-based indexing (not 0-based like Python) — common gotcha!
                ps.setString(1, event.id());
                ps.setString(2, event.eventType().getClass().getSimpleName());
                ps.setString(3, event.source());
                ps.setString(4, event.routingKey());
                ps.setString(5, event.processedAt().toString());
                ps.setString(6, mapper.writeValueAsString(event.cleanedPayload()));
                ps.setString(7, event.status().name());

                int rows = ps.executeUpdate();
                if (rows > 0) {
                    log.debug("Wrote event id={} to routing_key={}", event.id(), event.routingKey());
                }
            } catch (Exception e) {
                throw new SQLException("Failed to write event " + event.id(), e);
            }
        }

        @Override
        public void writeBatch(List<ProcessedEvent> events) throws SQLException {
            if (events.isEmpty()) return;

            var sql = """
                    INSERT INTO processed_events
                        (event_id, event_type, source, routing_key, processed_at, payload, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(event_id) DO NOTHING
                    """;

            // JDBC batch: add multiple statements, then execute all at once.
            // Much faster than one-by-one inserts — same idea as Python's executemany().
            try (var conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                conn.setAutoCommit(false);  // wrap batch in a transaction

                for (var event : events) {
                    ps.setString(1, event.id());
                    ps.setString(2, event.eventType().getClass().getSimpleName());
                    ps.setString(3, event.source());
                    ps.setString(4, event.routingKey());
                    ps.setString(5, event.processedAt().toString());
                    ps.setString(6, mapper.writeValueAsString(event.cleanedPayload()));
                    ps.setString(7, event.status().name());
                    ps.addBatch();  // queue this statement
                }

                ps.executeBatch();  // execute all queued statements
                conn.commit();
                log.info("Batch wrote {} events", events.size());

            } catch (Exception e) {
                throw new SQLException("Batch write failed", e);
            }
        }

        @Override
        public void close() {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
                log.info("SQLite connection pool closed");
            }
        }
    }

    // ── PostgreSQL implementation (used in Docker / production) ──────────────
    //
    // Reads all connection config from environment variables so the image is
    // portable across environments — no hardcoded hostnames or passwords.
    // Docker Compose wires the env vars; local dev can export them manually.

    class PostgresEventSink implements EventSink {

        private static final Logger log = LoggerFactory.getLogger(PostgresEventSink.class);
        private final HikariDataSource dataSource;
        private final ObjectMapper mapper = new ObjectMapper();

        public PostgresEventSink() {
            String host     = System.getenv().getOrDefault("DB_HOST",     "localhost");
            String port     = System.getenv().getOrDefault("DB_PORT",     "5432");
            String dbName   = System.getenv().getOrDefault("DB_NAME",     "streambridge");
            String user     = System.getenv().getOrDefault("DB_USER",     "streambridge");
            String password = System.getenv().getOrDefault("DB_PASSWORD", "streambridge");

            var config = new HikariConfig();
            config.setJdbcUrl("jdbc:postgresql://%s:%s/%s".formatted(host, port, dbName));
            config.setUsername(user);
            config.setPassword(password);
            // PostgreSQL handles concurrent writes — pool size > 1 is safe and faster.
            config.setMaximumPoolSize(5);

            this.dataSource = new HikariDataSource(config);
            log.info("PostgreSQL sink initialised at {}:{}/{}", host, port, dbName);
        }

        @Override
        public void write(ProcessedEvent event) throws SQLException {
            var sql = """
                    INSERT INTO processed_events
                        (event_id, event_type, source, routing_key, processed_at, payload, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (event_id) DO NOTHING
                    """;

            try (var conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                ps.setString(1, event.id());
                ps.setString(2, event.eventType().getClass().getSimpleName());
                ps.setString(3, event.source());
                ps.setString(4, event.routingKey());
                ps.setString(5, event.processedAt().toString());
                ps.setString(6, mapper.writeValueAsString(event.cleanedPayload()));
                ps.setString(7, event.status().name());
                ps.executeUpdate();

            } catch (Exception e) {
                throw new SQLException("Failed to write event " + event.id(), e);
            }
        }

        @Override
        public void writeBatch(List<ProcessedEvent> events) throws SQLException {
            if (events.isEmpty()) return;

            var sql = """
                    INSERT INTO processed_events
                        (event_id, event_type, source, routing_key, processed_at, payload, status)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (event_id) DO NOTHING
                    """;

            try (var conn = dataSource.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                conn.setAutoCommit(false);

                for (var event : events) {
                    ps.setString(1, event.id());
                    ps.setString(2, event.eventType().getClass().getSimpleName());
                    ps.setString(3, event.source());
                    ps.setString(4, event.routingKey());
                    ps.setString(5, event.processedAt().toString());
                    ps.setString(6, mapper.writeValueAsString(event.cleanedPayload()));
                    ps.setString(7, event.status().name());
                    ps.addBatch();
                }

                ps.executeBatch();
                conn.commit();
                log.info("Batch wrote {} events to PostgreSQL", events.size());

            } catch (Exception e) {
                throw new SQLException("Batch write failed", e);
            }
        }

        @Override
        public void close() {
            if (dataSource != null && !dataSource.isClosed()) {
                dataSource.close();
                log.info("PostgreSQL connection pool closed");
            }
        }
    }
}
