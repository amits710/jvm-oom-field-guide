#!/bin/sh
# Reproduces scenario 05 via the shared root runner, so the JVM flags (-Xms/-Xmx,
# collector, GC logging) are identical to the other version's.
# Program arguments are passed through, e.g.: ./run.sh --connections 20000
# With no arguments, uses --connections 50000 (50K x 256 KB = ~12.8 GB of
# pinned buffers vs. the default 64 MB heap — dies fast).
set -u
cd "$(dirname "$0")"
if [ $# -eq 0 ]; then
  set -- --connections 50000
fi
exec ../../run.sh scenario-05-leaked-connections broken "$@"
