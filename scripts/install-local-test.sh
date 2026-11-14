#!/usr/bin/env bash
# Tests of scripts/install-local.sh. Needs only bash and coreutils: the script runs under a scratch
# HOME, so no Pi, no network and none of your real ~/applications or ~/bin are involved.
#
# Run from the repository root:   scripts/install-local-test.sh
# Creates (and removes) its scratch directories under ${TMPDIR:-$HOME/tmp/pux4j}.
set -uo pipefail

SRC_SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/install-local.sh"
BASE="${TMPDIR:-$HOME/tmp/pux4j}"
mkdir -p "$BASE"
WORK="$(mktemp -d "$BASE/install-local-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

pass=0; fail=0
ok()  { echo "ok   - $1"; pass=$((pass+1)); }
bad() { echo "FAIL - $1"; fail=$((fail+1)); }
check() { local name=$1; shift; if "$@" >/dev/null 2>&1; then ok "$name"; else bad "$name"; fi; }

# A synthetic repository layout with a tiny fake bundle.
REPO="$WORK/repo"; FAKEHOME="$WORK/home"; FAKEBIN="$WORK/fakebin"
mkdir -p "$REPO/scripts" "$FAKEHOME" "$FAKEBIN"
cp "$SRC_SCRIPT" "$REPO/scripts/install-local.sh"
make_bundle() { # $1 = number of HAT jars
    local b="$REPO/pux4j-bundle/target/pux4j"
    rm -rf "$b"; mkdir -p "$b/lib" "$b/bin" "$b/conf" "$b/byteman"
    echo jar > "$b/lib/pux4j-core-0.1.0-SNAPSHOT.jar"
    [ "$1" -ge 1 ] && echo jar > "$b/lib/pux4j-driver-hat-2in9v2-0.1.0-SNAPSHOT.jar"
    [ "$1" -ge 2 ] && echo jar > "$b/lib/pux4j-driver-hat-2in13v4-0.1.0-SNAPSHOT.jar"
    printf '#!/usr/bin/env bash\necho pux4j\n' > "$b/bin/pux4j"
    cat > "$b/bin/install.sh" <<'INST'
#!/usr/bin/env bash
mkdir -p "$HOME/bin"
ln -sf "$(cd "$(dirname "$0")" && pwd)/pux4j" "$HOME/bin/pux4j"
echo installed
INST
    chmod +x "$b/bin/pux4j" "$b/bin/install.sh"
    echo conf > "$b/conf/logback-trace.xml"; echo rule > "$b/byteman/x.btm"
}
export PATH="$FAKEBIN:$PATH"
run() { (cd "$REPO" && HOME="$FAKEHOME" scripts/install-local.sh "$@"); }
APP="$FAKEHOME/applications/pux4j"

# --- missing bundle ---------------------------------------------------------
out="$(run 2>&1)"; rc=$?
[ "$rc" -eq 2 ] && ok "missing bundle: exit 2" || bad "missing bundle: exit $rc"
case "$out" in *"-DskipTests -Pbundle-hat-2in9v2"*) ok "missing bundle: message names the build command" ;; *) bad "missing bundle: message" ;; esac

# --- HAT jars: two, none ------------------------------------------------------
make_bundle 2
run >/dev/null 2>&1; rc=$?
[ "$rc" -eq 2 ] && ok "two HAT jars: refused (exit 2)" || bad "two HAT jars: exit $rc"
make_bundle 0
run >/dev/null 2>&1; rc=$?
[ "$rc" -eq 2 ] && ok "no HAT jar: refused (exit 2)" || bad "no HAT jar: exit $rc"

# --- arguments ------------------------------------------------------------------
make_bundle 1
run somehost >/dev/null 2>&1; rc=$?
[ "$rc" -eq 2 ] && ok "a host argument is refused (exit 2)" || bad "host argument: exit $rc"
run --nonsense >/dev/null 2>&1; rc=$?
[ "$rc" -eq 2 ] && ok "unknown option: exit 2" || bad "unknown option: exit $rc"
check "nothing installed after refused runs" test ! -e "$FAKEHOME/applications"

