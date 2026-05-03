package com.streambridge.transform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streambridge.transform.proto.TransformRequest;
import com.streambridge.transform.proto.TransformResponse;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for TransformServiceImpl.
 *
 * NO SERVER NEEDED:
 * We instantiate TransformServiceImpl directly and call its methods.
 * The transformation steps are package-private, so tests in the same package
 * can call them without reflection — no gRPC server, no ports, no network.
 *
 * WHAT IS CapturingObserver?
 * gRPC uses StreamObserver callbacks instead of return values.
 * To test this without a real gRPC runtime, we implement a simple
 * CapturingObserver that collects whatever the service method emits.
 * Python equivalent: a list you pass as a callback that collects yielded values.
 */
class TransformServiceImplTest {

    private TransformServiceImpl impl;
    private ObjectMapper mapper;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @BeforeEach
    void setUp() {
        impl = new TransformServiceImpl();
        mapper = new ObjectMapper();
    }

    // ── Step 1: camelCase → snake_case ───────────────────────────────────────

    @Test
    void convertCamelToSnake_renamesKeys() {
        var input = Map.<String, Object>of("userId", "u-1", "orderStatus", "pending", "firstName", "Alice");
        var result = impl.convertCamelToSnake(input);

        assertThat(result).containsKey("user_id")
                          .containsKey("order_status")
                          .containsKey("first_name")
                          .doesNotContainKey("userId")
                          .doesNotContainKey("orderStatus");
    }

    @Test
    void convertCamelToSnake_leavesSnakeCaseUnchanged() {
        var input = Map.<String, Object>of("user_id", "u-1", "credit_card", "4111");
        var result = impl.convertCamelToSnake(input);

        assertThat(result).containsKey("user_id")
                          .containsKey("credit_card");
    }

    // ── Step 2: remove nulls and empty values ────────────────────────────────

    @Test
    void removeNullsAndEmpty_dropsNullValues() {
        var input = new LinkedHashMap<String, Object>();
        input.put("name", "Alice");
        input.put("phone", null);

        var result = impl.removeNullsAndEmpty(input);

        assertThat(result).containsKey("name")
                          .doesNotContainKey("phone");
    }

    @Test
    void removeNullsAndEmpty_dropsBlankStrings() {
        var input = new LinkedHashMap<String, Object>();
        input.put("name", "Alice");
        input.put("notes", "");
        input.put("description", "  ");

        var result = impl.removeNullsAndEmpty(input);

        assertThat(result).containsKey("name")
                          .doesNotContainKey("notes")
                          .doesNotContainKey("description");
    }

    @Test
    void removeNullsAndEmpty_keepsNonEmptyValues() {
        var input = new LinkedHashMap<String, Object>();
        input.put("count", 0);
        input.put("active", false);
        input.put("name", "Alice");
        var result = impl.removeNullsAndEmpty(input);

        // 0 and false are not "empty" — only null/blank strings/empty collections
        assertThat(result).containsKey("count")
                          .containsKey("active")
                          .containsKey("name");
    }

    // ── Step 3: coerce numeric strings ───────────────────────────────────────

    @Test
    void coerceNumericStrings_convertsIntegerStringsToLong() {
        var input = Map.<String, Object>of("age", "42", "count", "0");
        var result = impl.coerceNumericStrings(input);

        assertThat(result.get("age")).isEqualTo(42L);
        assertThat(result.get("count")).isEqualTo(0L);
    }

    @Test
    void coerceNumericStrings_convertsDecimalStringsToDouble() {
        var input = Map.<String, Object>of("score", "3.14", "rate", "0.5");
        var result = impl.coerceNumericStrings(input);

        assertThat(result.get("score")).isEqualTo(3.14);
        assertThat(result.get("rate")).isEqualTo(0.5);
    }

    @Test
    void coerceNumericStrings_leavesNonNumericStringsAlone() {
        var input = Map.<String, Object>of("name", "Alice", "status", "pending");
        var result = impl.coerceNumericStrings(input);

        assertThat(result.get("name")).isEqualTo("Alice");
        assertThat(result.get("status")).isEqualTo("pending");
    }

    // ── Step 4: PII redaction ─────────────────────────────────────────────────

    @Test
    void redactPiiFields_replacesKnownPiiWithRedacted() {
        var input = Map.<String, Object>of(
            "email", "alice@test.com",
            "credit_card", "4111111111111111",
            "ssn", "123-45-6789",
            "phone", "555-1234"
        );
        var result = impl.redactPiiFields(input);

        assertThat(result.get("email")).isEqualTo("[REDACTED]");
        assertThat(result.get("credit_card")).isEqualTo("[REDACTED]");
        assertThat(result.get("ssn")).isEqualTo("[REDACTED]");
        assertThat(result.get("phone")).isEqualTo("[REDACTED]");
    }

