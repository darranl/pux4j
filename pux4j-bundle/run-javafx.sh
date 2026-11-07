#!/usr/bin/env bash
# Run the demo or dice-demo as a plain JavaFX desktop program: an ordinary JavaFX window laid out for the
# 296x128 panel, with no pux4j launcher, no emulator and no display or touch drivers. Handy for working on
# an app's layout and behaviour like any other JavaFX program.
#
# Usage:
#   ./run-javafx.sh <demo|dice-demo> [--scale=<n>] [--bezel|--no-bezel] [-D<property>=<value> ...]
#
#   --scale=<n>   zoom the window (default 3; 1 is pixel-exact)
#   --bezel       draw the frame around the display area (the default); --no-bezel drops it
#   -D...         JVM system properties; every other option goes to the program
#
# The window has no title bar: drag it with the mouse, right-click it for an Exit menu.
# Exit codes: 0 success, 1 failure (from the program), 2 usage error or the bundle is not built.
#
# To see the apps on the emulator or a panel instead, use run-emulator.sh or pux4j.
# This script only runs; it never builds. Build first from the repository root: mvn install (or mvn package).
# After editing sources, build again: the script runs whatever the last build left in target/.
set -euo pipefail

usage() { grep '^#' "$0" | sed 's/^# \{0,1\}//' | sed '1d'; }

for arg in "$@"; do
  case "$arg" in
    -h|--help) usage; exit 0 ;;
  esac
done

if [ $# -lt 1 ]; then
  echo "ERROR: give the app to run: demo or dice-demo (see --help)" >&2
  exit 2
fi

APP=$1; shift
case "$APP" in
  demo)      MAIN="dev.pux4j.ui.demo/dev.pux4j.ui.demo.DemoApp" ;;
  dice-demo) MAIN="dev.pux4j.ui.dice.demo/dev.pux4j.ui.dice.demo.DiceDemoApp" ;;
  *)
    echo "ERROR: unknown app '$APP': the JavaFX programs are demo and dice-demo (see --help)" >&2
    exit 2 ;;
esac

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
LIB_DIR="$SCRIPT_DIR/target/run-lib"

# run-lib holds every runtime jar of the emulator bundle, the two demos' included.
if ! compgen -G "$LIB_DIR/pux4j-demo-*.jar" >/dev/null || ! compgen -G "$LIB_DIR/pux4j-dice-demo-*.jar" >/dev/null; then
  echo "ERROR: the bundle is not built." >&2
  echo "       Build it first, from the repository root: mvn install" >&2
  exit 2
fi

MODULE_PATH=$(ls "$LIB_DIR"/*.jar | tr '\n' ':')

JVM_ARGS=()   # -D options
APP_ARGS=()   # everything else, passed to the program unchanged
for arg in "$@"; do
  case "$arg" in
    -D*) JVM_ARGS+=("$arg") ;;
    *)   APP_ARGS+=("$arg") ;;
  esac
done

exec java \
  --module-path "$MODULE_PATH" \
  --enable-native-access=javafx.graphics \
  ${JVM_ARGS[@]+"${JVM_ARGS[@]}"} \
  -m "$MAIN" \
  ${APP_ARGS[@]+"${APP_ARGS[@]}"}
