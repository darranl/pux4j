#!/usr/bin/env bash
# Tests of the bundle scripts bin/pux4j and bin/install.sh. Needs only bash and coreutils; no
# Java, no device: a fake `java` placed first on PATH stands in for the JVM.
#
# Run from pux4j-ui/:   pux4j-launcher/src/test/scripts/bundle-scripts-test.sh
# Creates (and removes) its scratch directories under ${TMPDIR:-$HOME/tmp/pux4j-ui}.
set -uo pipefail

HERE=$(cd -P "$(dirname "${BASH_SOURCE[0]}")" && pwd)
SCRIPTS=$(cd -P "$HERE/../../main/scripts" && pwd)

BASE="${TMPDIR:-$HOME/tmp/pux4j-ui}"
mkdir -p "$BASE"
WORK=$(mktemp -d "$BASE/bundle-scripts-test.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

FAILS=0
check() {   # check <description> <command...>
  local d=$1; shift
  if "$@"; then echo "ok   - $d"; else echo "FAIL - $d"; FAILS=$((FAILS + 1)); fi
}
eq() { [ "$1" = "$2" ]; }
has() { [[ $1 == *"$2"* ]]; }   # has <haystack> <needle>

# --- a staged bundle whose lib/ holds placeholder jars -------------------------------------------
BUNDLE="$WORK/bundle"
mkdir -p "$BUNDLE/bin" "$BUNDLE/lib"
cp "$SCRIPTS/pux4j" "$SCRIPTS/install.sh" "$BUNDLE/bin/"
chmod +x "$BUNDLE/bin/pux4j" "$BUNDLE/bin/install.sh"
touch "$BUNDLE/lib/a.jar" "$BUNDLE/lib/b.jar"

FAKE="$WORK/fakebin"
mkdir -p "$FAKE"
cat > "$FAKE/java" <<'JAVA'
#!/usr/bin/env bash
printf 'ARG:%s\n' "$@" > "$FAKE_JAVA_ARGS"
echo "fake java line 1"
echo "fake java line 2"
echo "fake java error" >&2
exit 3
JAVA
chmod +x "$FAKE/java"
export FAKE_JAVA_ARGS="$WORK/java-args.txt"

# --- logging and exit code ---------------------------------------------------------------------
out=$(PATH="$FAKE:$PATH" "$BUNDLE/bin/pux4j" demo 2>&1); code=$?
check "exit code of java (3) is the script's exit code" eq "$code" 3
check "console shows java stdout" has "$out" "fake java line 1"
check "console shows java stderr" has "$out" "fake java error"
check "logs/demo.log created" test -f "$BUNDLE/logs/demo.log"
check "log holds the output" grep -q "fake java line 2" "$BUNDLE/logs/demo.log"
echo "STALE-MARKER" >> "$BUNDLE/logs/demo.log"
PATH="$FAKE:$PATH" "$BUNDLE/bin/pux4j" demo >/dev/null 2>&1
check "log truncated on the second run" bash -c '! grep -q STALE-MARKER "$1"' _ "$BUNDLE/logs/demo.log"
check "log has exactly the 3 output lines after rerun" eq "$(wc -l < "$BUNDLE/logs/demo.log")" 3

PATH="$FAKE:$PATH" "$BUNDLE/bin/pux4j" validation --start-step 3 >/dev/null 2>&1
check "log named after the first non-dash argument" test -f "$BUNDLE/logs/validation.log"
PATH="$FAKE:$PATH" "$BUNDLE/bin/pux4j" -Dx=y smoke >/dev/null 2>&1
check "-D before the app name is skipped when naming the log" test -f "$BUNDLE/logs/smoke.log"

rm -rf "$BUNDLE/logs"
PATH="$FAKE:$PATH" "$BUNDLE/bin/pux4j" --list >/dev/null 2>&1
check "--list creates no logs directory" test ! -e "$BUNDLE/logs"
PATH="$FAKE:$PATH" "$BUNDLE/bin/pux4j" >/dev/null 2>&1
check "no app name creates no logs directory" test ! -e "$BUNDLE/logs"
"$BUNDLE/bin/pux4j" --help > "$WORK/help.txt" 2>&1
check "--help prints usage" grep -q PUX4J_JAVA_OPTS "$WORK/help.txt"
check "--help creates no logs directory" test ! -e "$BUNDLE/logs"

# --- arguments seen by java ----------------------------------------------------------------------
PATH="$FAKE:$PATH" PUX4J_JAVA_OPTS="-Xfoo -Dbar=baz  -Xqux" "$BUNDLE/bin/pux4j" demo -Dsys=1 arg1 >/dev/null 2>&1
args=$(tr '\n' ' ' < "$FAKE_JAVA_ARGS")
check "PUX4J_JAVA_OPTS words reach java, in order, before the module arguments" \
  has "$args" "ARG:-Xfoo ARG:-Dbar=baz ARG:-Xqux ARG:-Dsys=1 ARG:-m "
check "default heap option kept" has "$args" "ARG:-Xmx192m "
check "native access option kept" has "$args" "ARG:--enable-native-access=javafx.graphics,com.pi4j.plugin.ffm "
check "app name and args passed after the launcher class" has "$args" "EInkLauncher ARG:demo ARG:arg1 "
check "module path lists the lib jars" has "$args" "/lib/a.jar:$BUNDLE/lib/b.jar:"

# --- symlink resolution ---------------------------------------------------------------------------
mkdir -p "$WORK/linkbin"
ln -s "$BUNDLE/bin/pux4j" "$WORK/linkbin/pux4j"
PATH="$FAKE:$PATH" "$WORK/linkbin/pux4j" viaLink >/dev/null 2>&1
check "run through a symlink logs into the bundle" test -f "$BUNDLE/logs/viaLink.log"

# --- install.sh ----------------------------------------------------------------------------------
H1="$WORK/home1"; mkdir -p "$H1"
out=$(HOME="$H1" PATH="/usr/bin:/bin" "$BUNDLE/bin/install.sh" 2>&1); code=$?
check "install: succeeds with no ~/bin" eq "$code" 0
check "install: created ~/bin" test -d "$H1/bin"
check "install: link points at the real bundle script" eq "$(readlink "$H1/bin/pux4j")" "$(cd -P "$BUNDLE" && pwd)/bin/pux4j"
check "install: hints when ~/bin is not on PATH" has "$out" "not on your PATH"
out=$(HOME="$H1" PATH="/usr/bin:/bin" "$BUNDLE/bin/install.sh" 2>&1); code=$?
check "install: idempotent (second run succeeds)" eq "$code" 0
check "install: second run uses the existing ~/bin" has "$out" "Using existing"

H2="$WORK/home2"; mkdir -p "$H2/bin"
out=$(HOME="$H2" PATH="$H2/bin:/usr/bin:/bin" "$BUNDLE/bin/install.sh" 2>&1); code=$?
check "install: succeeds with an existing ~/bin" eq "$code" 0
check "install: no PATH hint when ~/bin is on PATH" bash -c '[[ $1 != *"not on your PATH"* && $1 == *"is on your PATH"* ]]' _ "$out"
check "install: link is executable" test -x "$H2/bin/pux4j"
check "install: no shell profile created" bash -c '! ls -A "$1" | grep -q "^\.\(profile\|bashrc\|bash_profile\)"' _ "$H2"

echo
if [ "$FAILS" -eq 0 ]; then echo "All checks passed."; else echo "$FAILS check(s) failed."; fi
exit "$FAILS"
