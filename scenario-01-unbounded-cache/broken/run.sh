#!/bin/sh
# Reproduces scenario 01: unbounded in-memory cache.
# -Xmx64m keeps the heap small so the OOM lands in seconds, not hours.
# Add -Xlog:gc* to watch the GC log signature described in diagnosis.md.
set -e
cd "$(dirname "$0")"
javac UnboundedCache.java
java -Xmx64m UnboundedCache
