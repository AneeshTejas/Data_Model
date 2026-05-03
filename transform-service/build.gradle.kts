// transform-service/build.gradle.kts
//
// KEY CONCEPT: What is a .proto file and why do we need a special plugin?
// ────────────────────────────────────────────────────────────────────────
// A .proto file is a schema definition (like Pydantic models in Python, or
// TypeScript interfaces). From one .proto file, protoc (the protobuf compiler)
// generates Java classes for you — you never write them by hand.
//
// The com.google.protobuf Gradle plugin wires protoc into your build so that:
//   1. You run `generateProto` → Java stubs appear in build/generated/source/proto/
//   2. Those generated files are automatically added to the main source set
//   3. Your handwritten Java code can import and use the generated classes
//
// NOTE: Version 1.0.0 is required for Gradle 9 compatibility.
// Version 0.9.4 used APIs that were removed in Gradle 9.

plugins {
    java
    application
    id("com.google.protobuf") version "0.10.0"
    // Phase 4: fat JAR for Docker + CI. com.gradleup.shadow is the maintained
    // fork of johnrengelman.shadow with Gradle 9 support.
    // Adds the 'shadowJar' task → build/libs/transform-service-0.1.0-all.jar
    id("com.gradleup.shadow") version "8.3.5"
}

application {
    mainClass.set("com.streambridge.transform.TransformServer")
}

val grpcVersion     = "1.65.0"
val protobufVersion = "3.25.3"

// OS-aware classifier: protoc and the grpc plugin are native binaries.
// Docker builder runs on Linux; your local machine may be Windows or Mac.
// Gradle downloads the right binary for whichever OS builds the project.
val os   = System.getProperty("os.name").lowercase()
val arch = System.getProperty("os.arch").lowercase()
val protocClassifier = when {
    os.contains("win")                       -> "windows-x86_64"
    os.contains("mac") || os.contains("darwin") ->
        if (arch.contains("aarch64")) "osx-aarch_64" else "osx-x86_64"
    else                                     -> "linux-x86_64"  // Docker / CI
}

dependencies {
    // ── gRPC trifecta ────────────────────────────────────────────────────────
    // grpc-stub:    the stub base classes your service impl extends
    // grpc-protobuf: marshaller that converts protobuf messages over the wire
    // grpc-netty-shaded: the HTTP/2 transport (Netty, shaded to avoid conflicts)
    implementation("io.grpc:grpc-stub:$grpcVersion")
    implementation("io.grpc:grpc-protobuf:$grpcVersion")
    implementation("io.grpc:grpc-netty-shaded:$grpcVersion")

    // The generated stubs have @javax.annotation.Generated on them.
    // This annotation was in the JDK up to Java 8 but removed from the module
    // path in Java 9+. compileOnly = needed to compile, not at runtime.
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")

    // Jackson: parse raw_json string ↔ Map<String, Object> in TransformServiceImpl
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.0")

    // Logging — same versions as ingestion-service
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("ch.qos.logback:logback-classic:1.5.6")

    // Tests
    testImplementation("org.assertj:assertj-core:3.25.3")
    testCompileOnly("javax.annotation:javax.annotation-api:1.3.2")
}

// ── Protobuf / gRPC code generation ──────────────────────────────────────────
//
// HOW THIS WORKS:
//   protoc = the protobuf compiler binary (downloaded from Maven Central)
//   grpc plugin = a protoc plugin that generates the gRPC service stub classes
//
// On Windows you MUST specify the :windows-x86_64@exe classifier — without it
// Gradle downloads a JAR with no executable and fails silently with "file not found".
// On Mac/Linux you would use :osx-x86_64@exe or :linux-x86_64@exe.
//
// The plugin automatically adds build/generated/source/proto/main/{java,grpc}
// to the main source set — you don't need to configure sourceSets manually.
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion:$protocClassifier@exe"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion:$protocClassifier@exe"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}

// ── Shadow JAR configuration (Phase 4) ───────────────────────────────────────
//
// 'shadowJar' produces a single fat JAR (all deps bundled) at:
//   build/libs/transform-service-0.1.0-all.jar
//
// WHY mergeServiceFiles():
// gRPC uses Java's ServiceLoader to discover its Netty transport provider.
// The service declarations live in META-INF/services/ inside each JAR.
// When shadow merges many JARs into one, only the LAST service file wins
// unless you merge them — which is what this does. Without it the gRPC
// server starts but can't find the HTTP/2 transport and throws:
//   "No functional channel service provider found"
tasks.shadowJar {
    mergeServiceFiles()
    manifest {
        attributes["Main-Class"] = "com.streambridge.transform.TransformServer"
    }
    // archiveClassifier = "all" (default) → transform-service-0.1.0-all.jar
    // Keeping the suffix makes it obvious this is the fat JAR, not the thin one.
}
