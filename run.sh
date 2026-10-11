#!/bin/sh
# Unified runner for the JVM Heap OOM Field Guide.
#
# Usage:
#   ./run.sh <scenario-dir> <broken|fixed|compare|diagnose> [-- program args...]
#
# Examples:
#   ./run.sh scenario-01-unbounded-cache broken
#   ./run.sh scenario-01-unbounded-cache fixed --items 500000
#   ./run.sh scenario-02-oversized-batch compare
#   ./run.sh scenario-01-unbounded-cache diagnose
#
# Broken and fixed always run under IDENTICAL JVM flags (see common.sh):
# same -Xms/-Xmx, same collector, same GC logging. The per-scenario run.sh
# wrappers delegate here, so the flags cannot drift between versions.
#
# diagnose mode runs the broken version with -XX:+HeapDumpOnOutOfMemoryError;
# the .hprof file lands in generated-output/. Set OOMFG_JFR=1 for a bounded
# JFR recording alongside the dump. Open the dump in Eclipse MAT or another
# HPROF analyzer.
set -u

ROOT="$(cd "$(dirname "$0")" && pwd)"
case "$ROOT" in
  *[[:space:]]*)
    echo "error: repository path contains whitespace: $ROOT" >&2
    echo "the runner builds JVM flags by word splitting; move the repo to a path without spaces" >&2
    exit 2 ;;
esac
. "$ROOT/common.sh"

usage() {
  echo "usage: $0 <scenario-dir> <broken|fixed|compare|diagnose> [-- args...]" >&2
  echo "scenarios:" >&2
  echo "  scenario-01-unbounded-cache  scenario-02-oversized-batch" >&2
  echo "  scenario-03-report-builder    scenario-04-threadlocal-leak" >&2
  echo "  scenario-05-leaked-connections" >&2
  echo "diagnose: run broken with heap dump to generated-output/" >&2
  echo "  OOMFG_JFR=1 enables a bounded JFR recording too" >&2
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
  # Shared scenario sources (e.g. scenario-03's SummaryCodec.java) live one
  # directory up; compile them alongside the program so both versions use
  # the single canonical implementation.
  _shared=""
  for _f in ../*.java; do
    if [ -e "$_f" ]; then _shared="$_shared $_f"; fi
  done
  echo "--- compiling $CLASS.java${_shared:+ (+ shared sources)} ---" >&2
  # shellcheck disable=SC2086  # intentional word splitting of _shared
  javac -encoding UTF-8 "$CLASS.java" $_shared || {
    echo "compilation failed; not running" >&2; return 2
  }
  echo "--- running: java <shared flags> $CLASS $* ---" >&2
  _flags="$(jvm_flags "$GCLOG")" || {
    echo "cannot compute JVM flags; not running" >&2; return 2
  }
  # shellcheck disable=SC2086  # intentional word splitting of the flag list
  # -cp .:.. so shared scenario classes (one directory up) are visible.
  java $_flags -cp ".:.." "$CLASS" "$@"
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
  diagnose)
    OUTDIR="$ROOT/generated-output"
    mkdir -p "$OUTDIR" || { echo "cannot create $OUTDIR" >&2; exit 2; }
    # Timestamped filenames: HotSpot refuses to overwrite an existing HPROF,
    # so repeated diagnose runs would silently fail to write a new dump.
    STAMP="$(date +%Y%m%d-%H%M%S)-$$"
    DUMP="$OUTDIR/${SCENARIO}-${STAMP}-heapdump.hprof"
    # Never use test mode here: we want the real OOM and the real dump.
    OOMFG_TEST=0
    OOMFG_EXTRA_FLAGS="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=$DUMP"
    if [ "${OOMFG_JFR:-0}" = "1" ]; then
      OOMFG_EXTRA_FLAGS="$OOMFG_EXTRA_FLAGS -XX:StartFlightRecording=filename=$OUTDIR/${SCENARIO}-${STAMP}.jfr,maxsize=50m"
      echo "diagnose: JFR recording to $OUTDIR/${SCENARIO}-${STAMP}.jfr (bounded 50m)" >&2
      echo "diagnose: note: the heap dump is the primary evidence for the retaining path;" >&2
      echo "diagnose: JFR gives complementary allocation/GC/timing info, but a short default" >&2
      echo "diagnose: recording may not contain enough allocation-stack or old-object detail" >&2
    fi
    export OOMFG_EXTRA_FLAGS OOMFG_TEST
    echo "diagnose: heap dump will be written to $DUMP" >&2
    echo "diagnose: open it in Eclipse MAT or another HPROF analyzer" >&2
    run_one broken "$@"
    ;;
  *)
    usage
    ;;
esac