    @Test
    void redactPiiFields_leavesNonPiiFieldsAlone() {
        var input = new LinkedHashMap<String, Object>();
        input.put("user_id", "u-1");
        input.put("plan", "pro");
        input.put("amount", 9900L);
        var result = impl.redactPiiFields(input);

        assertThat(result.get("user_id")).isEqualTo("u-1");
        assertThat(result.get("plan")).isEqualTo("pro");
        assertThat(result.get("amount")).isEqualTo(9900L);
    }

    // ── Unary Transform RPC ───────────────────────────────────────────────────

    @Test
    void transform_appliesAllStepsAndReturnsSingleResponse() throws Exception {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("userId", "u-1");       // camelCase → will become user_id
        payload.put("email", "a@test.com"); // PII → will be redacted
        payload.put("age", "30");           // numeric string → will become Long
        payload.put("notes", "");           // empty string → will be removed

        var request = TransformRequest.newBuilder()
                .setEventId("evt-test")
                .setRawJson(mapper.writeValueAsString(payload))
                .build();

        var observer = new CapturingObserver<TransformResponse>();
        impl.transform(request, observer);

        assertThat(observer.completed).isTrue();
        assertThat(observer.error).isNull();
        assertThat(observer.values).hasSize(1);

        var response = observer.values.get(0);
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getEventId()).isEqualTo("evt-test");

        Map<String, Object> out = mapper.readValue(response.getTransformedJson(), MAP_TYPE);
        assertThat(out).containsKey("user_id")
                       .doesNotContainKey("userId");
        assertThat(out.get("email")).isEqualTo("[REDACTED]");
        assertThat(out.get("age")).isEqualTo(30);        // coerced from "30"
        assertThat(out).doesNotContainKey("notes");      // removed (blank)
    }

    @Test
    void transform_malformedJsonSetsSuccessFalseWithMessage() {
        var request = TransformRequest.newBuilder()
                .setEventId("evt-bad")
                .setRawJson("{this is not json}")
                .build();

        var observer = new CapturingObserver<TransformResponse>();
        impl.transform(request, observer);

        // We return a response (not onError) so the caller can read the message
        assertThat(observer.completed).isTrue();
        assertThat(observer.values).hasSize(1);
        assertThat(observer.values.get(0).getSuccess()).isFalse();
        assertThat(observer.values.get(0).getErrorMessage()).isNotBlank();
    }

    @Test
    void transform_blankEventIdCallsOnError() {
        var request = TransformRequest.newBuilder()
                .setEventId("")
                .setRawJson("{}")
                .build();

        var observer = new CapturingObserver<TransformResponse>();
        impl.transform(request, observer);

        assertThat(observer.error).isNotNull();
        assertThat(observer.values).isEmpty();
    }

    // ── Server-streaming StreamTransform RPC ──────────────────────────────────

    @Test
    void streamTransform_emitsExactlyFourResponses() throws Exception {
        var payload = Map.of("userId", "u-1", "email", "alice@test.com");

        var request = TransformRequest.newBuilder()
                .setEventId("evt-stream")
                .setRawJson(mapper.writeValueAsString(payload))
                .build();

        var observer = new CapturingObserver<TransformResponse>();
        impl.streamTransform(request, observer);

        // One response per transformation step
        assertThat(observer.values).hasSize(4);
        assertThat(observer.completed).isTrue();
        assertThat(observer.error).isNull();
    }

    @Test
    void streamTransform_eachResponseHasExactlyOneNote() throws Exception {
        var request = TransformRequest.newBuilder()
                .setEventId("evt-notes")
                .setRawJson(mapper.writeValueAsString(Map.of("key", "value")))
                .build();

        var observer = new CapturingObserver<TransformResponse>();
        impl.streamTransform(request, observer);

        observer.values.forEach(response ->
            assertThat(response.getTransformationNotesList()).hasSize(1)
        );
    }

    @Test
    void streamTransform_finalResponseHasRedactedPii() throws Exception {
        var payload = Map.of("email", "alice@test.com", "user_id", "u-1");

        var request = TransformRequest.newBuilder()
                .setEventId("evt-pii")
                .setRawJson(mapper.writeValueAsString(payload))
                .build();

        var observer = new CapturingObserver<TransformResponse>();
        impl.streamTransform(request, observer);

        // Last response (step 4) has PII redacted
        var lastResponse = observer.values.get(3);
        Map<String, Object> out = mapper.readValue(lastResponse.getTransformedJson(), MAP_TYPE);
        assertThat(out.get("email")).isEqualTo("[REDACTED]");
        assertThat(out.get("user_id")).isEqualTo("u-1");
    }

    // ── Helper: captures whatever a StreamObserver receives ──────────────────

    /**
     * CapturingObserver collects all onNext() calls into a list.
     * Used to test gRPC service methods without a running server.
     *
     * Python equivalent: a list passed as a callback:
     *   responses = []
     *   service.stream_transform(request, callback=responses.append)
     */
    static class CapturingObserver<T> implements StreamObserver<T> {
        final List<T> values = new ArrayList<>();
        Throwable error;
        boolean completed;

        @Override public void onNext(T value)      { values.add(value); }
        @Override public void onError(Throwable t)  { error = t; }
        @Override public void onCompleted()         { completed = true; }
    }
}
