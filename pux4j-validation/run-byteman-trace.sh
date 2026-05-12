#!/usr/bin/env bash
# Run a validation target under ByteMan using one of the trace rule sets.
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
PROJECT_ROOT=$(cd "$SCRIPT_DIR/.." && pwd)
VALIDATION_JAR="$SCRIPT_DIR/target/pux4j-validation-0.1.0-SNAPSHOT.jar"
LIB_DIR="$SCRIPT_DIR/target/run-lib"
TRACE_DIR="$SCRIPT_DIR/byteman"
BYTEMAN_HOME=${BYTEMAN_HOME:-/home/darranl/development/byteman-download-4.0.26}
BYTEMAN_BIN="$BYTEMAN_HOME/bin/bmjava.sh"
BYTEMAN_CHECK="$BYTEMAN_HOME/bin/bytemancheck.sh"

usage() {
  cat <<'EOF'
Usage:
  ./pux4j-validation/run-byteman-trace.sh check
  ./pux4j-validation/run-byteman-trace.sh raw [-- extra-args]
  ./pux4j-validation/run-byteman-trace.sh pi4j [-- extra-args]
  ./pux4j-validation/run-byteman-trace.sh manualcs [-- extra-args]

Commands:
  check     Type-check the ByteMan rule files against the built classes
  raw       Run RawSpiReplicaTest with the raw ioctl trace rules
  pi4j      Run WaveShareReplicaTest with the Pi4J trace rules
  manualcs  Run ManualCsReplicaTest with the manual-CS Pi4J trace rules
EOF
}

prepare_if_needed() {
  if [[ ${SKIP_PREPARE:-0} == "1" ]]; then
    return
  fi

  local should_prepare=0
  if [[ ${FORCE_PREPARE:-0} == "1" ]]; then
    should_prepare=1
  elif [[ ! -f "$VALIDATION_JAR" || ! -d "$LIB_DIR" ]]; then
    should_prepare=1
  elif find "$SCRIPT_DIR/src/main" -type f -newer "$VALIDATION_JAR" | grep -q .; then
    should_prepare=1
  elif [[ "$SCRIPT_DIR/pom.xml" -nt "$VALIDATION_JAR" || "$PROJECT_ROOT/pom.xml" -nt "$VALIDATION_JAR" ]]; then
    should_prepare=1
  fi

  if [[ $should_prepare -eq 1 ]]; then
    echo "Preparing validation runtime artifacts..."
    (
      cd "$PROJECT_ROOT"
      mvn -pl pux4j-validation -am -DskipTests package
      mvn -pl pux4j-validation -DincludeScope=runtime dependency:copy-dependencies -DoutputDirectory=target/run-lib
    )
  fi
}

build_classpath() {
  local cp="$SCRIPT_DIR/target/classes"
  for jar in "$LIB_DIR"/*.jar; do
    cp="$cp:$jar"
  done
  printf '%s' "$cp"
}

check_rules() {
  local cp
  cp=$(build_classpath)
  "$BYTEMAN_CHECK" -cp "$cp" "$TRACE_DIR/raw-spi-trace.btm" "$TRACE_DIR/pi4j-spi-trace.btm" "$TRACE_DIR/manualcs-spi-trace.btm"
}

run_trace() {
  local rule_file=$1
  local main_class=$2
  shift 2

  local log_dir="${BYTEMAN_TRACE_LOG_DIR:-$SCRIPT_DIR/target/byteman-traces}"
  mkdir -p "$log_dir"
  local log_file="$log_dir/$(basename "$rule_file" .btm)-$(date +%Y%m%d-%H%M%S).log"

  local module_path="$VALIDATION_JAR"
  for jar in "$LIB_DIR"/*.jar; do
    module_path="$module_path:$jar"
  done

  echo "Logging ByteMan trace to $log_file"
  "$BYTEMAN_BIN" -l "$rule_file" -- \
    --module-path "$module_path" \
    --enable-native-access=com.pi4j.plugin.ffm,dev.pux4j.ui.validation \
    -m dev.pux4j.ui.validation/"$main_class" \
    "$@" 2>&1 | tee "$log_file"
}

if [[ ${1:-} == "-h" || ${1:-} == "--help" || $# -eq 0 ]]; then
  usage
  exit 0
fi

command=$1
shift

case "$command" in
  check)
    prepare_if_needed
    check_rules
    ;;
  raw)
    prepare_if_needed
    if [[ ${1:-} == "--" ]]; then
      shift
    fi
    run_trace "$TRACE_DIR/raw-spi-trace.btm" dev.pux4j.ui.validation.RawSpiReplicaTest "$@"
    ;;
  pi4j)
    prepare_if_needed
    if [[ ${1:-} == "--" ]]; then
      shift
    fi
    run_trace "$TRACE_DIR/pi4j-spi-trace.btm" dev.pux4j.ui.validation.WaveShareReplicaTest "$@"
    ;;
  manualcs)
    prepare_if_needed
    if [[ ${1:-} == "--" ]]; then
      shift
    fi
    run_trace "$TRACE_DIR/manualcs-spi-trace.btm" dev.pux4j.ui.validation.ManualCsReplicaTest "$@"
    ;;
  *)
    usage
    exit 1
    ;;
esac
