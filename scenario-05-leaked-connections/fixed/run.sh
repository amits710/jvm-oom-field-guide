#!/bin/sh
# Runs the fixed version of scenario 05 under the same 64 MB heap that kills
# the broken version. It should complete cleanly.
set -e
cd "$(dirname "$0")"
javac -encoding UTF-8 LeakedConnectionsFixed.java
java -Xmx64m LeakedConnectionsFixed
