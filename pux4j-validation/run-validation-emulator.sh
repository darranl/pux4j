#!/usr/bin/env bash
# Run HardwareValidationTest against the JavaFX emulator (no hardware required).
# Mouse click = touch down; mouse release = touch up.
# Build first: cd pux4j-ui && mvn -pl pux4j-validation -am -DskipTests package
set -euo pipefail

DISPLAY_PROFILE="ssd1675a"
SCALE="3"
EXTRA_ARGS=()
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
VALIDATION_JAR="$SCRIPT_DIR/target/pux4j-validation-0.1.0-SNAPSHOT.jar"
LIB_DIR="$SCRIPT_DIR/target/run-lib"

usage() {
  cat <<'EOF'
Usage:
  ./pux4j-validation/run-validation-emulator.sh [--display=PROFILE] [--scale=N] [extra args...]

Options:
  --display=PROFILE   Display profile: ssd1675a (2.9" V2, default) or ssd1680 (2.13" V4)
  --scale=N           Canvas scale factor (default: 3)
  extra args          Passed through to HardwareValidationTest (e.g. --start-step 3)

Build first:
  cd pux4j-ui && mvn -pl pux4j-validation -am -DskipTests package
EOF
}

while [[ $# -gt 0 ]]; do
  case ${1:-} in
    -h|--help)   usage; exit 0 ;;
    --display=*) DISPLAY_PROFILE="${1#--display=}"; shift ;;
    --scale=*)   SCALE="${1#--scale=}"; shift ;;
    *)           EXTRA_ARGS+=("$1"); shift ;;
  esac
done

# Native touch dimensions must match the display dimensions so TouchCoordinateMapper
# acts as identity (emulator already returns display-logical coordinates).
# ORIENTATION must match each profile's real hardware orientation exactly (see
# dist-hat-2in9v2/dist-hat-2in13v4 in pux4j-validation/pom.xml) — Canvas builds content in
# this orientation's coordinate space, and EmulatorDisplayProfile (pux4j-emulator) renders
# assuming the same. Getting this wrong doesn't crash — it silently renders content rotated
# 180 degrees from correct (found 2026-08-30 checking the ssd1680 profile specifically:
# HardwareValidationTest's own '--orientation' default of LANDSCAPE was always used here,
# never overridden per profile).
case "$DISPLAY_PROFILE" in
  ssd1675a) TOUCH_NATIVE_W=296; TOUCH_NATIVE_H=128; ORIENTATION="LANDSCAPE" ;;
  ssd1680)  TOUCH_NATIVE_W=250; TOUCH_NATIVE_H=122; ORIENTATION="LANDSCAPE_INVERTED" ;;
  *)
    echo "ERROR: Unknown display profile '$DISPLAY_PROFILE'. Valid: ssd1675a, ssd1680"
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
  -Dpux4j.emulator.display="$DISPLAY_PROFILE" \
  -Dpux4j.emulator.scale="$SCALE" \
  --module-path "$MODULE_PATH" \
  --add-modules dev.pux4j.ui.emulator \
  --enable-native-access=javafx.graphics,com.pi4j.plugin.ffm \
  -m dev.pux4j.ui.validation/dev.pux4j.ui.validation.HardwareValidationTest \
  --orientation "$ORIENTATION" \
  --touch-native-width "$TOUCH_NATIVE_W" \
  --touch-native-height "$TOUCH_NATIVE_H" \
  "${EXTRA_ARGS[@]+"${EXTRA_ARGS[@]}"}"
