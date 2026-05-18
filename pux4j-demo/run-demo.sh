#!/usr/bin/env bash
# Run the pux4j counter demo application.
# Auto-prepares runtime artifacts when missing.
#
# Usage:
#   ./run-demo.sh [--scale=<factor>]
#
# Options:
#   --scale=<n>   Display scale factor (default: 3.0). Use 1 for pixel-exact eInk size.
#
# Environment:
#   SKIP_PREPARE=1    Skip auto-build/dependency preparation checks
#   FORCE_PREPARE=1   Force rebuild and dependency refresh before run
set -euo pipefail

SCALE=3.0
for arg in "$@"; do
  case "$arg" in
    --scale=*) SCALE="${arg#--scale=}" ;;
    -h|--help)
      grep '^#' "$0" | sed 's/^# \?//'
      exit 0
      ;;
  esac
done

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
DEMO_JAR="$SCRIPT_DIR/target/pux4j-demo-0.1.0-SNAPSHOT.jar"
LIB_DIR="$SCRIPT_DIR/target/run-lib"

prepare_if_needed() {
  if [[ ${SKIP_PREPARE:-0} == "1" ]]; then
    return
  fi

  local should_prepare=0
  if [[ ${FORCE_PREPARE:-0} == "1" ]]; then
    should_prepare=1
  elif [[ ! -f "$DEMO_JAR" || ! -d "$LIB_DIR" ]]; then
    should_prepare=1
  elif find "$SCRIPT_DIR/src/main" -type f -newer "$DEMO_JAR" | grep -q .; then
    should_prepare=1
  elif [[ "$SCRIPT_DIR/pom.xml" -nt "$DEMO_JAR" || "$PROJECT_ROOT/pom.xml" -nt "$DEMO_JAR" ]]; then
    should_prepare=1
  fi

  if [[ $should_prepare -eq 1 ]]; then
    echo "Preparing demo runtime artifacts..."
    (
      cd "$PROJECT_ROOT"
      mvn -pl pux4j-demo -am -DskipTests package
      mvn -pl pux4j-demo -DincludeScope=runtime dependency:copy-dependencies \
          -DoutputDirectory=pux4j-demo/target/run-lib
    )
  fi
}

prepare_if_needed

MODULE_PATH="$DEMO_JAR:$(ls "$LIB_DIR"/*.jar | tr '\n' ':')"

exec java \
  --module-path "$MODULE_PATH" \
  --enable-native-access=javafx.graphics \
  -Dpux4j.display.scale="$SCALE" \
  -m dev.pux4j.ui.demo/dev.pux4j.ui.demo.DemoApp
