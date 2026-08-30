#!/usr/bin/env bash
# Run the interactive hardware validation test.
# Build first: cd pux4j-ui && mvn -pl pux4j-validation -am -DskipTests package
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
VALIDATION_JAR="$SCRIPT_DIR/target/pux4j-validation-0.1.0-SNAPSHOT.jar"
LIB_DIR="$SCRIPT_DIR/target/run-lib"

usage() {
  cat <<'EOF'
Usage:
  ./pux4j-validation/run-hardware-validation.sh [profile] [-- extra-java-test-args]

Profiles:
  pi500-2in9        Pi 500+ with WaveShare 2.9" V2 HAT (default)
  little-2in13      LittleRaspberry / 2.13" V4 HAT
  custom            No preset arguments; pass everything via --

Examples:
  ./pux4j-validation/run-hardware-validation.sh
  ./pux4j-validation/run-hardware-validation.sh pi500-2in9
  ./pux4j-validation/run-hardware-validation.sh pi500-2in9 -- --scenario-count 4
  ./pux4j-validation/run-hardware-validation.sh pi500-2in9 -- --all-scenarios
  ./pux4j-validation/run-hardware-validation.sh custom -- --display ssd1675a --touch icnt86x --orientation LANDSCAPE
  ./pux4j-validation/run-hardware-validation.sh pi500-2in9 -- --notes "pi500 baseline run"

Environment:
  VALIDATION_PROFILE   Default profile when not passed on CLI (default: pi500-2in9)

Build first:
  cd pux4j-ui && mvn -pl pux4j-validation -am -DskipTests package
EOF
}

PROFILE=${VALIDATION_PROFILE:-pi500-2in9}
if [[ ${1:-} == "-h" || ${1:-} == "--help" ]]; then
  usage
  exit 0
fi

if [[ ${1:-} != "" && ${1:-} != "--" ]]; then
  PROFILE=$1
  shift
fi

EXTRA_ARGS=()
if [[ ${1:-} == "--" ]]; then
  shift
  EXTRA_ARGS=("$@")
elif [[ $# -gt 0 ]]; then
  EXTRA_ARGS=("$@")
fi

PROFILE_ARGS=()
case "$PROFILE" in
  pi500-2in9)
    PROFILE_ARGS=(
      --display ssd1675a
      --touch icnt86x
      --orientation LANDSCAPE
      --touch-native-width 296
      --touch-native-height 128
      --flip-x
      --flip-y
    )
    ;;
  little-2in13)
    PROFILE_ARGS=(
      --display ssd1680
      --touch gt1151q
      --orientation LANDSCAPE_INVERTED
    )
    ;;
  custom)
    PROFILE_ARGS=()
    ;;
  *)
    echo "ERROR: unknown profile '$PROFILE'"
    usage
    exit 1
    ;;
esac

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

java \
  -ea \
  --module-path "$MODULE_PATH" \
  --enable-native-access=com.pi4j.plugin.ffm \
  -m dev.pux4j.ui.validation/dev.pux4j.ui.validation.HardwareValidationTest \
  "${PROFILE_ARGS[@]}" \
  "${EXTRA_ARGS[@]}"
