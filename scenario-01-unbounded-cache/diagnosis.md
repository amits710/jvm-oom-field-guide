# Scenario 01 — Diagnosis: the unbounded in-memory cache

> [Back to the field guide root](../README.md)

## The GC log signature

Run the broken version (`./run.sh` enables GC logging to
`scenario-01-unbounded-cache-broken-gc.log`) and you'll see the classic
retention signature. The post-GC live-set floor continues to rise; repeated
full GCs reclaim very little, leaving the heap close to capacity.

Representative output from OpenJDK 21 with Parallel GC on Linux. Exact GC
lines and OutOfMemoryError detail messages can differ by JDK, collector, and
operating system.

```
[0.412s][info][gc] GC(4) Pause Full (Allocation Failure) 61M->58M(64M) 38.204ms
[0.489s][info][gc] GC(5) Pause Full (Allocation Failure) 61M->60M(64M) 44.917ms
[0.571s][info][gc] GC(6) Pause Full (Allocation Failure) 62M->61M(64M) 51.330ms
Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
```

Read it like this:

- `61M->58M(64M)` — heap usage went from 61 MB before GC to 58 MB after GC,
  with a reported heap capacity of 64 MB. Here the heap sits close to capacity
  and the collection reclaimed only 3 MB.
- `Pause Full (Allocation Failure)` repeating back-to-back means the collector
  is desperately trying to make room and finding almost nothing to collect.
  Judge health by the trend, not one line: post-GC occupancy should return
  to a stable baseline, allocation throughput should stay sustainable, and
  the application should not spend an increasing fraction of time collecting
  garbage. A collector that repeatedly full-GCs and briefly drops occupancy
  can still be in severe trouble.
- The run ends with `java.lang.OutOfMemoryError: Java heap space` (not
  `GC overhead limit exceeded` — that one is HotSpot's safeguard: ~98% of time
  in GC recovering <2% of heap over consecutive collections).

This pattern is strong evidence that the JVM is retaining a large amount of
live data — but retained memory is not automatically a leak. It could be an
unbounded cache, or it could be a legitimate working set that doesn't fit the
configured heap. The heap dump (below) is what separates the two.

## How to recognize it in a real pipeline

You won't have a loop printing "map size" in production. What you will have:

1. **Post-GC occupancy grows with input volume, not time.** In a healthy JVM, allocation raises heap occupancy and collections bring it
   back toward a relatively stable post-GC baseline (the sawtooth). Here the
   sawtooth becomes a staircase: occupancy rises, full GCs barely dent it, and
   it rises again. Repeated full GCs with an increasing baseline are a warning
   sign. Graph post-GC occupancy — from detailed GC logs or
   JVM metrics with old-gen visibility — and if that line trends upward across
   comparable workload intervals, something is accumulating.
2. **Full GCs free almost nothing.** If your GC logs show reclaimed bytes
   shrinking toward zero while frequency climbs, that's retention, not churn.
3. **Restarts buy time — but that is a clue, not a diagnosis.** The pipeline
   runs fine for hours or days after a restart, then degrades again. A
   repeatable post-restart growth curve suggests cumulative retention or
   unbounded state. Confirm it using the post-GC live-set trend and paths
   to GC roots — a restart alone cannot tell a leak from an undersized heap
   fed by steady input.
4. **Latency degrades before death.** As the heap fills, GC pauses lengthen.
   You'll often see timeouts and backpressure *before* the OOM — the OOM is
   just the final symptom.

## Heap-dump clues

Take a heap dump on OOM (`-XX:+HeapDumpOnOutOfMemoryError`) and open it in
Eclipse Memory Analyzer or VisualVM:

- **Dominator tree:** one `HashMap` (or the class wrapping it) retains the
  overwhelming majority of the heap. Sort by retained heap — the leak is
  usually embarrassingly near the top.
- **Path to GC roots:** right-click the suspect → *Path to GC Roots* →
  *exclude weak/soft references*. A short path through a `static` field is
  the tell: the map is reachable forever by design, and nothing ever removes
  entries.
- **Histogram:** compare with a dump from startup. Repeated histograms can
  show which object populations continue to grow. Use a heap dump or JFR
  old-object analysis to determine why those objects remain reachable — a
  histogram shows instance counts, not object age. Here the growing
  populations are `byte[]` and `String` (the keys).

## The 2-minute triage checklist

When a pipeline OOMs and you suspect accumulation:

- [ ] **Post-GC occupancy trending up?** Check metrics or detailed GC logs.
      A rising baseline across comparable workload intervals is evidence of
      accumulation — not proof of a leak. A flat baseline near capacity may
      mean a legitimate working set or an undersized heap.
- [ ] **Do full GCs reclaim ~nothing?** Repeated full GCs that free very
      little indicate most of the occupied heap is live or otherwise
      unreclaimed — retention, whatever the cause.
- [ ] **Does a restart buy hours/days?** A repeatable post-restart growth
      curve suggests cumulative retention or unbounded state — but confirm
      with the post-GC live-set trend and paths to GC roots before calling
      it a leak.
- [ ] **Heap dump → dominator tree → top retainer.** What's holding it?
      Correlate with workload volume before deciding: fix retention or add
      memory.
- [ ] **Does the retainer grow with input volume?** A map/set/list whose size
      tracks records-processed is an unbounded cache until proven otherwise.
- [ ] **Fix = bound it or size for it.** Max size, TTL/TTI expiration, or
      eviction policy for a leak; a larger heap for a legitimate working set —
      then verify the post-GC baseline goes flat in staging before shipping.

## The fix (what `fixed/` demonstrates)

A bounded cache: the same insertion-order map, but `removeEldestEntry` caps
it at 1,000 entries, so the identical million-record workload runs clean
under the same 64 MB heap. Note this is *bounded eviction*, not LRU — keys
are never revisited in this workload, so LRU behavior would be unobservable.
The hand-rolled map is also not thread-safe; production caches need
concurrency control, metrics, and usually weight-based bounds. In
production, use Caffeine (size + TTL + stats) rather than hand-rolling — but
the principle is identical: **every cache needs an eviction policy, or it's
just a leak with a nicer name.**
