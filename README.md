# JVM Heap OOM Field Guide

[![ci](https://github.com/amits710/jvm-oom-field-guide/actions/workflows/ci.yml/badge.svg)](https://github.com/amits710/jvm-oom-field-guide/actions/workflows/ci.yml)

**Five ways Java apps run out of heap memory. Each one you can reproduce on your laptop.**

OutOfMemoryError is the 3 AM page nobody wants. This repo has five small programs that each trigger a real OOM in a different way. Run them, break things, learn how to diagnose it, then see the fix.

## Try it in five minutes

> **First time?** If `./run.sh` says "permission denied", run this once:
> `chmod +x run.sh test.sh common.sh scenario-*/broken/run.sh scenario-*/fixed/run.sh`

```sh
./run.sh scenario-01-unbounded-cache broken   # watch it die
./run.sh scenario-01-unbounded-cache fixed     # watch it survive
```

Then read `scenario-01-unbounded-cache/diagnosis.md`. It walks through the GC log, the heap dump, and what to check. About 15 minutes per scenario.

## The five scenarios

| # | What it is | What goes wrong | The fix |
|---|---|---|---|
| [01](scenario-01-unbounded-cache/) | Unbounded cache | A Map that grows forever | Bounded cache with eviction |
| [02](scenario-02-oversized-batch/) | Oversized batch | One 250 MB batch on a 64 MB heap | Process in smaller chunks |
| [03](scenario-03-report-builder/) | Report builder | Output piles up in memory | Stream it to disk instead |
| [04](scenario-04-threadlocal-leak/) | ThreadLocal leak | Pooled threads hold onto old data | Call `remove()` in `finally` |
| [05](scenario-05-leaked-connections/) | Leaked connections | Unclosed resources pile up | try-with-resources |

Each one runs in under a minute.

## How it is organized

Every scenario has the same layout:

```
scenario-NN-<name>/
  broken/           # the program that OOMs
  diagnosis.md      # how to figure out what happened
  fixed/            # same thing, fixed
```

Both versions do the same work. The broken one dies. The fixed one finishes and prints a `COMPLETED` line so you can check it did everything.

All runs use the same JVM flags (`-Xms64m -Xmx64m`, Parallel GC, same GC logging). The only thing that changes is the code.

## Running things

```sh
./run.sh scenario-01-unbounded-cache broken    # break it
./run.sh scenario-01-unbounded-cache fixed     # fix it
./run.sh scenario-01-unbounded-cache compare   # both, back to back
```

Or go into a scenario folder and run `./run.sh` from there.

### Different collectors

Default is Parallel GC on 64 MB. You can change it:

```sh
OOMFG_GC=G1 ./run.sh scenario-01-unbounded-cache broken
OOMFG_GC=ZGC OOMFG_HEAP=256m ./run.sh scenario-01-unbounded-cache broken
```

ZGC and Shenandoah need bigger heaps (at least 256 MB). They do not work well at 64 MB.

### Getting a heap dump

```sh
./run.sh scenario-01-unbounded-cache diagnose
```

This runs the broken version with heap dump on OOM. The `.hprof` file goes in `generated-output/`. Open it in Eclipse MAT.

## Running all the tests

```sh
./test.sh
```

Checks that all 5 broken versions OOM and all 5 fixed versions complete. CI runs this on JDK 11, 17, 21, and 25.

## Where you see these in real life

| Scenario | What it looks like at work |
|---|---|
| 01 cache | Spark executor caches, "recently seen" sets that never clear |
| 02 batch | One giant Spark partition, `SELECT *` loaded into a list |
| 03 report builder | Building a huge report in memory instead of streaming it |
| 04 ThreadLocal | Request data stuck on pooled threads |
| 05 connections | DB connections never closed, pool runs dry |

## What is not covered yet

This is heap only. A JVM can also die from:

- Metaspace leaks (too many classes loaded)
- Direct buffer / native memory (NIO, Netty)
- Too many threads
- Container OOM-kill (Kubernetes kills the pod, exit 137, no Java error at all)

Those are on the roadmap. The title says heap, and that is what this covers.

## Stuff you should know

- **OS:** Linux and macOS. Windows is untested.
- **JDK:** 11, 17, 21, 25. The code does not use anything newer than 11.
- **Time:** each lab takes under a minute.
- **Disk:** tiny, except scenario 03's fixed version writes a ~300 MB file.

## About the code

All examples are made up for teaching. They show patterns from real systems, but the code is written from scratch. Nothing from any employer.

## License

Apache-2.0. See [LICENSE](LICENSE).
