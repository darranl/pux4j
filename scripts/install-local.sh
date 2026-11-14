#!/usr/bin/env bash
# Install the pux4j bundle on THIS machine: for building and running on the Raspberry Pi itself.
# (To build on another computer and copy the bundle to a Pi, use scripts/deploy.sh instead.)
#
# The script does not build anything. Build the bundle for your HAT first, from the repository root:
#     mvn package -DskipTests -Pbundle-hat-2in9v2       # 2.9" V2 HAT
#     mvn package -DskipTests -Pbundle-hat-2in13v4      # 2.13" V4 HAT
# which leaves pux4j-bundle/target/pux4j/. This script copies that directory to
# ~/applications/pux4j and runs its bin/install.sh. The bundle carries one HAT's drivers;
# build the one that matches the HAT on this Pi (the script prints which one it is).
#
# Usage:
#   scripts/install-local.sh [OPTIONS]
#
# Options:
#   --dir <dir>       Directory that will contain the "pux4j" bundle directory. Relative paths are
#                     relative to your home directory; a leading "~/" is accepted and means the same.
#                     (Quote it, or your shell expands the ~.)
#                     Default: applications   (the bundle goes to ~/applications/pux4j)
#   --dry-run         Print what would be done without changing anything
#   -h, --help        Show this help
#
# What it does, in order:
#   1. read-only checks: Java 25 or newer on the PATH, SPI and I2C devices, tar. It warns
#      about anything missing and never changes system settings.
#   2. replaces lib/, bin/, conf/ and byteman/ of the bundle directory with the new ones. The old ones
#      are replaced only after the whole copy succeeded. The logs/ directory is left alone.
#   3. runs bin/install.sh, which links pux4j into ~/bin (creating it if needed).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE="$(cd "$SCRIPT_DIR/.." && pwd)/pux4j-bundle/target/pux4j"

PARENT_DIR="applications"
DRY_RUN=0

usage() { sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
    case $1 in
        --dir)     PARENT_DIR="${2:?--dir needs a value}"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) usage; exit 0 ;;
        -*)        echo "ERROR: unknown option: $1" >&2; exit 2 ;;
        *)         echo "ERROR: unexpected argument: $1 (this script takes no host; see --help)" >&2; exit 2 ;;
    esac
done
PARENT_DIR="${PARENT_DIR#\~/}"      # "~/x" means x, relative to the home directory
[[ -n "$PARENT_DIR" && "$PARENT_DIR" != "~" ]] || { echo "ERROR: --dir must be a directory name" >&2; exit 2; }
case "$PARENT_DIR" in /*) TARGET_PARENT="$PARENT_DIR" ;; *) TARGET_PARENT="$HOME/$PARENT_DIR" ;; esac
TARGET="$TARGET_PARENT/pux4j"

log() { echo "[install-local] $*"; }

# --- the bundle must have been built ---------------------------------------
if [[ ! -d "$BUNDLE/lib" || ! -f "$BUNDLE/bin/pux4j" || ! -f "$BUNDLE/bin/install.sh" ]]; then
    echo "ERROR: the bundle is not built: $BUNDLE" >&2
    echo "       Build it first, from the repository root, for the HAT on this Pi:" >&2
    echo "           mvn package -DskipTests -Pbundle-hat-2in9v2     (2.9\" V2 HAT)" >&2
    echo "           mvn package -DskipTests -Pbundle-hat-2in13v4    (2.13\" V4 HAT)" >&2
    exit 2
fi

# Which HAT the bundle was built for = the HAT driver jar in lib/.
HAT_JARS=("$BUNDLE"/lib/pux4j-driver-hat-*.jar)
if [[ ${#HAT_JARS[@]} -ne 1 || ! -e "${HAT_JARS[0]}" ]]; then
    echo "ERROR: expected exactly one HAT driver jar in $BUNDLE/lib, found ${#HAT_JARS[@]}." >&2
    echo "       Rebuild with exactly one -Pbundle-hat-... profile." >&2
    exit 2
fi
HAT_NAME="$(basename "${HAT_JARS[0]}")"
HAT_NAME="${HAT_NAME#pux4j-driver-hat-}"
HAT_NAME="${HAT_NAME%%-[0-9]*}"

# Installing over the build output would delete the bundle while copying it.
if [[ -d "$TARGET" ]] && [[ "$(cd "$TARGET" && pwd -P)" == "$(cd "$BUNDLE" && pwd -P)" ]]; then
    echo "ERROR: the target is the build output itself: $TARGET" >&2
    echo "       Choose another --dir." >&2
    exit 2
fi

log "bundle:  $BUNDLE"
log "built for the $HAT_NAME HAT"
log "target:  $TARGET"

# --- 1. read-only checks ----------------------------------------------------
log "checking this machine (read-only)..."
major=$(java -version 2>&1 | sed -n '1s/.*version "\([0-9][0-9]*\).*/\1/p' || true)
if [[ -z "$major" ]]; then echo "WARN: no java on the PATH (JDK 25 is required to run pux4j)"
elif [[ "$major" -lt 25 ]]; then echo "WARN: java $major found, JDK 25 or newer is required"
else echo "OK:   java $major"; fi
if [[ -e /dev/spidev0.0 ]]; then echo "OK:   SPI device present"
else echo "WARN: /dev/spidev0.0 not found: SPI is not enabled (see the Raspberry Pi documentation, raspi-config)"; fi
if [[ -e /dev/i2c-1 ]]; then echo "OK:   I2C device present"
else echo "WARN: /dev/i2c-1 not found: I2C is not enabled (see the Raspberry Pi documentation, raspi-config)"; fi
if command -v tar >/dev/null 2>&1; then echo "OK:   tar"; else echo "ERROR: tar is missing" >&2; exit 1; fi

# --- 2. copy the bundle ------------------------------------------------------
# Unpack next to the old files first; the old lib/ bin/ conf/ byteman/ are replaced only after the
# whole copy succeeded, so a failed copy leaves the previous installation working.
log "copying lib/ bin/ conf/ byteman/ (logs/ is kept)..."
if [[ $DRY_RUN -eq 1 ]]; then
    log "[DRY RUN] would replace lib/ bin/ conf/ byteman/ under $TARGET with those of $BUNDLE"
else
    mkdir -p "$TARGET"
    (
        cd "$TARGET"
        rm -rf .new; mkdir .new
        COPYFILE_DISABLE=1 tar -C "$BUNDLE" -cf - lib bin conf byteman | tar -xf - -C .new
        rm -rf lib bin conf byteman
        mv .new/* .
        rmdir .new
    )
fi

# --- 3. install ----------------------------------------------------------------
log "running bin/install.sh..."
if [[ $DRY_RUN -eq 1 ]]; then
    log "[DRY RUN] would run: bash $TARGET/bin/install.sh"
else
    bash "$TARGET/bin/install.sh"
fi

log "Done."
log "    pux4j --list          (if ~/bin is on your PATH; otherwise: $TARGET/bin/pux4j --list)"
log "    pux4j <app>           the JavaFX apps need a desktop session: run from a terminal in it, or export DISPLAY=:0 (or :1)"
