package com.streambridge.ingestion.grpc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.streambridge.ingestion.model.RawEvent;
import com.streambridge.transform.proto.TransformRequest;
import com.streambridge.transform.proto.TransformResponse;
import com.streambridge.transform.proto.TransformServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * TransformClient — wraps the gRPC stub so the rest of the ingestion-service
 * never has to touch gRPC types directly.
 *
 * JAVA CONCEPT: ManagedChannel
 * ────────────────────────────
 * A ManagedChannel is a long-lived HTTP/2 connection to a gRPC server.
 * Unlike HTTP/1.1 REST clients (one connection per request), a single
 * ManagedChannel multiplexes all RPCs on one TCP connection.
 *
 * Think of it as a connection pool, but for gRPC — you create it once at
 * startup and reuse it for every call. You must call shutdown() when done
 * to release the underlying TCP connection. That's why TransformClient
 * implements AutoCloseable — it can be used in try-with-resources.
 *
 * Python equivalent:
 *   channel = grpc.insecure_channel('localhost:50051')
 *   stub = TransformServiceStub(channel)
 *   # use stub for calls
 *   channel.close()
 *
 * BLOCKING vs ASYNC STUB:
 * We use the blocking stub (TransformServiceBlockingStub) here because
 * IngestionPipeline processes events synchronously. The blocking stub
 * makes the RPC call and waits for the response before returning — simpler
 * to reason about. For high-throughput async use, you'd use the async stub
 * with ListenableFuture or CompletableFuture.
 */
public class TransformClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TransformClient.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ManagedChannel channel;
    private final TransformServiceGrpc.TransformServiceBlockingStub stub;

    public TransformClient(String host, int port) {
        // usePlaintext() = no TLS. Appropriate for internal/local calls.
        // In production (Phase 3+) you'd use .useTransportSecurity() with certificates.
        this.channel = ManagedChannelBuilder.forAddress(host, port)
                .usePlaintext()
                .build();
        this.stub = TransformServiceGrpc.newBlockingStub(channel);
        log.info("TransformClient connected to {}:{}", host, port);
    }

    /**
     * Calls the remote Transform RPC and returns the cleaned payload as a Map.
     *
     * Fail-open: if the gRPC call fails for any reason (server down, timeout,
     * parse error) we log a warning and return the original raw payload rather
     * than dropping the event. This keeps the ingestion pipeline alive even if
     * the transform service is temporarily unavailable.
     */
    public Map<String, Object> transform(RawEvent event) {
        try {
            String rawJson = MAPPER.writeValueAsString(event.payload());

            TransformRequest request = TransformRequest.newBuilder()
                    .setEventId(event.id())
                    .setRawJson(rawJson)
                    .build();

            TransformResponse response = stub.transform(request);

            if (!response.getSuccess()) {
                log.warn("TransformService rejected event id={}: {}",
                         event.id(), response.getErrorMessage());
                return event.payload(); // fall back to raw payload
            }

            return MAPPER.readValue(response.getTransformedJson(), MAP_TYPE);

        } catch (Exception e) {
            log.error("gRPC transform call failed for event id={}, using raw payload: {}",
                      event.id(), e.getMessage());
            return event.payload(); // fail-open: keep event moving through pipeline
        }
    }

    /**
     * Closes the underlying HTTP/2 channel gracefully.
     * awaitTermination gives in-flight RPCs up to 5 seconds to finish.
     * Called automatically when used in try-with-resources.
     */
    @Override
    public void close() {
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            log.info("TransformClient channel closed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }
    }
}
