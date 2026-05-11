#!/usr/bin/env bash
# Run RawSpiReplicaTest — bypasses Pi4J FFM SPI and opens /dev/spidev0.0
# directly via Java FFM. Uses SPI_NO_CS + manual CS (Pi4J DigitalOutput
# on BCM 8) to keep CS asserted across cmd+data sequences, matching
# WaveShare's lgpio reference flow.
#
# This is the option-1 diagnostic from the partial-refresh investigation:
# if it produces clean black partials and clean white cleanup full, the
# Pi4J FFM SPI's per-call CS toggling is confirmed as the root cause.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
VALIDATION_JAR="$SCRIPT_DIR/target/pux4j-validation-0.1.0-SNAPSHOT.jar"
LIB_DIR="$SCRIPT_DIR/target/run-lib"

usage() {
  cat <<'EOF'
Usage:
  ./pux4j-validation/run-raw-replica-test.sh

Environment:
  SKIP_PREPARE=1    Skip auto-build/dependency preparation checks
  FORCE_PREPARE=1   Force rebuild and dependency refresh before run
EOF
}

if [[ ${1:-} == "-h" || ${1:-} == "--help" ]]; then
  usage
  exit 0
fi

prepare_if_needed() {
  if [[ ${SKIP_PREPARE:-0} == "1" ]]; then
    return
  fi

  local should_prepare=0
  if [[ ${FORCE_PREPARE:-0} == "1" ]]; then
    should_prepare=1
  elif [[ ! -f "$VALIDATION_JAR" || ! -d "$LIB_DIR" ]]; then
    should_prepare=1
  elif find "$SCRIPT_DIR/src/main" -type f -newer "$VALIDATION_JAR" | grep -q .; then
    should_prepare=1
  elif [[ "$SCRIPT_DIR/pom.xml" -nt "$VALIDATION_JAR" || "$PROJECT_ROOT/pom.xml" -nt "$VALIDATION_JAR" ]]; then
    should_prepare=1
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

if [ ! -f "$VALIDATION_JAR" ]; then
  echo "ERROR: $VALIDATION_JAR not found"
  exit 1
fi
if [ ! -d "$LIB_DIR" ]; then
  echo "ERROR: $LIB_DIR not found"
  exit 1
fi

MODULE_PATH="$VALIDATION_JAR:$(ls "$LIB_DIR"/*.jar | tr '\n' ':')"

# --enable-native-access for both the Pi4J FFM plugin (GPIO) and our
# validation module (raw SPI ioctls).
java \
  --module-path "$MODULE_PATH" \
  --enable-native-access=com.pi4j.plugin.ffm,dev.pux4j.ui.validation \
  -m dev.pux4j.ui.validation/dev.pux4j.ui.validation.RawSpiReplicaTest
