#!/usr/bin/env bash
# Headless check: hosts the real counter demo on EInkBridge at both panel geometries and drives it.
# Frames: pux4j-bridge-smoke/target/demo-bridge-check/<panel>/frame-NNNN.png
#
# Usage: ./run-demo-check.sh      (exit code 0 = pass)
exec "$(cd "$(dirname "$0")" && pwd)/run-check.sh" DemoBridgeCheck "$@"
