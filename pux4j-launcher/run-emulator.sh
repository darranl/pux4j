#!/usr/bin/env bash
# Run a pux4j demo on the desktop emulator through EInkBridge (drivers are auto-selected).
#
# Usage:
#   ./run-emulator.sh <app> [<app args> ...] [-D<pux4j property>=<value> ...]
#   ./run-emulator.sh --list
#
# Two kinds of app share one name space: JavaFX apps ("demo", the counter, and "dice-demo"; run
# through EInkBridge, extra arguments ignored) and non-JavaFX driver tests ("smoke",
# "validation"; everything after the name is passed to them unchanged). <app> is required while
# more than one app is on the module path; --list shows them with their kind. Only -D* options
# are JVM properties. Exit codes: 0 success, 1 failure, 2 usage error.
# Display size/panel: -Dpux4j.display.* (see EInkBridge).
#
# This script only runs; it never builds. Build first from pux4j-ui/: mvn install (or mvn package).
# After editing sources, build again: the script runs whatever the last build left in target/.
set -euo pipefail

for arg in "$@"; do
  case "$arg" in
    -h|--help)
      grep '^#' "$0" | sed 's/^# \?//'
      exit 0
      ;;
  esac
done

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
LAUNCHER_JAR="$SCRIPT_DIR/target/pux4j-launcher-0.1.0-SNAPSHOT.jar"
LIB_DIR="$SCRIPT_DIR/target/run-lib"

if [[ ! -f "$LAUNCHER_JAR" || ! -d "$LIB_DIR" ]]; then
  echo "ERROR: the launcher bundle is not built." >&2
  echo "       Build it first: cd pux4j-ui && mvn install" >&2
  exit 2
fi

MODULE_PATH="$LAUNCHER_JAR:$(ls "$LIB_DIR"/*.jar | tr '\n' ':')"

JVM_ARGS=()   # -D options
APP_ARGS=()   # app name, its arguments, --list (anything not -D*, passed through unchanged)
for arg in "$@"; do
  case "$arg" in
    -D*) JVM_ARGS+=("$arg") ;;
    *)   APP_ARGS+=("$arg") ;;
  esac
done

exec java \
  --module-path "$MODULE_PATH" \
  --enable-native-access=javafx.graphics \
  "${JVM_ARGS[@]}" \
  -m dev.pux4j.ui.launcher/dev.pux4j.ui.launcher.EInkLauncher \
  "${APP_ARGS[@]}"
