# JVM Heap OOM Field Guide

[![ci](https://github.com/amits710/jvm-oom-field-guide/actions/workflows/ci.yml/badge.svg)](https://github.com/amits710/jvm-oom-field-guide/actions/workflows/ci.yml)

**Five reproducible Java heap failure patterns for data pipelines and backend services.**

`OutOfMemoryError` is the 3 AM page nobody wants. This repo is a field guide
to the ways JVM-based data pipelines actually run out of *heap* memory in
production — each one as a small, runnable reproduction you can trigger on
your laptop, diagnose like you would at 3 AM, and then fix.

## Start here in five minutes

> **First clone?** GitHub API pushes don't preserve the executable bit, so
> if `./run.sh` gives "permission denied", run this once:
> `chmod +x run.sh test.sh common.sh scenario-*/broken/run.sh scenario-*/fixed/run.sh`

```sh
./run.sh scenario-01-unbounded-cache broken   # watch it die with OutOfMemoryError
./run.sh scenario-01-unbounded-cache fixed     # watch the same workload survive
```

Then read `scenario-01-unbounded-cache/diagnosis.md` for the GC-log
signature, heap-dump clues, and triage checklist. Fifteen minutes per
scenario is the intended pace.

## The five scenarios

| # | Failure pattern | Broken behavior | Fix | ~Duration |
|---|---|---|---|---|
| [01](scenario-01-unbounded-cache/) | Unbounded in-memory cache | Map grows with input volume; OOMs on a rising post-GC staircase | Bounded cache with eviction | <1 min |
| [02](scenario-02-oversized-batch/) | Oversized batch | One 250 MB batch vs. a 64 MB heap; dies on a cliff | Chunked reads sized for the heap | <1 min |
| [03](scenario-03-report-builder/) | Report-builder retention | Output accumulates in memory; staircase like 01, but the retainer is the *product* | Stream output to disk incrementally | <1 min |
| [04](scenario-04-threadlocal-leak/) | Growing ThreadLocal state retained by pooled threads | Per-thread lists grow per task; staircase invisible to the GC log alone | `remove()` in `finally` per task | <1 min |
| [05](scenario-05-leaked-connections/) | Leaked connections | Static registry pins every unclosed resource; 1:1 growth with work done | try-with-resources + deregistration | <1 min |

## How each scenario is organized

Every scenario follows the same three steps — **reproduce, diagnose, fix**:

```
scenario-NN-<name>/
  broken/               # minimal program that reliably OOMs
    <Name>.java
    run.sh              # delegates to the shared root runner
  diagnosis.md          # GC log signature, heap-dump clues, triage checklist
  fixed/                # the corrected version: same workload, no OOM
    <Name>Fixed.java
    run.sh
```

Two experimental disciplines are enforced across all five:

- **Equivalent workloads.** Broken and fixed variants share the same
  workload-defining parameters (record count, payload size, batch size). A
  fixed variant may expose an implementation or output control such as
  `--chunk-size` or `--output`; that control does not change the logical
  records processed. The broken version OOMs before finishing; the fixed
  version completes *every* item and prints a `COMPLETED` line with
  record/byte/checksum counts so you can verify it did the same work.
- **Identical JVM flags.** All runs go through the root `run.sh`, which
  applies the same `-Xms64m -Xmx64m`, the same collector (Parallel GC), and
  the same GC logging to both versions. The only variable is the program.
  Each run writes its own GC log, e.g.
  `scenario-01-unbounded-cache-broken-gc.log`.

## Running a scenario

All examples are pure Java with zero dependencies. Designed for JDK 11 and later; continuously tested on the versions listed below:

```sh
./run.sh scenario-01-unbounded-cache broken            # reproduce
./run.sh scenario-01-unbounded-cache fixed             # fix
./run.sh scenario-01-unbounded-cache compare            # broken, then fixed
./run.sh scenario-02-oversized-batch broken --batch-size 500000  # tune the workload
```

Or from inside a scenario directory: `cd scenario-01-unbounded-cache/broken && ./run.sh`.

### Switching collectors and heaps

The default is Parallel GC on a 64 MB heap. Override with environment variables:

```sh
OOMFG_GC=G1 ./run.sh scenario-01-unbounded-cache broken          # G1 instead of Parallel
OOMFG_GC=ZGC OOMFG_HEAP=256m ./run.sh scenario-01-unbounded-cache broken
```

`OOMFG_GC` accepts `Parallel` (default), `G1`, `ZGC`, or `Shenandoah`.
`OOMFG_HEAP` sets both `-Xms` and `-Xmx` (default `64m`).

> **ZGC and Shenandoah need bigger heaps.** Both reserve heap headroom for
> concurrent operation; at 64 MB they may fail in confusing ways or refuse
> to start. Use at least 256 MB when experimenting with them.

### Heap-dump diagnose mode

```sh
./run.sh scenario-01-unbounded-cache diagnose
```

Runs the broken version with `-XX:+HeapDumpOnOutOfMemoryError`; the `.hprof`
file lands in `generated-output/`. Open it in Eclipse MAT or another HPROF
analyzer. Set `OOMFG_JFR=1` for a bounded (50 MB) JFR recording alongside
the dump.

## Verifying the whole repo

```sh
./test.sh         # all 5 broken scenarios must OOM, all 5 fixed must complete
```

`test.sh` compiles each program into an isolated temp directory, validates
the environment (JDK 11+, `timeout`/`gtimeout` handling), and asserts the
right things: broken runs must exit nonzero *without* timing out and print
`OutOfMemoryError`; fixed runs must exit 0 and print their `COMPLETED`
marker with the expected counts. (It also checks per-batch checksums, the
streamed report's byte count, CLI validation (bad args exit 2), and that
scenario 03's two versions share the single `SummaryCodec` implementation.)
CI runs this on JDK 11, 17, 21, and 25, with JDK 27 as best-effort.

## A note on the terminal excerpts

GC log lines and `OutOfMemoryError` detail messages in the diagnosis guides
are **representative output** (OpenJDK 21, Parallel GC, Linux). Your exact
lines will differ by JDK version, collector, heap size, allocation pattern,
and OS — the *shapes* (rising post-GC floor, cliff vs. staircase) are what
transfer. The test suite never asserts a specific OOM message for this
reason.

## Where this maps in production

| Scenario | Production disguise |
|---|---|
| 01 unbounded cache | Spark executor in-memory caches, Kafka Streams state stores, "recently seen" dedup sets |
| 02 oversized batch | Partition skew (one giant Spark partition), unbounded JDBC `fetchSize`, `SELECT *` into a list |
| 03 report-builder retention | Batch report generation accumulating rows, oversized API responses built in memory |
| 04 ThreadLocal state | Request-scoped buffers on servlet/worker pools, logging MDC maps, per-request formatters |
| 05 leaked connections | Unclosed JDBC connections/result sets, leaked file handles in ETL, socket leaks |

## What this guide does not yet cover

This guide covers **Java heap** exhaustion only. A JVM process can also die
from:

- **Metaspace growth** (class/metadata leak)
- **Direct-buffer / native memory** exhaustion (NIO, Netty, JNI)
- **Native thread** exhaustion (`unable to create native thread`)
- **Container OOM-kill** — in Kubernetes, exceeding the container memory limit
  produces `OOMKilled` / exit 137, *not* a Java `OutOfMemoryError`. If your
  pod dies with no heap error at all, check the container limit before the
  heap. JVM native-memory investigation (`-XX:NativeMemoryTracking`,
  `jcmd VM.native_memory`) belongs here too.

These are on the roadmap. The honest scope is in the title: this is a *heap*
OOM field guide, and the five labs are the foundation, not the whole
landscape.

## Supported platforms

- **OS:** Linux (primary), macOS (CI-tested). Windows is untested —
  `test.sh` needs `timeout`/`gtimeout` or it runs steps without a per-step
  limit.
- **JDK:** 11, 17, 21, 25 (LTS release lines), 27 (current feature release, best-effort). CI matrix covers
  these; the labs avoid APIs newer than 11.
- **Duration:** each lab runs in under a minute on a laptop.
- **Disk:** negligible, except scenario 03's fixed run, which streams a
  ~300 MB report file (kept for inspection; delete it when done).

## A note on the examples

Every example here is synthetic, written from scratch for teaching. They model
failure *patterns* seen in real pipelines — the shapes are real, the code is
not taken from any production system or employer.

## License

Apache-2.0 — see [LICENSE](LICENSE). If you use this guide in your work,
please cite it (see [CITATION.cff](CITATION.cff)).
