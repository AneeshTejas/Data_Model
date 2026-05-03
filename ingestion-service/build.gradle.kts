import java.time.LocalDateTime

// ingestion-service/build.gradle.kts
// This is the build file for just this module — like a module-level requirements.txt + setup.py
//
// KEY CONCEPT: In Gradle, dependencies have "configurations" (scopes):
//   implementation  = needed to compile AND run (like install_requires in setup.py)
//   testImplementation = only for tests (like extras_require["test"])
//   runtimeOnly     = only needed at runtime, not compile time

plugins {
    java
    application   // adds a 'run' task and lets us define a main class
}

application {
    mainClass.set("com.streambridge.ingestion.IngestionApp")
}

dependencies {
    // Jackson: JSON parsing — the Python equivalent of json + pydantic combined
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")

    // HikariCP: database connection pool — manages your DB connections efficiently
    // Python equivalent: SQLAlchemy's connection pool, but as a standalone library
    implementation("com.zaxxer:HikariCP:5.1.0")

    // PostgreSQL JDBC driver — how Java talks to Postgres
    // Python equivalent: psycopg2
    implementation("org.postgresql:postgresql:42.7.3")

    // SQLite driver — for local dev without a running Postgres
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")

    // SLF4J + Logback: structured logging
    // Python equivalent: logging module + structlog
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Test deps
    testImplementation("org.assertj:assertj-core:3.25.3")

    // ── Phase 2: gRPC TransformClient ────────────────────────────────────────
    // project(":transform-service") gives us the generated proto stubs
    // (TransformRequest, TransformResponse, TransformServiceGrpc) AND pulls in
    // grpc-stub + grpc-protobuf transitively for compilation of TransformClient.
    implementation(project(":transform-service"))

    // grpc-stub + grpc-protobuf are needed at compile time to use the generated
    // blocking stub (AbstractBlockingStub) and protobuf message base classes
    // (MessageOrBuilder, GeneratedMessageV3). Gradle's 'implementation' scope
    // on transform-service only exposes its compiled .class files, not its own
    // transitive deps — so we must declare these explicitly here.
    implementation("io.grpc:grpc-stub:1.65.0")
    implementation("io.grpc:grpc-protobuf:1.65.0")

    // grpc-netty-shaded provides the actual HTTP/2 channel transport at runtime.
    implementation("io.grpc:grpc-netty-shaded:1.65.0")

    // javax.annotation-api: the generated stubs have @javax.annotation.Generated,
    // which was removed from the JDK module path in Java 9+.
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
}

// ─────────────────────────────────────────────────────────────────────────────
// CUSTOM GRADLE TASK — this is where Gradle shines over Maven.
// You can write arbitrary code as part of your build.
// This task scans your source files and generates a quick schema report.
// ─────────────────────────────────────────────────────────────────────────────
tasks.register("generateSchemaReport") {
    group = "streambridge"
    description = "Scans event model classes and generates a schema report"

    doLast {
        val outputDir = layout.buildDirectory.dir("reports/schema").get().asFile
        outputDir.mkdirs()
        val report = outputDir.resolve("schema-report.txt")

        val modelDir = file("src/main/java/com/streambridge/ingestion/model")
        val lines = mutableListOf<String>()
        lines.add("StreamBridge Schema Report")
        lines.add("Generated: ${LocalDateTime.now()}")
        lines.add("=".repeat(50))

        if (modelDir.exists()) {
            modelDir.listFiles()?.filter { it.extension == "java" }?.forEach { f ->
                lines.add("\nModel: ${f.nameWithoutExtension}")
                f.readLines()
                    .filter { it.trim().startsWith("String ") || it.trim().startsWith("int ") || it.trim().startsWith("long ") || it.trim().startsWith("boolean ") || it.trim().startsWith("List<") }
                    .map { it.trim().trimEnd(',') }
                    .forEach { lines.add("  field: $it") }
            }
        }

        report.writeText(lines.joinToString("\n"))
        println("Schema report written to: ${report.absolutePath}")
    }
}

// Make 'build' also run our custom task
tasks.named("build") {
    finalizedBy("generateSchemaReport")
}
