#!/usr/bin/env bash
# Run ManualCsReplicaTest — Pi4J FFM SPI + Pi4J GPIO with manual CS on BCM 8.
# Requires dtoverlay=spi0-2cs,cs0_pin=26,cs1_pin=23 in /boot/firmware/config.txt
# (moves kernel CE0/CE1 off BCM 8/7 so we can claim BCM 8 as a GPIO).
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
VALIDATION_JAR="$SCRIPT_DIR/target/pux4j-validation-0.1.0-SNAPSHOT.jar"
LIB_DIR="$SCRIPT_DIR/target/run-lib"

prepare_if_needed() {
  if [[ ${SKIP_PREPARE:-0} == "1" ]]; then return; fi
  local should_prepare=0
  if [[ ${FORCE_PREPARE:-0} == "1" ]]; then should_prepare=1
  elif [[ ! -f "$VALIDATION_JAR" || ! -d "$LIB_DIR" ]]; then should_prepare=1
  elif find "$SCRIPT_DIR/src/main" -type f -newer "$VALIDATION_JAR" | grep -q .; then should_prepare=1
  elif [[ "$SCRIPT_DIR/pom.xml" -nt "$VALIDATION_JAR" || "$PROJECT_ROOT/pom.xml" -nt "$VALIDATION_JAR" ]]; then should_prepare=1
  fi
  if [[ $should_prepare -eq 1 ]]; then
    echo "Preparing validation runtime artifacts..."
    (
      cd "$PROJECT_ROOT"
      mvn -pl pux4j-validation -am -DskipTests package
      mvn -pl pux4j-validation -DincludeScope=runtime dependency:copy-dependencies -DoutputDirectory=target/run-lib
    )
  fi
}

prepare_if_needed

MODULE_PATH="$VALIDATION_JAR:$(ls "$LIB_DIR"/*.jar | tr '\n' ':')"

java \
  --module-path "$MODULE_PATH" \
  --enable-native-access=com.pi4j.plugin.ffm \
  -m dev.pux4j.ui.validation/dev.pux4j.ui.validation.ManualCsReplicaTest
