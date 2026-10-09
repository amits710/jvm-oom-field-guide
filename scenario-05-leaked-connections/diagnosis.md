# Scenario 05 — Diagnosis: the leaked connection

## The GC log signature

Run the broken version (`./run.sh` enables GC logging to `gc.log`) and you'll
see a staircase — a rising post-GC floor across consecutive collections,
ending at capacity:

```
[0.105s][info][gc] GC(0) Pause Young (Allocation Failure) 15M->15M(61M) 19.193ms
[0.168s][info][gc] GC(1) Pause Young (Allocation Failure) 30M->30M(61M) 15.350ms
[0.196s][info][gc] GC(2) Pause Full (Ergonomics) 46M->46M(61M) 24.071ms
[0.213s][info][gc] GC(3) Pause Full (Ergonomics) 58M->58M(61M) 10.749ms
[0.236s][info][gc] GC(5) Pause Full (Allocation Failure) 58M->58M(61M) 20.017ms
Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
```

Read it like this:

- The post-GC floor climbs: **15M → 30M → 46M → 58M → 58M**. Each collection
  reclaims nothing — every byte on the heap is live, held by connections
  that will never be closed.
- The program printed `opened 200 connections` before dying at 232. The heap
  holds ~232 × 256 KB ≈ 59 MB of connection buffers — the arithmetic of the
  leak is right there in the occupancy.
- This *looks* like scenario 01's signature (both are retention staircases).
  The log alone can't tell them apart — the heap dump can.

## Staircase vs. staircase: 01 vs. 05

Scenarios 01 and 05 share a GC signature but not a root cause, and the fixes
are different. This is the pair worth learning to distinguish:

| | Scenario 01 (unbounded cache) | Scenario 05 (leaked connection) |
|---|---|---|
| What's retained | Data the program *chose* to keep | Resources the program *forgot* to release |
| GC root | A cache / registry with no eviction | A registry nothing ever removes from |
| Histogram tell | `byte[]` / `String` counts track input | `Connection` count tracks work done, 1:1 |
| Fix | Eviction policy (bound the retention) | Close the resource (fix the lifecycle) |

If the histogram's fastest-growing class is a *resource* (connection,
stream, handle) rather than a *payload* (`byte[]`, `String`, your domain
objects), suspect the lifecycle, not the cache.

## Heap-dump clues

Take a heap dump on OOM (`-XX:+HeapDumpOnOutOfMemoryError`) and open it in
Eclipse MAT or JDK Mission Control:

- **Histogram:** `LeakedConnections$Connection` instances number in the
  hundreds, each retaining ~256 KB — and the count matches the units of work
  the program performed. A 1:1 ratio between a resource class and work done
  is the leak's fingerprint.
- **Dominator tree:** each `Connection` retains its `byte[]` buffer; together
  they dominate the heap. No single object is suspicious — the *count* is.
- **Path to GC roots:** right-click a `Connection` → *Path to GC Roots* →
  *exclude weak/soft references*. The path runs through the **static
  `openConnections` set**. A static registry that only ever grows is the
  classic shape of a resource leak: something added every resource "for
  tracking" and never removed.
- **Shallow vs. retained:** each connection is small (object header + two
  fields); its *retained* heap is the 256 KB buffer. Sort by retained, not
  shallow — the leak hides behind a small object holding a big one.

## How to recognize it in a real pipeline

You won't have a loop printing "opened N connections" in production. What you
will have:

1. **OOMs that correlate with request/connection count, not time.** The
   service dies after N requests, restarts, dies again after N requests.
   Graph heap against request count — a leak's clock is measured in work,
   not wall time.
2. **Resource-exhaustion warnings nearby.** Connection-pool exhaustion,
   "too many open files," socket timeouts — the JVM OOM is often the *second*
   symptom. The pool complaining first is the tell that resources, not data,
   are the problem.
3. **The staircase again — but slower.** In production the leak is usually
   one connection per request, so the floor rises over hours, not 0.2
   seconds. Same shape, different timescale. Graph post-GC old-gen occupancy
   against request count and the 1:1 ratio shows up.
4. **Restarts "fix" it temporarily.** Like scenario 01, the clock resets on
   restart — but unlike 01, the fix is not a bigger heap or an eviction
   policy. More heap just buys more leaked connections.

## The 2-minute triage checklist

When a pipeline OOMs and you suspect a resource leak:

- [ ] **Does heap track work, not time?** OOM after N requests/jobs,
      reproducible per unit of work, points at per-work resource retention.
- [ ] **Heap dump → histogram → is the fastest-growing class a resource?**
      Connections, streams, channels, handles — not payloads. Count should
      roughly equal work done.
- [ ] **Path to GC roots → static registry?** A set/map/list that only grows,
      holding resources "for tracking," is the leak's hiding place.
- [ ] **Any pool/file/socket warnings in the logs?** Resource exhaustion
      before the OOM corroborates the lifecycle story.
- [ ] **Fix = close the resource, then drop the reference.** try-with-resources
      (or close in finally) *plus* deregistration from any tracking
      collection. Closing without deregistering still leaks. Then verify the
      open-count metric goes flat under load before shipping.

## The fix (what `fixed/` demonstrates)

`Connection` implements `AutoCloseable`, each one is opened in
try-with-resources, and `close()` removes it from the tracking set. Live
memory stays at ~one buffer regardless of how many connections are processed.
In production, the same idea wears different clothes — a connection pool with
leak detection, try-with-resources around every JDBC handle, file-stream
discipline in ETL code — but the principle is identical: **every acquisition
needs a release on every path, and the release must drop the bookkeeping
reference too.**
