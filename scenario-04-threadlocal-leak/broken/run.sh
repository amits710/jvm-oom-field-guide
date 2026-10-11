#!/bin/sh
# Reproduces scenario 04 via the shared root runner, so the JVM flags (-Xms/-Xmx,
# collector, GC logging) are identical to the other version's.
# Program arguments are passed through, e.g.: ./run.sh --items 500000
set -u
cd "$(dirname "$0")"
exec ../../run.sh scenario-04-threadlocal-leak broken "$@"
