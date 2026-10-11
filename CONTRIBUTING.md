# Contributing to the JVM Heap OOM Field Guide

Thanks for considering a contribution. This repo has one non-negotiable
standard: **every lab must be a controlled experiment.** A reader should be
able to trust that the only difference between `broken/` and `fixed/` is the
fix.

## Adding a new scenario

Create `scenario-NN-<name>/` with:

- `broken/<Name>.java` — must reliably throw `OutOfMemoryError` on a 64 MB
  heap. Let the error escape naturally: no catch-and-rethrow, no diagnostic
  string building in a catch block.
- `fixed/<Name>Fixed.java` — must accept the **same CLI parameters** as the
  broken version, complete the **entire** workload, and print a `COMPLETED`
  line with record/byte/checksum counts proving it did the same work.
- `broken/run.sh` and `fixed/run.sh` — thin wrappers delegating to the root
  `run.sh` (copy an existing pair). Never set JVM flags here; the shared
  runner in `common.sh` keeps them identical.
- `diagnosis.md` — GC-log signature (mark excerpts as representative, never
  guaranteed), heap-dump clues, a triage checklist, and the fix. Link back to
  the root README. State the precise failure (e.g. "bounded cache", not
  "LRU", unless the workload actually exercises the claimed behavior).

## Rules for edits

- **Equivalent workloads.** If you change what the broken version does, make
  the same change to the fixed version's parameters and completion
  assertions.
- **No new dependencies.** The labs compile with plain `javac`. Keep it that
  way.
- **Careful diagnostic claims.** Histograms show instance counts, not object
  age. GC-root paths are evidence about ownership, not proof of a leak type.
  A restart buying time is a clue, not a diagnosis. "Full GC drops
  occupancy" alone does not imply health.
- **JDK 11 compatibility.** No APIs newer than Java 11; CI tests 11, 17, 21,
  and 25.

## Before opening a PR

```sh
./test.sh   # must be fully green
```

If you touched a diagnosis guide, re-read it for absolute statements ("always",
"proves", "means") and soften the ones that aren't literally true.
