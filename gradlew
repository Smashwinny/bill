#!/usr/bin/env sh
##############################################################################
##
##  Gradle startup script for Unix
##
##############################################################################

APP_HOME="$(cd "$(dirname "$0")" && pwd)"
DEFAULT_JVM_OPTS=""

if [ -x "$APP_HOME/gradlew" ] && [ "$(dirname "$0")" != "." ]; then
    :
fi

if ! command -v gradle >/dev/null 2>&1; then
    echo "Gradle wrapper is not fully prepared in this environment."
    echo "Please install gradle or run in an environment with internet to bootstrap wrapper."
    exit 1
fi

exec gradle "$@"
