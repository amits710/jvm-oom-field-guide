#!/bin/sh
# Repo-wide smoke test: every broken/ scenario must OOM, every fixed/ must complete.
# Run from the repo root: ./test.sh
set -u
PASS=0; FAIL=0
check_broken() {
  name="$1"; dir="$2"
  out=$(cd "$dir" && timeout 120 ./run.sh 2>&1)
  if echo "$out" | grep -q "OutOfMemoryError"; then
    echo "PASS  $name (broken OOMs)"; PASS=$((PASS+1))
  else
    echo "FAIL  $name (broken did NOT OOM)"; FAIL=$((FAIL+1))
    echo "$out" | tail -3
  fi
}
check_fixed() {
  name="$1"; dir="$2"; expect="$3"
  out=$(cd "$dir" && timeout 180 ./run.sh 2>&1)
  if echo "$out" | grep -q "OutOfMemoryError"; then
    echo "FAIL  $name (fixed OOMed!)"; FAIL=$((FAIL+1))
    echo "$out" | tail -3
  elif echo "$out" | grep -q "$expect"; then
    echo "PASS  $name (fixed completes)"; PASS=$((PASS+1))
  else
    echo "FAIL  $name (fixed: unexpected output)"; FAIL=$((FAIL+1))
    echo "$out" | tail -3
  fi
}
check_broken "01-unbounded-cache"    "scenario-01-unbounded-cache/broken"
check_fixed  "01-unbounded-cache"    "scenario-01-unbounded-cache/fixed" "No OOM"
check_broken "02-oversized-batch"    "scenario-02-oversized-batch/broken"
check_fixed  "02-oversized-batch"    "scenario-02-oversized-batch/fixed" "heap survived"
check_broken "03-report-builder"     "scenario-03-report-builder/broken"
check_fixed  "03-report-builder"     "scenario-03-report-builder/fixed" "heap survived"
check_broken "04-threadlocal-leak"   "scenario-04-threadlocal-leak/broken"
check_fixed  "04-threadlocal-leak"   "scenario-04-threadlocal-leak/fixed" "heap survived"
check_broken "05-leaked-connections" "scenario-05-leaked-connections/broken"
check_fixed  "05-leaked-connections" "scenario-05-leaked-connections/fixed" "heap survived"
echo "=================== $PASS passed, $FAIL failed ==================="
[ "$FAIL" -eq 0 ]
