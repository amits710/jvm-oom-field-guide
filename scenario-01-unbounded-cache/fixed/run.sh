#!/bin/sh
# Runs the fixed version of scenario 01 under the same 64 MB heap that kills
# the broken version. It should complete cleanly.
set -e
cd "$(dirname "$0")"
javac -encoding UTF-8 UnboundedCacheFixed.java
java -Xmx64m UnboundedCacheFixed
