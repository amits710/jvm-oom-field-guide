#!/bin/sh
# Full verification for the JVM Heap OOM Field Guide.
#
#   ./test.sh
#
# Every broken/ program must die with OutOfMemoryError; every fixed/ program
# must complete and print its completion marker with the expected counts.
# Broken runs use -XX:+ExitOnOutOfMemoryError (via OOMFG_TEST=1, see
# common.sh) so the suite is fast and deterministic; the assertions never
# depend on a specific OOM detail message, only on "OutOfMemoryError" plus
# the program's own STARTED marker and a nonzero, non-timeout exit code.
set -u

ROOT="$(cd "$(dirname "$0")" && pwd)"
. "$ROOT/common.sh"
export OOMFG_TEST=1

PASS=0; FAIL=0
TMPBASE="$(mktemp -d "${TMPDIR:-/tmp}/oomfg-test.XXXXXX")"
cleanup() {
  if [ "${OOMFG_KEEP_ARTIFACTS:-0}" = "1" ]; then
    # CI mode: copy GC logs and test outputs where the workflow uploads them.
    rm -rf "$ROOT/test-artifacts"
    mkdir -p "$ROOT/test-artifacts"
    for d in "$TMPBASE"/*/; do
      label="$(basename "$d")"
      mkdir -p "$ROOT/test-artifacts/$label"
      for log in gc.log stdout.log stderr.log javac.log; do
        [ -f "$d/$log" ] && cp "$d/$log" "$ROOT/test-artifacts/$label/"
      done
    done
    # Also keep the special-case outputs (e.g. 03-report.bin).
    for f in "$TMPBASE"/03-report.bin; do
      [ -f "$f" ] && cp "$f" "$ROOT/test-artifacts/"
    done
  fi
  if [ "$FAIL" -eq 0 ]; then
    rm -rf "$TMPBASE"
  else
    echo "FAILURES PRESENT — test artifacts preserved in: $TMPBASE" >&2
  fi
}
trap cleanup EXIT

pass() { PASS=$((PASS + 1)); echo "PASS  $1"; }
fail() { FAIL=$((FAIL + 1)); echo "FAIL  $1"; [ -n "${2:-}" ] && echo "      $2"; }

# ---------- environment validation ----------
for cmd in java javac mktemp grep; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "missing required command: $cmd" >&2; exit 2; }
done
_JAVA_MAJOR="$(java -version 2>&1 | head -1 | sed 's/.*version "\([0-9][0-9]*\).*/\1/')"
if [ "${_JAVA_MAJOR:-0}" -lt 11 ]; then
  echo "JDK 11+ required (found major version: ${_JAVA_MAJOR:-unknown})" >&2; exit 2
fi
echo "testing with: $(java -version 2>&1 | head -1)"

# Prefer GNU timeout; macOS may have gtimeout (coreutils). With neither,
# run directly — CI jobs set their own timeout-minutes as a backstop.
if command -v timeout >/dev/null 2>&1; then
  TIMEOUT="timeout 180"
elif command -v gtimeout >/dev/null 2>&1; then
  TIMEOUT="gtimeout 180"
else
  TIMEOUT=""
  echo "note: no timeout/gtimeout found; running steps without a per-step limit" >&2
fi

compile_one() { # $1=workdir $2...=java source paths (extra shared sources allowed)
  _w="$1"; shift
  # shellcheck disable=SC2086  # intentional: empty extra sources vanish
  if ! javac -encoding UTF-8 -d "$_w/classes" "$@" >"$_w/javac.log" 2>&1; then
    return 1
  fi
}

# Extra shared sources for a scenario (e.g. scenario-03's SummaryCodec.java).
# Prints the path, or nothing.
scenario_extra_src() { # $1=scenario-dir
  if [ -f "$ROOT/$1/SummaryCodec.java" ]; then
    printf '%s' "$ROOT/$1/SummaryCodec.java"
  fi
}

check_broken() { # $1=label $2=scenario-dir $3=class $4=start-marker [$5...=program args]
  label="$1"; scenario="$2"; class="$3"; marker="$4"; shift 4
  dir="$TMPBASE/$label-broken"; mkdir -p "$dir/classes"
  # shellcheck disable=SC2086  # intentional: empty extra sources vanish
  if ! compile_one "$dir" "$ROOT/$scenario/broken/$class.java" $(scenario_extra_src "$scenario"); then
    fail "$label (broken did not compile)"; sed 's/^/      /' "$dir/javac.log"; return
  fi
  # shellcheck disable=SC2086  # intentional splitting of TIMEOUT and flag list
  $TIMEOUT java $(jvm_flags "$dir/gc.log") -cp "$dir/classes" "$class" "$@" \
    >"$dir/stdout.log" 2>"$dir/stderr.log"
  code=$?
  if [ "$code" -eq 0 ]; then
    fail "$label (broken exited 0 — expected OutOfMemoryError)"; return
  fi
  if [ "$code" -eq 124 ]; then
    fail "$label (broken TIMED OUT instead of OOMing)"; return
  fi
  # Note: with -XX:+ExitOnOutOfMemoryError HotSpot prints
  # "Terminating due to java.lang.OutOfMemoryError: ..." to stdout, not
  # stderr — so check both streams. (Without the flag, the stack trace goes
  # to stderr as usual.)
  if ! grep -q "OutOfMemoryError" "$dir/stderr.log" "$dir/stdout.log" 2>/dev/null; then
    fail "$label (neither stream contains OutOfMemoryError)"; tail -4 "$dir/stdout.log" | sed 's/^/      /'; return
  fi
  if ! grep -q "$marker" "$dir/stdout.log"; then
    fail "$label (stdout missing start marker '$marker')"; return
  fi
  pass "$label (broken OOMs as expected, exit=$code)"
}

check_fixed() { # $1=label $2=scenario-dir $3=class $4=completion-grep [$5...=program args]
  label="$1"; scenario="$2"; class="$3"; expect="$4"; shift 4
  dir="$TMPBASE/$label-fixed"; mkdir -p "$dir/classes"
  # shellcheck disable=SC2086  # intentional: empty extra sources vanish
  if ! compile_one "$dir" "$ROOT/$scenario/fixed/$class.java" $(scenario_extra_src "$scenario"); then
    fail "$label (fixed did not compile)"; sed 's/^/      /' "$dir/javac.log"; return
  fi
  # shellcheck disable=SC2086
  $TIMEOUT java $(jvm_flags "$dir/gc.log") -cp "$dir/classes" "$class" "$@" \
    >"$dir/stdout.log" 2>"$dir/stderr.log"
  code=$?
  if grep -q "OutOfMemoryError" "$dir/stderr.log" "$dir/stdout.log" 2>/dev/null; then
    fail "$label (fixed OOMed!)"; tail -4 "$dir/stderr.log" | sed 's/^/      /'; return
  fi
  if [ "$code" -eq 124 ]; then
    fail "$label (fixed TIMED OUT)"; return
  fi
  if [ "$code" -ne 0 ]; then
    fail "$label (fixed exited $code)"; tail -4 "$dir/stderr.log" | sed 's/^/      /'; return
  fi
  if ! grep -q "$expect" "$dir/stdout.log"; then
    fail "$label (stdout missing expected '$expect')"; tail -4 "$dir/stdout.log" | sed 's/^/      /'; return
  fi
  pass "$label (fixed completes: $expect)"
}

echo "================ scenario checks ================"
check_broken "01-unbounded-cache"    scenario-01-unbounded-cache UnboundedCache       "STARTED scenario-01"
check_fixed  "01-unbounded-cache"    scenario-01-unbounded-cache UnboundedCacheFixed  "COMPLETED records=1000000"

check_broken "02-oversized-batch"    scenario-02-oversized-batch OversizedBatch       "STARTED scenario-02"
check_fixed  "02-oversized-batch"    scenario-02-oversized-batch OversizedBatchFixed  "COMPLETED batches=3"
# every batch must have processed the full 250k x 1KB workload:
if [ "$(grep -c 'batch [123] done, checksum=256000000' "$TMPBASE/02-oversized-batch-fixed/stdout.log")" = "3" ]; then
  pass "02 per-batch checksums (3 x 256000000)"
else
  fail "02 per-batch checksums"
fi

check_broken "03-report-builder"     scenario-03-report-builder ReportBuilder        "STARTED scenario-03"
check_fixed  "03-report-builder"     scenario-03-report-builder ReportBuilderFixed   "COMPLETED records=300000 bytes=307200000" \
  --output "$TMPBASE/03-report.bin"
# the streamed report must actually hold all 300k x 1KB summaries:
if [ -f "$TMPBASE/03-report.bin" ]; then
  _sz=$(wc -c < "$TMPBASE/03-report.bin" | tr -d ' ')
  if [ "$_sz" = "307200000" ]; then
    pass "03 report file is exactly 307200000 bytes"
  else
    fail "03 report file size is $_sz, expected 307200000"
  fi
else
  fail "03 report file was not created"
fi

check_broken "04-threadlocal-leak"   scenario-04-threadlocal-leak ThreadLocalLeak     "STARTED scenario-04"
check_fixed  "04-threadlocal-leak"   scenario-04-threadlocal-leak ThreadLocalLeakFixed "COMPLETED tasks=3000"

check_broken "05-leaked-connections" scenario-05-leaked-connections LeakedConnections       "STARTED scenario-05"
check_fixed  "05-leaked-connections" scenario-05-leaked-connections LeakedConnectionsFixed  "COMPLETED connections=100000 open=0"

echo "================ CLI validation checks ================"
# Every program must exit 2 (with a usage line on stderr) on bad CLI input —
# missing values, unknown flags, zero/negative numbers, and overflow.

check_cli_exit2() { # $1=label $2=scenario-dir $3=broken|fixed $4=class [$5...=bad args]
  label="$1"; scenario="$2"; subdir="$3"; class="$4"; shift 4
  dir="$TMPBASE/$label"; mkdir -p "$dir/classes"
  # shellcheck disable=SC2086  # intentional: empty extra sources vanish
  if ! compile_one "$dir" "$ROOT/$scenario/$subdir/$class.java" $(scenario_extra_src "$scenario"); then
    fail "$label (did not compile)"; return
  fi
  java -cp "$dir/classes" "$class" "$@" >"$dir/stdout.log" 2>"$dir/stderr.log"
  code=$?
  if [ "$code" -ne 2 ]; then
    fail "$label (exit=$code, expected 2)"; return
  fi
  if ! grep -q "usage:" "$dir/stderr.log"; then
    fail "$label (stderr missing usage line)"; return
  fi
  pass "$label (exit 2 with usage)"
}

check_cli_exit2 "cli-01-missing-value"  scenario-01-unbounded-cache broken UnboundedCache      --items
check_cli_exit2 "cli-01-unknown-flag"   scenario-01-unbounded-cache broken UnboundedCache      --bogus
check_cli_exit2 "cli-02-zero"           scenario-02-oversized-batch broken OversizedBatch      --batch-size 0
check_cli_exit2 "cli-02-negative"       scenario-02-oversized-batch broken OversizedBatch      --batch-size -1
check_cli_exit2 "cli-02-chunksize-zero" scenario-02-oversized-batch fixed  OversizedBatchFixed --chunk-size 0
check_cli_exit2 "cli-03-unknown-flag"   scenario-03-report-builder  broken ReportBuilder       --nope
check_cli_exit2 "cli-04-negative"       scenario-04-threadlocal-leak broken ThreadLocalLeak    --tasks -5
check_cli_exit2 "cli-05-overflow"       scenario-05-leaked-connections broken LeakedConnections --buffer-kb 3000000

echo "================ shared-source checks ================"
# SummaryCodec must exist and be the single implementation used by both
# scenario-03 versions (no duplicated local copies).
if [ -f "$ROOT/scenario-03-report-builder/SummaryCodec.java" ] \
&& grep -q "SummaryCodec.makeSummary" "$ROOT/scenario-03-report-builder/broken/ReportBuilder.java" \
&& grep -q "SummaryCodec.makeSummary" "$ROOT/scenario-03-report-builder/fixed/ReportBuilderFixed.java" \
&& ! grep -q "static byte\[\] makeSummary" "$ROOT/scenario-03-report-builder/broken/ReportBuilder.java" \
&& ! grep -q "static byte\[\] makeSummary" "$ROOT/scenario-03-report-builder/fixed/ReportBuilderFixed.java"; then
  pass "03 SummaryCodec is the single shared implementation"
else
  fail "03 SummaryCodec wiring (dead code or duplicated makeSummary)"
fi

echo "=================== $PASS passed, $FAIL failed ==================="
[ "$FAIL" -eq 0 ]
