# Scenario 02 — Diagnosis: the oversized batch

## The GC log signature

Run the broken version (`./run.sh` enables GC logging to `gc.log`) and you'll
see a cliff, not a staircase. The heap rockets to capacity inside a single
batch; back-to-back full GCs reclaim nothing; the JVM dies within a second:

```
[0.194s][info][gc] GC(10) Pause Full (Ergonomics) 58M->58M(61M) 11.005ms
[0.203s][info][gc] GC(11) Pause Full (Ergonomics) 58M->58M(61M) 9.482ms
[0.230s][info][gc] GC(12) Pause Full (Allocation Failure) 58M->58M(61M) 23.656ms
[0.243s][info][gc] GC(13) Pause Full (Ergonomics) 58M->58M(61M) 12.298ms
Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
```

Read it like this:

- `58M->58M(61M)` — the full GC reclaimed *zero* bytes. Everything on the heap
  is live: it's the batch being read right now.
- The whole sequence spans **~0.06 seconds** (0.194s → 0.243s). Compare with
  scenario 01, where the staircase builds over many collections across the
  run. Here there is no gradual climb — one unit of work is simply bigger
  than the heap.
- The program printed `reading batch 1` and never finished it. It died on the
  *first* batch. That alone tells you the batch is oversized, not that
  something is leaking across batches.

## Cliff vs. staircase: 01 vs. 02

These two scenarios are the pair most worth learning to distinguish, because
the fixes are different:

| | Scenario 01 (unbounded cache) | Scenario 02 (oversized batch) |
|---|---|---|
| Post-GC floor | **Rises** across the run (staircase) | Flat — dies too fast for a trend |
| Time to OOM | Gradual (many GC cycles) | Sudden (seconds, one batch) |
| What's live | Everything ever seen | Only the current batch |
| Fix | Bound the retention (eviction) | Bound the unit of work (chunking) |

If the post-GC floor is flat and the JVM dies fast, suspect the unit of work.
If the floor climbs steadily, suspect retention. Both end in
`OutOfMemoryError: Java heap space`, and the log is what tells them apart.

## Heap-dump clues

Take a heap dump on OOM (`-XX:+HeapDumpOnOutOfMemoryError`) and open it in
Eclipse MAT or JDK Mission Control:

- **Dominator tree:** one giant `ArrayList` (or `byte[][]`, or whatever holds
  the batch) dominates the heap — same as scenario 01 so far.
- **Path to GC roots:** this is the differentiator. The list is reachable from
  a **local variable** in the batch-reading method — not from a `static`
  field, not from a cache. Nothing is *retaining* it beyond its natural
  lifetime; it's just too big. A short path through a local = sizing problem.
  A short path through a static = retention problem.
- **Histogram:** one allocation site dominates (`byte[]` here). The instances
  were all created in the last fraction of a second — a leak's payload would
  show a mix of ages.

## How to recognize it in a real pipeline

You won't have a loop printing "reading batch N" in production. What you will
have:

1. **OOMs that correlate with input size, not uptime.** The pipeline runs fine
   for weeks, then dies on a Tuesday. Check what was different about Tuesday's
   input — a skewed partition, a backfill, a customer 10x bigger than the rest.
2. **No rising baseline before death.** Metrics show heap healthy right up to
   the spike. Retention problems telegraph themselves for hours; sizing
   problems don't.
3. **Restarts don't help.** Unlike scenario 01, restarting buys you nothing —
   the same partition kills the JVM again on reprocessing. If the failure
   reproduces deterministically on the same input, it's the unit of work.
4. **One partition is always the culprit.** Skew is the production disguise:
   999 partitions fit in memory, one doesn't.

## The 2-minute triage checklist

When a pipeline OOMs and you suspect the batch, not a leak:

- [ ] **Did it die fast?** Seconds/minutes from a healthy heap to OOM suggests
      one oversized unit of work, not gradual retention.
- [ ] **Does the same input kill it again?** Deterministic re-failure on the
      same partition = sizing. A leak would take hours to rebuild.
- [ ] **Heap dump → dominator tree → top retainer reachable from a local?**
      Local variable = the batch itself is too big. Static field = go read
      scenario 01.
- [ ] **Is there a skew suspect?** Check partition sizes for the failed run.
      The killer partition is usually an obvious outlier.
- [ ] **Fix = bound the unit of work.** Chunked reads, fetch-size / page-size
      limits, streaming cursors, spill-to-disk sorts — anything that caps live
      memory independent of input size. Then verify with the largest partition
      you can find, not the average one.

## The fix (what `fixed/` demonstrates)

Read the batch in bounded chunks (5,000 records here) instead of all 250,000
at once. Each chunk is processed and released before the next is read, so live
memory stays flat regardless of partition size. In production, the same idea
wears different clothes — a JDBC fetch size, a paginated API, a streaming
parser — but the principle is identical: **size the unit of work for the
heap, not for the input.**
