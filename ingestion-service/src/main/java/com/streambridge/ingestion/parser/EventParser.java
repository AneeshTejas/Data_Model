package com.streambridge.ingestion.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.streambridge.ingestion.model.RawEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * EventParser reads JSON event files and turns them into RawEvent objects.
 *
 * JAVA CONCEPTS TO FOCUS ON HERE:
 *
 * 1. ObjectMapper (Jackson): Jackson is THE JSON library in Java.
 *    mapper.readValue(json, RawEvent.class) is Python's json.loads() + dataclass instantiation.
 *    The second argument is the target TYPE — Java needs this because it's statically typed.
 *
 * 2. Stream<T>: Java's lazy iterable, similar to Python generators.
 *    Operations on Stream are lazy — nothing executes until a terminal op (toList, forEach, etc.)
 *    Python equivalent: a generator function with yield.
 *
 * 3. Path / Files: Java's modern file I/O (Java NIO). Prefer this over old File class.
 *    Python equivalent: pathlib.Path
 *
 * 4. Checked vs Unchecked exceptions:
 *    - IOException is CHECKED: the compiler forces you to handle or declare it.
 *    - RuntimeException is UNCHECKED: no obligation to declare it.
 *    Python doesn't have this distinction — all exceptions are unchecked.
 */
public class EventParser {

    // Logger — Java's logging is more structured than Python's print().
    // Logger names usually match the class name — makes log filtering easy.
    private static final Logger log = LoggerFactory.getLogger(EventParser.class);

    // ObjectMapper is THREAD-SAFE and expensive to create — always reuse one instance.
    // Python equivalent: you'd typically just call json.loads() without any setup.
    private final ObjectMapper mapper;

    public EventParser() {
        this.mapper = new ObjectMapper()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    /**
     * Parses a single JSON line into a RawEvent.
     *
     * Returns an Optional — Java's null-safe container.
     * Python equivalent: Optional[RawEvent] from typing, or just returning None.
     *
     * PATTERN: Wrap risky operations (parsing, IO) in try-catch and return Optional.empty()
     * on failure. The caller decides whether to skip or fail.
     */
    public java.util.Optional<RawEvent> parseLine(String jsonLine) {
        if (jsonLine == null || jsonLine.isBlank()) {
            return java.util.Optional.empty();
        }

        try {
            RawEvent event = mapper.readValue(jsonLine, RawEvent.class);
            log.debug("Parsed event id={} type={}", event.id(), event.type());
            return java.util.Optional.of(event);
        } catch (Exception e) {
            // Don't crash the pipeline on a single bad event — log and continue.
            // This resilience pattern is fundamental to data pipelines.
            log.warn("Failed to parse event line, skipping. Line: '{}' Error: {}", jsonLine, e.getMessage());
            return java.util.Optional.empty();
        }
    }

    /**
     * Reads a file where each line is a JSON event (newline-delimited JSON / NDJSON).
     * Returns a Stream — callers can chain filter/map/collect on it.
     *
     * JAVA STREAM CONCEPTS:
     *   Files.lines()           → Stream<String>  (lazy, reads one line at a time)
     *   .filter(...)            → intermediate op (lazy, returns new Stream)
     *   .map(this::parseLine)   → method reference — shorthand for line -> parseLine(line)
     *   .filter(opt::isPresent) → keep only successful parses
     *   .map(opt::get)          → unwrap the Optional
     *
     * Python equivalent:
     *   (parse_line(line) for line in open(path) if not line.strip().startswith("#"))
     *
     * @throws IOException if the file cannot be read
     */
    public Stream<RawEvent> parseFile(Path filePath) throws IOException {
        log.info("Parsing events from file: {}", filePath);

        return Files.lines(filePath)       // Stream<String>, one per line
                .filter(line -> !line.isBlank())
                .filter(line -> !line.startsWith("#"))   // allow comment lines in test files
                .map(this::parseLine)                    // Stream<Optional<RawEvent>>
                .filter(java.util.Optional::isPresent)  // method reference — drops empty()
                .map(java.util.Optional::get);           // unwrap to Stream<RawEvent>
    }

    /**
     * Parses a List of JSON strings. Useful for tests where you don't need a file.
     *
     * JAVA 17 CONCEPT: List.of() creates an IMMUTABLE list (throws on add/remove).
     * Python equivalent: tuple(...) or just a list.
     */
    public List<RawEvent> parseLines(List<String> lines) {
        return lines.stream()
                .map(this::parseLine)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .toList();  // Java 16+ — collects to an unmodifiable List
    }

    /**
     * Serialises a RawEvent back to JSON string. Useful for logging and debugging.
     */
    public String toJson(Object obj) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (IOException e) {
            return "{\"error\": \"serialization failed\"}";
        }
    }
}
