# Shared JVM flags for the JVM Heap OOM Field Guide.
#
# Broken and fixed versions of every scenario run under IDENTICAL flags:
# same initial and max heap, same collector, same GC logging decorations.
# The only difference between a broken run and a fixed run is the program.
#
# Sourced by run.sh and test.sh — do not execute directly.

# jvm_flags <gc-log-path>: print the shared flags for one run.
jvm_flags() {
  _gclog="$1"
  _flags="-Xms64m -Xmx64m -XX:+UseParallelGC -Xlog:gc*:file=${_gclog}:time,level,tags"
  if [ "${OOMFG_TEST:-0}" = "1" ]; then
    # Test mode only: exit on the first OutOfMemoryError instead of riding
    # the full GC death spiral to the natural end. This makes ./test.sh and
    # CI fast and deterministic. Educational runs (./run.sh) never set this,
    # so readers still see the ordinary OutOfMemoryError and its stack trace.
    _flags="$_flags -XX:+ExitOnOutOfMemoryError"
  fi
  printf '%s' "$_flags"
}
