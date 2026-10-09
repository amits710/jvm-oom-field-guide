#!/bin/sh
# Reproduces scenario 05: leaked connections.
# -Xmx64m keeps the heap small so the OOM lands in seconds, not hours.
# GC logging is on so you see the failure signature described in diagnosis.md.
set -e
cd "$(dirname "$0")"
javac -encoding UTF-8 LeakedConnections.java
java -Xmx64m -XX:+UseParallelGC -Xlog:gc*:file=gc.log:time,uptime,level,tags LeakedConnections
