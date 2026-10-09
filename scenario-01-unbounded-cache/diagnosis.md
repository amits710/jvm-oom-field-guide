# Scenario 01 — Diagnosis: the unbounded in-memory cache

## The GC log signature

Run the broken version with GC logging (`java -Xmx64m -Xlog:gc* UnboundedCache`)
and you'll see the classic leak signature. Old-gen occupancy climbs
monotonically; full GCs run more and more often and free less and less:

```
[0.412s][info][gc] GC(3) Pause Full (Allocation Failure) 61M->58M(64M) 38.204ms
[0.489s][info][gc] GC(4) Pause Full (Allocation Failure) 61M->60M(64M) 44.917ms
[0.571s][info][gc] GC(5) Pause Full (Allocation Failure) 62M->61M(64M) 51.330ms
Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
```

Read it like this:

- `61M->60M(64M)` — the arrow is *before -> after*. Each full GC reclaims a
  smaller and smaller sliver while the heap sits pinned near max.
- `Pause Full (Allocation Failure)` repeating back-to-back means the collector
  is desperately trying to make room and finding almost nothing to collect.
  Healthy pressure looks different: full GCs that actually drop occupancy.
- The run ends with `java.lang.OutOfMemoryError: Java heap space` (not
  `GC overhead limit exceeded` — that one means the JVM spent >98% of its time
  in GC; here it dies from genuine exhaustion first).

## How to recognize it in a real pipeline

You won't have a loop printing "map size" in production. What you will have:

1. **Old-gen grows with input volume, not time.** The sawtooth pattern of a
   healthy JVM (up, full GC, back down) becomes a staircase (up, full GC,
   barely down, up again). Graph old-gen occupancy after full GCs — if that
   line trends upward across hours, something is accumulating.
2. **Full GCs free almost nothing.** If your GC logs show reclaimed bytes
   shrinking toward zero while frequency climbs, that's retention, not churn.
3. **Restarts "fix" it temporarily.** The most damning symptom: the pipeline
   runs fine for hours or days after a restart, then degrades again. A leak's
   clock resets on restart; a sizing problem does not.
4. **Latency degrades before death.** As the heap fills, GC pauses lengthen.
   You'll often see timeouts and backpressure *before* the OOM — the OOM is
   just the final symptom.

## Heap-dump clues

Take a heap dump on OOM (`-XX:+HeapDumpOnOutOfMemoryError`) and open it in
Eclipse MAT or JDK Mission Control:

- **Dominator tree:** one `HashMap` (or the class wrapping it) retains the
  overwhelming majority of the heap. Sort by retained heap — the leak is
  usually embarrassingly near the top.
- **Path to GC roots:** right-click the suspect → *Path to GC Roots* →
  *exclude weak/soft references*. A short path through a `static` field is
  the tell: the map is reachable forever by design, and nothing ever removes
  entries.
- **Histogram:** compare with a dump from startup. The classes whose instance
  counts grew 100x are your leak's payload — here, `byte[]` and `String`
  (the keys).

## The 2-minute triage checklist

When a pipeline OOMs and you suspect accumulation:

- [ ] **Old-gen after full GC trending up?** Check metrics or GC logs. Up =
      leak; flat = pressure/sizing.
- [ ] **Do full GCs reclaim ~nothing?** Shrinking reclaimed bytes = retention.
- [ ] **Does a restart buy hours/days?** Yes = leak clock reset.
- [ ] **Heap dump → dominator tree → top retainer.** What's holding it?
- [ ] **Does the retainer grow with input volume?** A map/set/list whose size
      tracks records-processed is the unbounded cache until proven otherwise.
- [ ] **Fix = bound it.** Max size, TTL/TTI expiration, or eviction policy —
      then verify old-gen-after-full-GC goes flat in staging before shipping.

## The fix (what `fixed/` demonstrates)

`LinkedHashMap` in access order with `removeEldestEntry` gives LRU eviction
with no dependencies; the same 200k-record workload that killed the broken
version runs clean under the same 64 MB heap. In production, use Caffeine
(size + TTL + stats) rather than hand-rolling — but the principle is
identical: **every cache needs an eviction policy, or it's just a leak with
a nicer name.**
