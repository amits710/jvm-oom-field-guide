# Scenario 05: Leaked Connections

> [Back to the main README](../README.md)

## What the GC log looks like

Another staircase. Post-GC heap climbs until it dies.

```
[0.105s][info][gc] GC(0) Pause Young (Allocation Failure) 15M->15M(61M) 19.193ms
[0.168s][info][gc] GC(1) Pause Young (Allocation Failure) 30M->30M(61M) 15.350ms
[0.196s][info][gc] GC(2) Pause Full (Ergonomics) 46M->46M(61M) 24.071ms
Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
```

Post-GC numbers: 15M, 30M, 46M, 58M. Each GC frees nothing.

It printed "opened 200 connections" before dying. 200 connections times 256 KB each is about 51 MB. The math checks out.

## How this is different from 01

Same staircase in the GC log. Different root cause:

| | 01 (cache) | 05 (leaked connections) |
|---|---|---|
| What is held | Data you chose to keep | Resources you forgot to close |
| Root | A cache with no eviction | A registry nothing removes from |
| Fix | Eviction policy | Close the thing |

If the fastest-growing class in the heap dump is a *resource* (Connection, stream, socket) instead of *data* (byte[], String), you are here.

## Heap dump: what to look for

- **Histogram:** hundreds of `Connection` objects. The count matches the work done. 200 connections opened, 200 in the dump. That 1:1 ratio is the fingerprint.
- **Dominator tree:** each Connection is small, but holds a 256 KB buffer. Sort by retained heap, not shallow. The leak hides behind a small object holding a big one.
- **Path to GC Roots:** goes through a static `openConnections` set. A static collection that only grows is the classic resource leak shape. Something added every connection "for tracking" and never removed it.

## How to spot this in real life

1. **Dies after N requests.** Not after N hours. Graph heap against request count. A leak's clock is measured in work.
2. **Other warnings come first.** Pool exhaustion, "too many open files," socket timeouts. The OOM is often the second symptom. If the pool complained before the heap died, it is a resource leak.
3. **Slow staircase.** In production it is one connection per request, so the climb takes hours not seconds. Same shape, slower.
4. **Restart helps but does not fix.** More heap just means more leaked connections before it dies.

## Quick checklist

- [ ] Does heap track work done, not wall clock time?
- [ ] Heap dump: is the fastest-growing class a resource, not data?
- [ ] Does the count match the work? (200 requests = 200 connections)
- [ ] Path to GC roots: static set or list that only grows?
- [ ] Any pool or socket warnings in the logs before the OOM?
- [ ] Fix it: close in try-with-resources AND remove from the tracking set. Both.

## The fix

```java
try (Connection c = openConnection()) {
    // use it
}  // auto-closed, auto-removed from tracking
```

Two parts: close the resource, and drop it from the registry. If you close it but leave it in the set, the Java object still hangs around.

**Every resource you open needs to be closed on every path.**
