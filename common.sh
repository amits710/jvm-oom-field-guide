# Shared JVM flags for the JVM Heap OOM Field Guide.
#
# Broken and fixed versions of every scenario run under IDENTICAL flags:
# same initial and max heap, same collector, same GC logging decorations.
# The only difference between a broken run and a fixed run is the program.
#
# Sourced by run.sh and test.sh — do not execute directly.
#
# Environment overrides:
#   OOMFG_GC=G1|ZGC|Shenandoah|Parallel   (default: Parallel)
#     NOTE: ZGC and Shenandoah reserve heap headroom for concurrent
#     operation and need a bigger heap than the default 64m to run these
#     labs usefully. If you switch collectors, also raise the heap, e.g.:
#       OOMFG_GC=ZGC OOMFG_HEAP=256m ./run.sh scenario-01-unbounded-cache broken
#   OOMFG_HEAP=64m                        (default: 64m; sets both -Xms and -Xmx)
#   OOMFG_EXTRA_FLAGS="..."               appended verbatim (used by diagnose mode)
#   OOMFG_JFR=1                           bounded JFR recording (diagnose mode)

# jvm_flags <gc-log-path>: print the shared flags for one run.
# Returns 2 (with an error on stderr) for an unsupported collector or a
# gc-log path containing whitespace: the flag list is word-split by callers,
# so spaces would silently break it, and silently falling back to Parallel GC
# on a typo (e.g. OOMFG_GC=ZGCC) would invalidate the experiment.
jvm_flags() {
  _gclog="$1"
  case "$_gclog" in
    *[[:space:]]*)
      echo "jvm_flags: gc-log path contains whitespace, refusing: $_gclog" >&2
      return 2 ;;
  esac
  case "${OOMFG_GC:-Parallel}" in
    Parallel)   _gc="-XX:+UseParallelGC" ;;
    G1)         _gc="-XX:+UseG1GC" ;;
    ZGC)        _gc="-XX:+UseZGC" ;;
    Shenandoah) _gc="-XX:+UseShenandoahGC" ;;
    *)
      echo "Unsupported OOMFG_GC: '${OOMFG_GC:-}' (expected Parallel, G1, ZGC, or Shenandoah)" >&2
      return 2 ;;
  esac
  _heap="${OOMFG_HEAP:-64m}"
  _flags="-Xms${_heap} -Xmx${_heap} ${_gc} -Xlog:gc*:file=${_gclog}:time,level,tags"
  if [ "${OOMFG_TEST:-0}" = "1" ]; then
    # Test mode only: exit on the first OutOfMemoryError instead of riding
    # the full GC death spiral to the natural end. This makes ./test.sh and
    # CI fast and deterministic. Educational runs (./run.sh) never set this,
    # so readers still see the ordinary OutOfMemoryError and its stack trace.
    _flags="$_flags -XX:+ExitOnOutOfMemoryError"
  fi
  if [ -n "${OOMFG_EXTRA_FLAGS:-}" ]; then
    _flags="$_flags $OOMFG_EXTRA_FLAGS"
  fi
  printf '%s' "$_flags"
}

# jvm_base_flags: base JVM flags without test-mode extras (for CI env recording).
jvm_base_flags() {
  _gc_flags="$(OOMFG_TEST=0 OOMFG_EXTRA_FLAGS= jvm_flags /dev/null)"
  # Strip the gc-log path (it's /dev/null here, not meaningful for recording).
  printf '%s' "$_gc_flags"
}

# jvm_extra_flags: the additional flags applied in test mode (for CI env recording).
jvm_extra_flags() {
  if [ "${OOMFG_TEST:-0}" = "1" ]; then
    printf '%s' "-XX:+ExitOnOutOfMemoryError"
    if [ -n "${OOMFG_EXTRA_FLAGS:-}" ]; then
      printf ' %s' "$OOMFG_EXTRA_FLAGS"
    fi
  fi
}
