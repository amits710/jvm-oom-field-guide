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

compile_one() { # $1=workdir $2=java source path
  if ! javac -encoding UTF-8 -d "$1/classes" "$2" >"$1/javac.log" 2>&1; then
    return 1
  fi
}

check_broken() { # $1=label $2=scenario-dir $3=class $4=start-marker [$5...=program args]
  label="$1"; scenario="$2"; class="$3"; marker="$4"; shift 4
  dir="$TMPBASE/$label-broken"; mkdir -p "$dir/classes"
  if ! compile_one "$dir" "$ROOT/$scenario/broken/$class.java"; then
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
  if ! compile_one "$dir" "$ROOT/$scenario/fixed/$class.java"; then
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

echo "=================== $PASS passed, $FAIL failed ==================="
[ "$FAIL" -eq 0 ]
