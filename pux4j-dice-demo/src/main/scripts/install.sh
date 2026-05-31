#!/usr/bin/env bash
# Install pux4j launcher scripts to ~/bin — idempotent, safe to re-run.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
BIN_DIR="$HOME/bin"

mkdir -p "$BIN_DIR"

for script in "$SCRIPT_DIR"/*; do
  name=$(basename "$script")
  if [[ "$name" == "install.sh" ]]; then
    continue
  fi
  ln -sf "$SCRIPT_DIR/$name" "$BIN_DIR/$name"
  echo "Linked: $BIN_DIR/$name -> $SCRIPT_DIR/$name"
done

echo "Installation complete."
