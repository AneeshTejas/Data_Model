-- 01-init.sql — StreamBridge PostgreSQL schema
--
-- Postgres runs every *.sql file in /docker-entrypoint-initdb.d/ on FIRST
-- startup (empty data volume). Re-running 'docker compose up' does NOT re-run
-- these scripts — Postgres skips initdb when the data directory is non-empty.
-- To re-run: 'docker compose down -v' (wipes the volume) then 'docker compose up'.
--
-- Schema mirrors the SQLite schema used in local dev so both EventSink
-- implementations produce identical rows. TEXT columns are idiomatic in Postgres
-- for variable-length strings without a meaningful max length.

CREATE TABLE IF NOT EXISTS processed_events (
    event_id     TEXT PRIMARY KEY,
    event_type   TEXT NOT NULL,
    source       TEXT NOT NULL,
    routing_key  TEXT NOT NULL,
    processed_at TEXT NOT NULL,   -- ISO-8601 string from Instant.toString()
    payload      TEXT NOT NULL,   -- JSON blob from Jackson
    status       TEXT NOT NULL    -- ProcessingStatus enum name
);

-- Index on routing_key:
-- Hevo-style analytics query by destination ("give me all order events").
-- Without an index this is a full sequential scan — O(n) for every query.
-- With this B-tree index Postgres jumps straight to matching rows — O(log n).
CREATE INDEX IF NOT EXISTS idx_routing_key
    ON processed_events (routing_key);

-- Index on processed_at:
-- Pipeline monitoring queries time windows ("events from the last hour").
-- ISO-8601 strings (2024-01-15T10:30:00Z) sort correctly as TEXT in Postgres
-- because the format is left-to-right lexicographic by time component.
CREATE INDEX IF NOT EXISTS idx_processed_at
    ON processed_events (processed_at);
