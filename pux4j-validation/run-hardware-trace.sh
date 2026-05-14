#!/usr/bin/env bash
# Run DisplaySmokeTest under ByteMan hardware-trace instrumentation.
# Intercepts Ssd1675aDisplayDriver.sendCommand and sendData and prints a
# timestamped SPI-level trace to stdout, prefixed [HW-TRACE].
#
# Filter trace lines:
#   ./pux4j-validation/run-hardware-trace.sh 2>&1 | grep '\[HW-TRACE\]'
#
# Requires:
#   - ByteMan 4.x at $BYTEMAN_HOME (default: ~/development/byteman-download-4.0.26)
#   - Hardware attached and SPI/I2C enabled on the Pi
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
VALIDATION_JAR="$SCRIPT_DIR/target/pux4j-validation-0.1.0-SNAPSHOT.jar"
LIB_DIR="$SCRIPT_DIR/target/run-lib"
BYTEMAN_HOME="${BYTEMAN_HOME:-/home/darranl/development/byteman-download-4.0.26}"
BTM_RULES="$PROJECT_ROOT/pux4j-core/src/main/byteman/hardware-trace.btm"

usage() {
  cat <<'EOF'
Usage:
  ./pux4j-validation/run-hardware-trace.sh [check]

Arguments:
  check             Type-check the ByteMan rule file without running (optional)

Environment:
  BYTEMAN_HOME      Path to ByteMan install (default: ~/development/byteman-download-4.0.26)
  SKIP_PREPARE=1    Skip auto-build/dependency preparation checks
  FORCE_PREPARE=1   Force rebuild and dependency refresh before run

Output:
  All SPI commands and data writes are printed to stdout prefixed [HW-TRACE].
  Filter with: grep '\[HW-TRACE\]'

Examples:
  # Run smoke test with hardware trace
  ./pux4j-validation/run-hardware-trace.sh

  # Capture trace to file
  ./pux4j-validation/run-hardware-trace.sh 2>&1 | tee /tmp/hw-trace.log

  # Type-check rules only
  ./pux4j-validation/run-hardware-trace.sh check
EOF
}

if [[ ${1:-} == "-h" || ${1:-} == "--help" ]]; then
  usage
  exit 0
fi

if [[ ! -f "$BYTEMAN_HOME/lib/byteman.jar" ]]; then
  echo "ERROR: ByteMan not found at $BYTEMAN_HOME"
  echo "       Set BYTEMAN_HOME to point at your ByteMan install directory."
  exit 1
fi

if [[ ! -f "$BTM_RULES" ]]; then
  echo "ERROR: Rule file not found: $BTM_RULES"
  exit 1
fi

# Type-check only
if [[ ${1:-} == "check" ]]; then
  echo "Type-checking: $BTM_RULES"
  java -jar "$BYTEMAN_HOME/lib/byteman.jar" -Dorg.jboss.byteman.compile.to.bytecode check "$BTM_RULES"
  echo "Type-check OK"
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
      mvn -pl pux4j-validation -DincludeScope=runtime dependency:copy-dependencies \
          -DoutputDirectory=target/run-lib
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
BYTEMAN_AGENT="-javaagent:$BYTEMAN_HOME/lib/byteman.jar=script:$BTM_RULES"

echo "Running DisplaySmokeTest with hardware-trace instrumentation..."
echo "ByteMan rules: $BTM_RULES"
echo ""

java \
  $BYTEMAN_AGENT \
  -ea \
  --module-path "$MODULE_PATH" \
  --enable-native-access=com.pi4j.plugin.ffm \
  -m dev.pux4j.ui.validation/dev.pux4j.ui.validation.DisplaySmokeTest \
  ssd1675a
