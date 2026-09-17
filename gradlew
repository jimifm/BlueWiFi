#!/bin/sh
DIR="$(cd "$(dirname "$0")" && pwd)"
WRAPPER_DIR="$DIR/gradle/wrapper"

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

if [ -f "$WRAPPER_DIR/gradle-wrapper.jar" ]; then
    exec "$JAVACMD" "-Dorg.gradle.appname=gradlew" -classpath "$WRAPPER_DIR/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi

echo "Error: gradle-wrapper.jar not found at $WRAPPER_DIR"
exit 1
