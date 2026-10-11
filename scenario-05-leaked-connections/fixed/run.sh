#!/bin/sh
# Runs the fixed version of scenario 05 via the shared root runner, so the JVM flags (-Xms/-Xmx,
# collector, GC logging) are identical to the other version's.
# Program arguments are passed through, e.g.: ./run.sh --connections 20000
# With no arguments, uses --connections 50000 (same workload as the broken
# version's default; try-with-resources handles it fine).
set -u
cd "$(dirname "$0")"
if [ $# -eq 0 ]; then
  set -- --connections 50000
fi
exec ../../run.sh scenario-05-leaked-connections fixed "$@"
