#!/usr/bin/env bash
# Run DisplaySmokeTest against the JavaFX emulator (no hardware required).
# Build first: cd pux4j-ui && mvn -pl pux4j-validation -am -DskipTests package
set -euo pipefail

DISPLAY_PROFILE="ssd1675a"
SCALE="3"
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
VALIDATION_JAR="$SCRIPT_DIR/target/pux4j-validation-0.1.0-SNAPSHOT.jar"
LIB_DIR="$SCRIPT_DIR/target/run-lib"

usage() {
  cat <<'EOF'
Usage:
  ./pux4j-validation/run-smoke-emulator.sh [--display=PROFILE] [--scale=N]

Options:
  --display=PROFILE   Display profile: ssd1675a (2.9" V2, default) or ssd1680 (2.13" V4)
  --scale=N           Canvas scale factor (default: 3)

Build first:
  cd pux4j-ui && mvn -pl pux4j-validation -am -DskipTests package
EOF
}

while [[ $# -gt 0 ]]; do
  case ${1:-} in
    -h|--help)   usage; exit 0 ;;
    --display=*) DISPLAY_PROFILE="${1#--display=}"; shift ;;
    --scale=*)   SCALE="${1#--scale=}"; shift ;;
    *) echo "Unknown argument: $1"; usage; exit 1 ;;
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

java \
  -Dpux4j.emulator.display="$DISPLAY_PROFILE" \
  -Dpux4j.emulator.scale="$SCALE" \
  --module-path "$MODULE_PATH" \
  --add-modules dev.pux4j.ui.emulator \
  -m dev.pux4j.ui.validation/dev.pux4j.ui.validation.DisplaySmokeTest
