# JVM OOM Field Guide for Data Engineers

`OutOfMemoryError` is the 3 AM page nobody wants. This repo is a field guide to the
ways JVM-based data pipelines actually run out of memory in production — each one
as a small, runnable reproduction you can trigger on your laptop, diagnose like
you would at 3 AM, and then fix.

## Who this is for

Data engineers running JVM pipelines (Spark, Flink, Kafka Streams, or plain Java
services chewing through records). You don't need to be a GC tuning expert — you
need to recognize the failure signatures fast and know which lever to pull.

## How each scenario is organized

Every scenario follows the same three steps — **reproduce, diagnose, fix**:

```
scenario-NN-<name>/
  broken/               # minimal program that reliably OOMs
    <Name>.java
    run.sh              # compiles and runs it with a small heap
  diagnosis.md          # GC log signature, heap-dump clues, triage checklist
  fixed/                # the corrected version, same workload, no OOM
    <Name>Fixed.java
    run.sh
```

The discipline is deliberate: you should be able to trigger the failure, read
`diagnosis.md`, and confirm the fix — all in about fifteen minutes per scenario.

## Running a scenario

All examples are pure Java with zero dependencies. Any JDK 11+ works:

```sh
cd scenario-01-unbounded-cache/broken
./run.sh        # watch it die with OutOfMemoryError
cd ../fixed
./run.sh        # watch the same workload survive
```

## Verifying the whole repo

```sh
./test.sh         # all 5 broken scenarios must OOM, all 5 fixed must complete
```

## Roadmap

- [x] **01 — Unbounded in-memory cache** (this repo starts here)
- [x] **02 — Oversized batch accumulation** (one unit of work bigger than the heap)
- [x] **03 — Report-builder retention** (accumulating output, not input)
- [x] **04 — ThreadLocal leaks in pooled threads**
- [x] **05 — Leaked connections / unclosed result sets**

## A note on the examples

Every example here is synthetic, written from scratch for teaching. They model
failure *patterns* seen in real pipelines — the shapes are real, the code is not
taken from any production system or employer.
