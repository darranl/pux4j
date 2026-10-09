#!/usr/bin/env bash
# Run a pux4j demo on the desktop emulator through EInkBridge (drivers are auto-selected).
#
# Usage:
#   ./run-emulator.sh [-Dpux4j.app=<name>] [-D<pux4j property>=<value> ...]
#
# The only app today is "demo" (the counter); it is picked automatically while it is the
# only SceneContent on the module path. Display size/panel: -Dpux4j.display.* (see EInkBridge).
#
# Environment:
#   SKIP_PREPARE=1    Skip auto-build/dependency preparation checks
#   FORCE_PREPARE=1   Force rebuild and dependency refresh before run
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
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
HOST_JAR="$SCRIPT_DIR/target/pux4j-host-0.1.0-SNAPSHOT.jar"
LIB_DIR="$SCRIPT_DIR/target/run-lib"

if [[ ${SKIP_PREPARE:-0} != "1" ]]; then
  if [[ ${FORCE_PREPARE:-0} == "1" || ! -f "$HOST_JAR" || ! -d "$LIB_DIR" ]] \
     || find "$PROJECT_ROOT"/pux4j-*/src/main "$PROJECT_ROOT"/pom.xml "$PROJECT_ROOT"/pux4j-*/pom.xml \
             -type f -newer "$LIB_DIR" | grep -q .; then
    echo "Preparing host runtime artifacts..."
    (
      cd "$PROJECT_ROOT"
      # One reactor run so sibling modules resolve to their fresh target/ jars, not ~/.m2.
      rm -rf pux4j-host/target/run-lib
      mvn -pl pux4j-host -am -Pbundle-emulator -DskipTests -DincludeScope=runtime \
          package dependency:copy-dependencies -DoutputDirectory=target/run-lib
    )
  fi
fi

MODULE_PATH="$HOST_JAR:$(ls "$LIB_DIR"/*.jar | tr '\n' ':')"

exec java \
  --module-path "$MODULE_PATH" \
  --enable-native-access=javafx.graphics \
  "$@" \
  -m dev.pux4j.ui.host/dev.pux4j.ui.host.BridgeHost
