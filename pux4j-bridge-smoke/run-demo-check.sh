#!/usr/bin/env bash
# Headless check: hosts the real counter demo on EInkBridge (PNG display driver +
# programmatic touch) at both panel geometries (296x128 and 250x122) and taps its buttons.
# Frames are written to pux4j-bridge-smoke/target/demo-bridge-check/<panel>/frame-NNNN.png.
# Open the PNGs to check the layout and orientation by eye.
#
# Usage: ./run-demo-check.sh      (exit code 0 = pass)
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
LIB_DIR="$SCRIPT_DIR/target/run-lib"
JAR="$SCRIPT_DIR/target/pux4j-bridge-smoke-0.1.0-SNAPSHOT.jar"

(
  cd "$PROJECT_ROOT"
  rm -rf "$LIB_DIR"
  mvn -q -pl pux4j-bridge-smoke -am -DskipTests -DincludeScope=runtime \
      package dependency:copy-dependencies -DoutputDirectory=target/run-lib
)

MODULE_PATH="$JAR:$(ls "$LIB_DIR"/*.jar | tr '\n' ':')"

exec java \
  --module-path "$MODULE_PATH" \
  --enable-native-access=javafx.graphics \
  "$@" \
  -m dev.pux4j.ui.bridgesmoke/dev.pux4j.ui.bridgesmoke.DemoBridgeCheck
