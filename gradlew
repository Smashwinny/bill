#!/usr/bin/env sh

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
if [ -z "$JAVA_HOME" ]; then
  JAVA_EXE="$(command -v java || true)"
  if [ -z "$JAVA_EXE" ]; then
    echo "ERROR: JAVA_HOME is not set and no java executable found in PATH." >&2
    exit 1
  fi
else
  JAVA_EXE="$JAVA_HOME/bin/java"
fi

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
if [ ! -f "$CLASSPATH" ]; then
  echo "ERROR: Missing $CLASSPATH. Gradle wrapper jar not found." >&2
  echo "Hint: run from networked environment to bootstrap wrapper jar from distribution metadata." >&2
  exit 1
fi

exec "$JAVA_EXE" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
