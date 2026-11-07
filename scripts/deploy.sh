#!/usr/bin/env bash
# Deploy the pux4j bundle to a Raspberry Pi.
#
# The script does not build anything. Build the bundle for your HAT first, from pux4j-ui/:
#     mvn package -Pbundle-hat-2in9v2       # 2.9" V2 HAT
#     mvn package -Pbundle-hat-2in13v4      # 2.13" V4 HAT
# which leaves pux4j-launcher/target/pux4j/. This script copies that directory to the Pi
# and runs its bin/install.sh there. The bundle carries one HAT's drivers; deploy the
# bundle that matches the HAT on that Pi (the script prints which one it is).
#
# Usage:
#   scripts/deploy.sh [OPTIONS] <host>
#
# Options:
#   --user <user>     SSH user (default: your local user name)
#   --dir <dir>       Directory on the Pi that will contain the "pux4j" bundle directory.
#                     Relative paths are relative to the Pi user's home.
#                     Default: applications   (the bundle goes to ~/applications/pux4j)
#   --dry-run         Print what would be done without connecting or changing anything
#   -h, --help        Show this help
#
# What it does on the Pi, in order:
#   1. read-only checks: Java 25 or newer on the PATH, SPI and I2C devices, tar. It warns
#      about anything missing and never changes system settings.
#   2. replaces lib/, bin/, conf/ and byteman/ of the bundle directory with the new ones
#      (a tar stream over ssh). The logs/ directory is left alone.
#   3. runs bin/install.sh, which links pux4j into ~/bin (creating it if needed).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE="$(cd "$SCRIPT_DIR/.." && pwd)/pux4j-launcher/target/pux4j"

TARGET_USER="${USER:-}"
PARENT_DIR="applications"
DRY_RUN=0
TARGET_HOST=""

usage() { sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
    case $1 in
        --user)    TARGET_USER="${2:?--user needs a value}"; shift 2 ;;
        --dir)     PARENT_DIR="${2:?--dir needs a value}"; shift 2 ;;
        --dry-run) DRY_RUN=1; shift ;;
        -h|--help) usage; exit 0 ;;
        -*)        echo "ERROR: unknown option: $1" >&2; exit 2 ;;
        *)         if [[ -z "$TARGET_HOST" ]]; then TARGET_HOST="$1"
                   else echo "ERROR: unexpected argument: $1" >&2; exit 2; fi
                   shift ;;
    esac
done
[[ -n "$TARGET_HOST" ]] || { echo "ERROR: <host> is required (see --help)" >&2; exit 2; }
[[ -n "$PARENT_DIR" ]] || { echo "ERROR: --dir must not be empty" >&2; exit 2; }

log() { echo "[deploy] $*"; }

# --- the bundle must have been built ---------------------------------------
if [[ ! -d "$BUNDLE/lib" || ! -f "$BUNDLE/bin/pux4j" || ! -f "$BUNDLE/bin/install.sh" ]]; then
    echo "ERROR: the bundle is not built: $BUNDLE" >&2
    echo "       Build it first, from pux4j-ui/, for the HAT on the Pi:" >&2
    echo "           mvn package -Pbundle-hat-2in9v2     (2.9\" V2 HAT)" >&2
    echo "           mvn package -Pbundle-hat-2in13v4    (2.13\" V4 HAT)" >&2
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

REMOTE="${TARGET_USER:+$TARGET_USER@}$TARGET_HOST"
printf -v Q_PARENT '%q' "$PARENT_DIR"
REMOTE_DIR_SH="$Q_PARENT/pux4j"      # shell-quoted; relative paths are relative to the remote home

case "$PARENT_DIR" in /*) SHOW_DIR="$PARENT_DIR" ;; *) SHOW_DIR="~/$PARENT_DIR" ;; esac

log "bundle:  $BUNDLE"
log "built for the $HAT_NAME HAT"
log "target:  $REMOTE:$SHOW_DIR/pux4j"

# --- 1. read-only checks on the Pi ----------------------------------------
CHECK_SCRIPT='
major=$(java -version 2>&1 | sed -n '"'"'1s/.*version "\([0-9][0-9]*\).*/\1/p'"'"')
if [ -z "$major" ]; then echo "WARN: no java on the PATH of a non-interactive ssh shell (JDK 25 is required to run pux4j)"
elif [ "$major" -lt 25 ]; then echo "WARN: java $major found, JDK 25 or newer is required"
else echo "OK:   java $major"; fi
if [ -e /dev/spidev0.0 ]; then echo "OK:   SPI device present"
else echo "WARN: /dev/spidev0.0 not found: SPI is not enabled (see the Raspberry Pi documentation, raspi-config)"; fi
if [ -e /dev/i2c-1 ]; then echo "OK:   I2C device present"
else echo "WARN: /dev/i2c-1 not found: I2C is not enabled (see the Raspberry Pi documentation, raspi-config)"; fi
command -v tar >/dev/null 2>&1 && echo "OK:   tar" || { echo "ERROR: tar is missing on the Pi"; exit 1; }
'
log "checking the Pi (read-only)..."
if [[ $DRY_RUN -eq 1 ]]; then
    log "[DRY RUN] would run on $REMOTE: java version, /dev/spidev0.0, /dev/i2c-1 and tar checks"
else
    ssh "$REMOTE" "sh -s" <<< "$CHECK_SCRIPT"
fi

# --- 2. copy the bundle ------------------------------------------------------
REMOTE_UNPACK="set -e; d=$REMOTE_DIR_SH; mkdir -p \"\$d\"; cd \"\$d\"; rm -rf lib bin conf byteman; tar -xf -"
log "copying lib/ bin/ conf/ byteman/ (logs/ is kept)..."
if [[ $DRY_RUN -eq 1 ]]; then
    log "[DRY RUN] would run: tar -C $BUNDLE -cf - lib bin conf byteman | ssh $REMOTE '<replace those directories under $PARENT_DIR/pux4j and unpack>'"
else
    tar -C "$BUNDLE" -cf - lib bin conf byteman | ssh "$REMOTE" "$REMOTE_UNPACK"
fi

# --- 3. install ----------------------------------------------------------------
log "running bin/install.sh on the Pi..."
if [[ $DRY_RUN -eq 1 ]]; then
    log "[DRY RUN] would run on $REMOTE: bash $PARENT_DIR/pux4j/bin/install.sh"
else
    ssh "$REMOTE" "bash $REMOTE_DIR_SH/bin/install.sh"
fi

log "Done. On $TARGET_HOST:"
log "    pux4j --list          (if ~/bin is on your PATH; otherwise: $SHOW_DIR/pux4j/bin/pux4j --list)"
log "    pux4j <app>           the JavaFX apps need a desktop session: export DISPLAY=:0 (or :1) first"
