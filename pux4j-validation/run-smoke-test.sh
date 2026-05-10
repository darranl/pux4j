#!/usr/bin/env bash
# Run DisplaySmokeTest against the named driver (default: ssd1675a).
# Must be executed from the pux4j-ui directory after 'mvn package -DskipTests'.
# Requires SPI and I2C enabled on the Pi (raspi-config → Interface Options).
set -euo pipefail

DRIVER=${1:-ssd1675a}
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
VALIDATION_JAR="$SCRIPT_DIR/target/pux4j-validation-0.1.0-SNAPSHOT.jar"
LIB_DIR="$SCRIPT_DIR/target/run-lib"

if [ ! -f "$VALIDATION_JAR" ]; then
  echo "ERROR: $VALIDATION_JAR not found — run 'mvn package -DskipTests' first"
  exit 1
fi
if [ ! -d "$LIB_DIR" ]; then
  echo "ERROR: $LIB_DIR not found — run 'mvn dependency:copy-dependencies -pl pux4j-validation -DincludeScope=runtime -DoutputDirectory=target/run-lib'"
  exit 1
fi

MODULE_PATH="$VALIDATION_JAR:$(ls "$LIB_DIR"/*.jar | tr '\n' ':')"

java \
  --module-path "$MODULE_PATH" \
  --enable-native-access=com.pi4j.plugin.ffm \
  -m dev.pux4j.ui.validation/dev.pux4j.ui.validation.DisplaySmokeTest \
  "$DRIVER"
