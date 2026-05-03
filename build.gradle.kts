// Root build file — shared config that applies to ALL submodules.
// Think of this like a base pyproject.toml that every sub-package inherits from.

plugins {
    java
}

allprojects {
    group = "com.streambridge"
    version = "0.1.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        // Java 21 LTS — a superset of Java 17. All Java 17 features work here.
        // Pattern matching in switch (JEP 441) is stable in 21 vs preview in 17.
        // Your company uses Java 17+ — 21 is the current LTS and fully compatible.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Shared test dependencies across all modules
    dependencies {
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
