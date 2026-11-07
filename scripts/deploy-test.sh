#!/usr/bin/env bash
# Tests of scripts/deploy.sh. Needs only bash and coreutils: a fake `ssh` on the PATH runs the
# "remote" command locally under a scratch HOME, so no Pi and no network are involved.
#
# Run from the repository root:   scripts/deploy-test.sh
# Creates (and removes) its scratch directories under ${TMPDIR:-$HOME/tmp/pux4j}.
set -uo pipefail

SRC_SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/deploy.sh"
BASE="${TMPDIR:-$HOME/tmp/pux4j}"
mkdir -p "$BASE"
WORK="$(mktemp -d "$BASE/deploy-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

pass=0; fail=0
ok()  { echo "ok   - $1"; pass=$((pass+1)); }
bad() { echo "FAIL - $1"; fail=$((fail+1)); }
check() { local name=$1; shift; if "$@" >/dev/null 2>&1; then ok "$name"; else bad "$name"; fi; }

# A synthetic repository layout with a tiny fake bundle.
REPO="$WORK/repo"; PIHOME="$WORK/pihome"; FAKEBIN="$WORK/fakebin"
mkdir -p "$REPO/scripts" "$PIHOME" "$FAKEBIN"
cp "$SRC_SCRIPT" "$REPO/scripts/deploy.sh"
make_bundle() { # $1 = number of HAT jars
    local b="$REPO/pux4j-launcher/target/pux4j"
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

# Fake ssh: ssh [options] [user@]host command   -> run the command locally with HOME=$PIHOME.
cat > "$FAKEBIN/ssh" <<'FAKE'
#!/usr/bin/env bash
echo "$1" >> "$FAKE_SSH_LOG"      # first argument = the [user@]host given to ssh
shift
cd "$FAKE_PIHOME" || exit 99      # a real ssh session starts in the remote home directory
HOME="$FAKE_PIHOME" exec bash -c "$1"
FAKE
chmod +x "$FAKEBIN/ssh"
export FAKE_PIHOME="$PIHOME" FAKE_SSH_LOG="$WORK/ssh.log"
export PATH="$FAKEBIN:$PATH"
run() { (cd "$REPO" && scripts/deploy.sh "$@"); }

# --- missing bundle ---------------------------------------------------------
rm -rf "$REPO/pux4j-launcher"
out="$(run somehost 2>&1)"; rc=$?
[ "$rc" -eq 2 ] && ok "missing bundle: exit 2" || bad "missing bundle: exit $rc"
case "$out" in *"-DskipTests -Pbundle-hat-2in9v2"*) ok "missing bundle: message names the build command" ;; *) bad "missing bundle: message" ;; esac

# --- two HAT jars -------------------------------------------------------------
make_bundle 2
run somehost >/dev/null 2>&1; rc=$?
[ "$rc" -eq 2 ] && ok "two HAT jars: refused (exit 2)" || bad "two HAT jars: exit $rc"
make_bundle 0
run somehost >/dev/null 2>&1; rc=$?
[ "$rc" -eq 2 ] && ok "no HAT jar: refused (exit 2)" || bad "no HAT jar: exit $rc"

# --- first deploy ----------------------------------------------------------------
make_bundle 1
out="$(run somehost 2>&1)"; rc=$?
[ "$rc" -eq 0 ] && ok "first deploy: exit 0" || { bad "first deploy: exit $rc"; echo "$out"; }
case "$out" in *"built for the 2in9v2 HAT"*) ok "first deploy: prints the HAT" ;; *) bad "first deploy: HAT line" ;; esac
check "first deploy: lib copied"      test -f "$PIHOME/applications/pux4j/lib/pux4j-core-0.1.0-SNAPSHOT.jar"
check "first deploy: bin executable"  test -x "$PIHOME/applications/pux4j/bin/pux4j"
check "first deploy: conf copied"     test -f "$PIHOME/applications/pux4j/conf/logback-trace.xml"
check "first deploy: byteman copied"  test -f "$PIHOME/applications/pux4j/byteman/x.btm"
check "first deploy: ~/bin link made" test -L "$PIHOME/bin/pux4j"
check "first deploy: no .new left"    test ! -e "$PIHOME/applications/pux4j/.new"
grep -qx "somehost" "$FAKE_SSH_LOG" && ok "no --user: ssh gets the bare host" || bad "no --user: ssh target ($(head -1 "$FAKE_SSH_LOG"))"

# --- re-deploy keeps logs, removes stale files ------------------------------------
mkdir -p "$PIHOME/applications/pux4j/logs"; echo old > "$PIHOME/applications/pux4j/logs/demo.log"
echo stale > "$PIHOME/applications/pux4j/lib/stale.jar"  # lib/ exists after the first deploy
run somehost >/dev/null 2>&1; rc=$?
[ "$rc" -eq 0 ] && ok "re-deploy: exit 0" || bad "re-deploy: exit $rc"
check "re-deploy: logs kept"        test -f "$PIHOME/applications/pux4j/logs/demo.log"
check "re-deploy: stale jar gone"   test ! -e "$PIHOME/applications/pux4j/lib/stale.jar"

# --- --user, --dir (spaces, leading ~/) --------------------------------------------
: > "$FAKE_SSH_LOG"
run --user pi --dir "my apps" somehost >/dev/null 2>&1; rc=$?
[ "$rc" -eq 0 ] && ok "--dir with a space: exit 0" || bad "--dir with a space: exit $rc"
check "--dir with a space: deployed there" test -f "$PIHOME/my apps/pux4j/lib/pux4j-core-0.1.0-SNAPSHOT.jar"
grep -qx "pi@somehost" "$FAKE_SSH_LOG" && ok "--user: ssh gets user@host" || bad "--user: ssh target"
run --dir '~/tilde dir' somehost >/dev/null 2>&1; rc=$?
[ "$rc" -eq 0 ] && ok "--dir ~/x: exit 0" || bad "--dir ~/x: exit $rc"
check "--dir ~/x: relative to the Pi home" test -f "$PIHOME/tilde dir/pux4j/lib/pux4j-core-0.1.0-SNAPSHOT.jar"
check "--dir ~/x: no literal ~ directory"  test ! -e "$PIHOME/~"

# --- a failed transfer leaves the previous deployment working ----------------------------
cat > "$FAKEBIN/tar" <<'BADTAR'
#!/usr/bin/env bash
case " $* " in *" -xf "*) cat >/dev/null; exit 1 ;; *) exec /usr/bin/tar "$@" ;; esac
BADTAR
chmod +x "$FAKEBIN/tar"
run somehost >/dev/null 2>&1; rc=$?
[ "$rc" -ne 0 ] && ok "failing tar: deploy fails" || bad "failing tar: exit 0"
check "failing tar: previous lib kept" test -f "$PIHOME/applications/pux4j/lib/pux4j-core-0.1.0-SNAPSHOT.jar"
rm -f "$FAKEBIN/tar"

# --- dry run does nothing ----------------------------------------------------------------
rm -rf "$PIHOME/applications" "$PIHOME/bin"
run --dry-run somehost >/dev/null 2>&1; rc=$?
[ "$rc" -eq 0 ] && ok "--dry-run: exit 0" || bad "--dry-run: exit $rc"
check "--dry-run: nothing created" test ! -e "$PIHOME/applications"

echo
if [ "$fail" -eq 0 ]; then echo "All $pass checks passed."; else echo "$fail of $((pass+fail)) checks FAILED."; exit 1; fi
