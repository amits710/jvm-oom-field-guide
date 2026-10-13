# Scenario 01: Unbounded Cache

> [Back to the main README](../README.md)

## What the GC log looks like

Run the broken version. The GC log goes to `scenario-01-unbounded-cache-broken-gc.log`. You will see full GCs that free almost nothing, over and over.

```
[0.412s][info][gc] GC(4) Pause Full (Allocation Failure) 61M->58M(64M) 38.204ms
[0.489s][info][gc] GC(5) Pause Full (Allocation Failure) 61M->60M(64M) 44.917ms
[0.571s][info][gc] GC(6) Pause Full (Allocation Failure) 62M->61M(64M) 51.330ms
Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
```

What this means:

- `61M->58M(64M)` - heap was at 61 MB, GC got it down to 58 MB, heap is 64 MB total. Only freed 3 MB. Not good.
- Full GCs back to back means the JVM is desperate. It keeps trying to free space and finding nothing to free.
- Ends with `OutOfMemoryError: Java heap space`.

Your exact lines will look different depending on JDK and OS. The shape is what matters: heap fills up, GCs do nothing, boom.

## How to spot this in real life

You will not have a nice loop in production. Here is what you actually see:

1. **Heap keeps growing after every GC.** Normally the heap goes up and down (sawtooth). Here it goes up, GC barely dents it, up again. Like stairs.
2. **GCs free less and less.** Each full GC reclaims fewer bytes than the last.
3. **Restart helps for a while.** App runs fine for hours or days after a restart, then gets slow again. That is a clue, not a diagnosis. Could be a leak, could be the heap is just too small.
4. **Things get slow before it dies.** GC pauses get longer. You see timeouts before the OOM.

## Heap dump: what to look for

Make a heap dump (`-XX:+HeapDumpOnOutOfMemoryError`) and open it in Eclipse MAT:

- **Dominator tree:** sort by retained heap. One HashMap owns most of it. It is usually right at the top.
- **Path to GC Roots:** right-click it, exclude weak references. If the path goes through a static field, that is your answer. The map lives forever and nothing removes entries.
- **Histogram:** compare to a dump from startup. The growing objects here are `byte[]` and `String` (the keys).

## Quick checklist when this happens

- [ ] Is post-GC heap trending up over time? Check your metrics.
- [ ] Do full GCs free almost nothing?
- [ ] Does a restart buy you hours or days?
- [ ] Heap dump: what is the top object in the dominator tree?
- [ ] Does that object grow with input volume? If yes, it is an unbounded cache.
- [ ] Fix it: add a max size, a TTL, or an eviction policy. Or make the heap bigger if the data is legit.

## The fix

The fixed version uses the same code but caps the map at 1,000 entries with `removeEldestEntry`. Same million records, same 64 MB heap, no OOM.

In real code, use Caffeine or Guava instead of hand-rolling. But the idea is the same: **a cache without eviction is just a leak with a better name.**
