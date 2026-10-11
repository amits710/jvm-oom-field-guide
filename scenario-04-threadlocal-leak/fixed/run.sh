#!/bin/sh
# Runs the fixed version of scenario 04 via the shared root runner, so the JVM flags (-Xms/-Xmx,
# collector, GC logging) are identical to the other version's.
# Program arguments are passed through, e.g.: ./run.sh --tasks 10000
# With no arguments, uses --tasks 5000 (same workload as the broken version's
# default; per-task cleanup handles it fine).
set -u
cd "$(dirname "$0")"
if [ $# -eq 0 ]; then
  set -- --tasks 5000
fi
exec ../../run.sh scenario-04-threadlocal-leak fixed "$@"
