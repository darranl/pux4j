#!/usr/bin/env bash
# Run DisplaySmokeTest under ByteMan hardware-trace instrumentation.
# Build first: cd pux4j-ui && mvn -pl pux4j-validation -am -DskipTests package
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

Build first:
  cd pux4j-ui && mvn -pl pux4j-validation -am -DskipTests package

Examples:
  ./pux4j-validation/run-hardware-trace.sh
  ./pux4j-validation/run-hardware-trace.sh 2>&1 | tee ~/tmp/hw-trace.log
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

if [[ ${1:-} == "check" ]]; then
  echo "Type-checking: $BTM_RULES"
  java -jar "$BYTEMAN_HOME/lib/byteman.jar" -Dorg.jboss.byteman.compile.to.bytecode check "$BTM_RULES"
  echo "Type-check OK"
  exit 0
fi

require_artifacts() {
  local ok=1
  if [[ ! -f "$VALIDATION_JAR" || ! -d "$LIB_DIR" ]]; then
    echo "ERROR: Build artifacts not found."
    echo "       Run: cd pux4j-ui && mvn -pl pux4j-validation -am -DskipTests package"
    ok=0
  fi
  [[ $ok -eq 1 ]]
}
require_artifacts || exit 1

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
