#!/bin/sh
# Reproduces scenario 04 via the shared root runner, so the JVM flags (-Xms/-Xmx,
# collector, GC logging) are identical to the other version's.
# Program arguments are passed through, e.g.: ./run.sh --tasks 10000
# With no arguments, uses --tasks 5000 (enough per-thread accumulation to OOM
# the broken version on the default 64 MB heap).
set -u
cd "$(dirname "$0")"
if [ $# -eq 0 ]; then
  set -- --tasks 5000
fi
exec ../../run.sh scenario-04-threadlocal-leak broken "$@"
