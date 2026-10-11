#!/bin/sh
# Reproduces scenario 02 via the shared root runner, so the JVM flags (-Xms/-Xmx,
# collector, GC logging) are identical to the other version's.
# Program arguments are passed through, e.g.: ./run.sh --batch-size 1000000
# With no arguments, uses --batch-size 500000 (a single 500 MB batch vs. the
# default 64 MB heap — dies on the cliff quickly and reliably).
set -u
cd "$(dirname "$0")"
if [ $# -eq 0 ]; then
  set -- --batch-size 500000
fi
exec ../../run.sh scenario-02-oversized-batch broken "$@"
