#!/usr/bin/env bash
# Run DisplaySmokeTest against the named driver (default: ssd1675a).
# Auto-prepares runtime artifacts when missing.
# Requires SPI and I2C enabled on the Pi (raspi-config → Interface Options).
set -euo pipefail

DRIVER="ssd1675a"
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
  driver                Display driver factory name (default: ssd1675a)

Options:
  --enable-byteman      Load a ByteMan rule file alongside the test (requires
                        BYTEMAN_HOME to be set). Optionally pass the .btm filename
                        as the next argument (default: partial-corruption-check.btm).
                        Rule files are looked up in src/main/byteman/.

Environment:
  SKIP_PREPARE=1        Skip auto-build/dependency preparation checks
  FORCE_PREPARE=1       Force rebuild and dependency refresh before run
  BYTEMAN_HOME          Path to ByteMan installation (required with --enable-byteman)

Notes:
  - The script auto-builds pux4j-validation and refreshes runtime dependencies when artifacts are missing or stale.
EOF
}

# Parse arguments
while [[ $# -gt 0 ]]; do
  case ${1:-} in
    -h|--help)
      usage
      exit 0
      ;;
    --enable-byteman)
      ENABLE_BYTEMAN=1
      shift
      # If next arg is a .btm filename, consume it as the script name
      if [[ $# -gt 0 && "${1:-}" == *.btm ]]; then
        BYTEMAN_SCRIPT="$1"
        shift
      fi
      ;;
    *)
      DRIVER="$1"
      shift
      ;;
  esac
done

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
  "$DRIVER"
