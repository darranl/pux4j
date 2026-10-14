#!/usr/bin/env bash
# Headless check: hosts the real dice demo on EInkBridge at both panel geometries and drives it.
# Frames: pux4j-bridge-smoke/target/dice-bridge-check/<panel>/frame-NNNN.png
#
# Usage: ./run-dice-check.sh      (exit code 0 = pass)
exec "$(cd "$(dirname "$0")" && pwd)/run-check.sh" DiceBridgeCheck "$@"
