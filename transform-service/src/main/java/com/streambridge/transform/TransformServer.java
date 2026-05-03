package com.streambridge.transform;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TransformServer — starts the gRPC server and blocks until shutdown.
 *
 * JAVA CONCEPT: Shutdown hooks
 * ────────────────────────────
 * Runtime.getRuntime().addShutdownHook(thread) registers a thread that the JVM
 * runs automatically when it receives SIGTERM or SIGINT (Ctrl+C), or when
 * System.exit() is called.
 *
 * WHY THIS MATTERS FOR CONTAINERS (Phase 3):
 * When Docker stops a container it sends SIGTERM first, waits a grace period,
 * then sends SIGKILL. If your server has no shutdown hook, SIGTERM just kills
 * it instantly — active requests are dropped and in-flight writes may corrupt.
 * The shutdown hook lets you finish in-flight requests and close connections
 * cleanly before the process exits. Kubernetes does the same thing on pod termination.
 *
 * Python equivalent: signal.signal(signal.SIGTERM, handler) or
 * atexit.register(cleanup_function).
 *
 * HOW gRPC TRANSPORT WORKS HERE:
 * ServerBuilder.forPort(50051) auto-selects the Netty transport when
 * grpc-netty-shaded is on the classpath — no explicit Netty configuration needed.
 * The server uses HTTP/2 over a single TCP port, multiplexing all RPCs.
 */
public class TransformServer {

    private static final Logger log = LoggerFactory.getLogger(TransformServer.class);
    private static final int PORT = 50051;

    public static void main(String[] args) throws Exception {
        Server server = ServerBuilder.forPort(PORT)
                .addService(new TransformServiceImpl())
                .build()
                .start();

        log.info("TransformServer started on port {}", PORT);
        log.info("Registered service: TransformService (unary + server-streaming)");

        // Shutdown hook: JVM calls this thread on SIGTERM / Ctrl+C.
        // server.shutdown() is non-blocking — it stops accepting new RPCs
        // but lets in-flight ones complete (graceful drain).
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Received shutdown signal — draining in-flight RPCs...");
            server.shutdown();
            log.info("TransformServer stopped cleanly");
        }, "grpc-shutdown-hook"));

        // Block the main thread until the server terminates.
        // Without this the main thread exits immediately and the JVM shuts down.
        server.awaitTermination();
    }
}
