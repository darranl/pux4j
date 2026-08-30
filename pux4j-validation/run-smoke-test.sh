#!/usr/bin/env bash
# Run DisplaySmokeTest against a physically attached display.
# Build first: cd pux4j-ui && mvn -pl pux4j-validation -am -DskipTests package
#
# Requires SPI and I2C enabled on the Pi (raspi-config → Interface Options).
set -euo pipefail

DRIVER="${1:-}"
ENABLE_BYTEMAN=0
BYTEMAN_SCRIPT="partial-corruption-check.btm"
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
VALIDATION_JAR="$SCRIPT_DIR/target/pux4j-validation-0.1.0-SNAPSHOT.jar"
LIB_DIR="$SCRIPT_DIR/target/run-lib"

usage() {
  cat <<'EOF'
Usage:
  ./pux4j-validation/run-smoke-test.sh [--enable-byteman [script]] [driver]

Arguments:
  driver                Display driver factory name (auto-selected if omitted)

Options:
  --enable-byteman      Load a ByteMan rule file alongside the test (requires
                        BYTEMAN_HOME to be set). Optionally pass the .btm filename
                        as the next argument (default: partial-corruption-check.btm).

Environment:
  BYTEMAN_HOME          Path to ByteMan installation (required with --enable-byteman)

Build first:
  cd pux4j-ui && mvn -pl pux4j-validation -am -DskipTests package
EOF
}

args=("$@")
set --
for arg in "${args[@]}"; do
  case "$arg" in
    -h|--help)        usage; exit 0 ;;
    --enable-byteman) ENABLE_BYTEMAN=1 ;;
    *.btm)            BYTEMAN_SCRIPT="$arg" ;;
    *)                DRIVER="$arg" ;;
  esac
done

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

BYTEMAN_AGENT_OPTS=""
if [[ $ENABLE_BYTEMAN -eq 1 ]]; then
  if [[ -z "${BYTEMAN_HOME:-}" ]]; then
    echo "ERROR: BYTEMAN_HOME is not set — cannot load ByteMan agent"
    exit 1
  fi
  BTM_JAR="$BYTEMAN_HOME/lib/byteman.jar"
  if [[ ! -f "$BTM_JAR" ]]; then
    echo "ERROR: ByteMan jar not found at $BTM_JAR"
    exit 1
  fi
  BTM_SCRIPT_PATH="$SCRIPT_DIR/src/main/byteman/$BYTEMAN_SCRIPT"
  if [[ ! -f "$BTM_SCRIPT_PATH" ]]; then
    echo "ERROR: ByteMan script not found: $BTM_SCRIPT_PATH"
    exit 1
  fi
  BYTEMAN_AGENT_OPTS="-javaagent:$BTM_JAR=script:$BTM_SCRIPT_PATH"
  echo "ByteMan enabled: $BTM_SCRIPT_PATH"
fi

java \
  $BYTEMAN_AGENT_OPTS \
  -ea \
  --module-path "$MODULE_PATH" \
  --enable-native-access=com.pi4j.plugin.ffm \
  -m dev.pux4j.ui.validation/dev.pux4j.ui.validation.DisplaySmokeTest \
  ${DRIVER:+"$DRIVER"}