# --- first install ----------------------------------------------------------------
out="$(run 2>&1)"; rc=$?
[ "$rc" -eq 0 ] && ok "first install: exit 0" || { bad "first install: exit $rc"; echo "$out"; }
case "$out" in *"built for the 2in9v2 HAT"*) ok "first install: prints the HAT" ;; *) bad "first install: HAT line" ;; esac
check "first install: lib copied"      test -f "$APP/lib/pux4j-core-0.1.0-SNAPSHOT.jar"
check "first install: bin executable"  test -x "$APP/bin/pux4j"
check "first install: conf copied"     test -f "$APP/conf/logback-trace.xml"
check "first install: byteman copied"  test -f "$APP/byteman/x.btm"
check "first install: ~/bin link made" test -L "$FAKEHOME/bin/pux4j"
check "first install: no .new left"    test ! -e "$APP/.new"

# --- re-install keeps logs, removes stale files -------------------------------------
mkdir -p "$APP/logs"; echo old > "$APP/logs/demo.log"
echo stale > "$APP/lib/stale.jar"
run >/dev/null 2>&1; rc=$?
[ "$rc" -eq 0 ] && ok "re-install: exit 0" || bad "re-install: exit $rc"
check "re-install: logs kept"        test -f "$APP/logs/demo.log"
check "re-install: stale jar gone"   test ! -e "$APP/lib/stale.jar"

# --- --dir (spaces, leading ~/, absolute) ------------------------------------------------
run --dir "my apps" >/dev/null 2>&1; rc=$?
[ "$rc" -eq 0 ] && ok "--dir with a space: exit 0" || bad "--dir with a space: exit $rc"
check "--dir with a space: installed there" test -f "$FAKEHOME/my apps/pux4j/lib/pux4j-core-0.1.0-SNAPSHOT.jar"
run --dir '~/tilde dir' >/dev/null 2>&1; rc=$?
[ "$rc" -eq 0 ] && ok "--dir ~/x: exit 0" || bad "--dir ~/x: exit $rc"
check "--dir ~/x: relative to the home" test -f "$FAKEHOME/tilde dir/pux4j/lib/pux4j-core-0.1.0-SNAPSHOT.jar"
check "--dir ~/x: no literal ~ directory"  test ! -e "$FAKEHOME/~"
run --dir "$WORK/abs" >/dev/null 2>&1; rc=$?
[ "$rc" -eq 0 ] && ok "--dir absolute: exit 0" || bad "--dir absolute: exit $rc"
check "--dir absolute: installed there" test -f "$WORK/abs/pux4j/lib/pux4j-core-0.1.0-SNAPSHOT.jar"

# --- installing over the build output is refused --------------------------------------------
run --dir "$REPO/pux4j-bundle/target" >/dev/null 2>&1; rc=$?
[ "$rc" -eq 2 ] && ok "target is the build output: refused (exit 2)" || bad "target is the build output: exit $rc"
check "target is the build output: bundle intact" test -f "$REPO/pux4j-bundle/target/pux4j/lib/pux4j-core-0.1.0-SNAPSHOT.jar"

# --- a failed copy leaves the previous installation working ------------------------------------
cat > "$FAKEBIN/tar" <<'BADTAR'
#!/usr/bin/env bash
case " $* " in *" -xf "*) cat >/dev/null; exit 1 ;; *) exec /usr/bin/tar "$@" ;; esac
BADTAR
chmod +x "$FAKEBIN/tar"
run >/dev/null 2>&1; rc=$?
[ "$rc" -ne 0 ] && ok "failing tar: install fails" || bad "failing tar: exit 0"
check "failing tar: previous lib kept" test -f "$APP/lib/pux4j-core-0.1.0-SNAPSHOT.jar"
check "failing tar: previous bin kept" test -x "$APP/bin/pux4j"
rm -f "$FAKEBIN/tar"

# --- dry run does nothing ----------------------------------------------------------------------
rm -rf "$FAKEHOME/applications" "$FAKEHOME/bin"
run --dry-run >/dev/null 2>&1; rc=$?
[ "$rc" -eq 0 ] && ok "--dry-run: exit 0" || bad "--dry-run: exit $rc"
check "--dry-run: nothing created" test ! -e "$FAKEHOME/applications"

echo
if [ "$fail" -eq 0 ]; then echo "All $pass checks passed."; else echo "$fail of $((pass+fail)) checks FAILED."; exit 1; fi
