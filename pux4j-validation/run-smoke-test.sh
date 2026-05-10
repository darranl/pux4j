#!/usr/bin/env bash
# Run DisplaySmokeTest against the named driver (default: ssd1675a).
# Auto-prepares runtime artifacts when missing.
# Requires SPI and I2C enabled on the Pi (raspi-config → Interface Options).
set -euo pipefail

DRIVER=${1:-ssd1675a}
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
VALIDATION_JAR="$SCRIPT_DIR/target/pux4j-validation-0.1.0-SNAPSHOT.jar"
LIB_DIR="$SCRIPT_DIR/target/run-lib"

usage() {
  cat <<'EOF'
Usage:
  ./pux4j-validation/run-smoke-test.sh [driver]

Arguments:
  driver            Display driver factory name (default: ssd1675a)

Environment:
  SKIP_PREPARE=1    Skip auto-build/dependency preparation checks
  FORCE_PREPARE=1   Force rebuild and dependency refresh before run

Notes:
  - The script auto-builds pux4j-validation and refreshes runtime dependencies when artifacts are missing or stale.
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
