#!/bin/sh
# Runs the fixed version of scenario 05 via the shared root runner, so the JVM flags (-Xms/-Xmx,
# collector, GC logging) are identical to the other version's.
# Program arguments are passed through, e.g.: ./run.sh --items 500000
set -u
cd "$(dirname "$0")"
exec ../../run.sh scenario-05-leaked-connections fixed "$@"
