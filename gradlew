#!/usr/bin/env sh
#
# Minimal Gradle wrapper script (POSIX).
#
set -eu

DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
WRAPPER_JAR="$DIR/gradle/wrapper/gradle-wrapper.jar"

if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Missing $WRAPPER_JAR"
  echo "This repository includes gradle-wrapper.properties but not the wrapper jar."
  echo "Run with a local Gradle installation, or add the wrapper jar."
  exit 1
fi

JAVA_CMD="${JAVA_HOME:-}/bin/java"
if [ ! -x "$JAVA_CMD" ]; then
  JAVA_CMD="java"
fi

exec "$JAVA_CMD" -classpath "$WRAPPER_JAR" org.gradle.wrapper.GradleWrapperMain "$@"

