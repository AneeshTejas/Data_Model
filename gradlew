#!/bin/sh
#
# Gradle Wrapper startup script for POSIX-compatible systems (Linux, macOS).
# Used by Docker builder containers; Windows uses gradlew.bat instead.
#
set -e

# Resolve the directory this script lives in (= project root).
# Works correctly even when called from a different working directory.
APP_HOME=$(cd "$(dirname "$0")" && pwd)

# Find the JVM: prefer JAVA_HOME if set, otherwise assume java is on PATH.
if [ -n "$JAVA_HOME" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

WRAPPER_JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"

# WHY -classpath instead of -jar:
# 'java -jar foo.jar' requires a Main-Class entry in foo.jar's MANIFEST.MF.
# Our gradle-wrapper.jar has GradleWrapperMain compiled in but no manifest entry.
# 'java -classpath foo.jar MainClass' works without any manifest entry — we just
# name the main class explicitly. This is how the standard Gradle-generated
# wrapper scripts work on all platforms.
exec "$JAVACMD" \
    -classpath "$WRAPPER_JAR" \
    "-Dapp.home=$APP_HOME" \
    "-Dapp.name=gradlew" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
