#!/usr/bin/env bash
# Link this bundle's bin/pux4j into ~/bin so that `pux4j` can be run from anywhere.
#
# Usage:   <bundle>/bin/install.sh
#
# Creates ~/bin if it does not exist and (re)creates the link ~/bin/pux4j, so it is safe to run
# again, for example after copying a newer bundle over this one. It does not edit any shell
# profile and does not need sudo.
set -euo pipefail

SCRIPT_SOURCE="${BASH_SOURCE[0]}"
while [ -h "$SCRIPT_SOURCE" ]; do
  SCRIPT_DIR=$(cd -P "$(dirname "$SCRIPT_SOURCE")" && pwd)
  SCRIPT_SOURCE=$(readlink "$SCRIPT_SOURCE")
  [[ $SCRIPT_SOURCE != /* ]] && SCRIPT_SOURCE="$SCRIPT_DIR/$SCRIPT_SOURCE"
done
SCRIPT_DIR=$(cd -P "$(dirname "$SCRIPT_SOURCE")" && pwd)
TARGET="$SCRIPT_DIR/pux4j"
BIN_DIR="$HOME/bin"

[ -f "$TARGET" ] || { echo "ERROR: $TARGET not found" >&2; exit 1; }
chmod +x "$TARGET"

if [ -d "$BIN_DIR" ]; then
  echo "Using existing $BIN_DIR"
else
  mkdir -p "$BIN_DIR"
  echo "Created $BIN_DIR"
fi

ln -sf "$TARGET" "$BIN_DIR/pux4j"
echo "Linked $BIN_DIR/pux4j -> $TARGET"

case ":$PATH:" in
  *":$BIN_DIR:"*)
    echo "$BIN_DIR is on your PATH: run  pux4j --list" ;;
  *)
    echo
    echo "NOTE: $BIN_DIR is not on your PATH. Either:"
    echo "  - run it by its full path:  $BIN_DIR/pux4j --list"
    echo "  - log out and back in (many systems add ~/bin to the PATH at login when it exists), or"
    echo "  - add it yourself, e.g. put   export PATH=\"\$HOME/bin:\$PATH\"   in your shell profile." ;;
esac
