#!/bin/sh
# Unified runner for the JVM Heap OOM Field Guide.
#
# Usage:
#   ./run.sh <scenario-dir> <broken|fixed|compare> [-- program args...]
#
# Examples:
#   ./run.sh scenario-01-unbounded-cache broken
#   ./run.sh scenario-01-unbounded-cache fixed --items 500000
#   ./run.sh scenario-02-oversized-batch compare
#
# Broken and fixed always run under IDENTICAL JVM flags (see common.sh):
# same -Xms/-Xmx, same collector, same GC logging. The per-scenario run.sh
# wrappers delegate here, so the flags cannot drift between versions.
set -u

ROOT="$(cd "$(dirname "$0")" && pwd)"
. "$ROOT/common.sh"

usage() {
  echo "usage: $0 <scenario-dir> <broken|fixed|compare> [-- args...]" >&2
  echo "scenarios:" >&2
  echo "  scenario-01-unbounded-cache  scenario-02-oversized-batch" >&2
  echo "  scenario-03-report-builder    scenario-04-threadlocal-leak" >&2
  echo "  scenario-05-leaked-connections" >&2
  exit 2
}

[ $# -ge 2 ] || usage
SCENARIO="$1"; MODE="$2"; shift 2
if [ "${1:-}" = "--" ]; then shift; fi  # allow a literal "--" before program args

main_class() {
  case "$1/$2" in
    scenario-01-unbounded-cache/broken)  echo UnboundedCache ;;
    scenario-01-unbounded-cache/fixed)   echo UnboundedCacheFixed ;;
    scenario-02-oversized-batch/broken)  echo OversizedBatch ;;
    scenario-02-oversized-batch/fixed)   echo OversizedBatchFixed ;;
    scenario-03-report-builder/broken)    echo ReportBuilder ;;
    scenario-03-report-builder/fixed)     echo ReportBuilderFixed ;;
    scenario-04-threadlocal-leak/broken)  echo ThreadLocalLeak ;;
    scenario-04-threadlocal-leak/fixed)   echo ThreadLocalLeakFixed ;;
    scenario-05-leaked-connections/broken) echo LeakedConnections ;;
    scenario-05-leaked-connections/fixed)  echo LeakedConnectionsFixed ;;
    *) return 1 ;;
  esac
}

run_one() { # $1=mode, $@=program args
  mode="$1"; shift
  CLASS="$(main_class "$SCENARIO" "$mode")" || {
    echo "unknown scenario/mode: $SCENARIO $mode" >&2; return 2
  }
  DIR="$ROOT/$SCENARIO/$mode"
  [ -d "$DIR" ] || { echo "missing directory: $DIR" >&2; return 2; }
  cd "$DIR"
  GCLOG="$ROOT/$SCENARIO/${SCENARIO}-${mode}-gc.log"
  echo "--- compiling $CLASS.java ---" >&2
  javac -encoding UTF-8 "$CLASS.java"
  echo "--- running: java <shared flags> $CLASS $* ---" >&2
  # shellcheck disable=SC2086  # intentional word splitting of the flag list
  java $(jvm_flags "$GCLOG") "$CLASS" "$@"
}

case "$MODE" in
  broken|fixed)
    run_one "$MODE" "$@"
    ;;
  compare)
    echo "================ broken (expect OutOfMemoryError) ================" >&2
    run_one broken "$@" || echo "(broken exited with status $?)"
    echo "================ fixed (expect clean completion) ================" >&2
    run_one fixed "$@"
    ;;
  *)
    usage
    ;;
esac
