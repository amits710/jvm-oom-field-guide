# Scenario 02: Oversized Batch

> [Back to the main README](../README.md)

## What the GC log looks like

This one is a cliff, not a staircase. The heap fills up in one batch and dies in under a second.

```
[0.194s][info][gc] GC(10) Pause Full (Ergonomics) 58M->58M(61M) 11.005ms
[0.203s][info][gc] GC(11) Pause Full (Ergonomics) 58M->58M(61M) 9.482ms
[0.230s][info][gc] GC(12) Pause Full (Allocation Failure) 58M->58M(61M) 23.656ms
Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
```

What this means:

- `58M->58M` - GC freed nothing. Zero. The batch is just too big.
- The whole thing takes 0.06 seconds. Compare to scenario 01 where it builds up slowly. Here there is no buildup. One batch, dead.
- It printed "reading batch 1" and never finished. Died on the first batch. That tells you the batch is too big, not that something is leaking.

## Cliff vs staircase

This is the key thing to learn. Scenarios 01 and 02 both end in OOM but the fixes are totally different:

| | 01 (cache) | 02 (batch) |
|---|---|---|
| How it dies | Slow climb over many GCs | Sudden, one batch |
| What is in memory | Everything ever seen | Just the current batch |
| Fix | Limit what you keep | Limit what you load at once |

If it dies fast from a healthy heap, the batch is too big. If it climbs slowly, something is being retained.

## Heap dump: what to look for

- **Dominator tree:** one huge ArrayList or byte array owns the heap. Same as 01 so far.
- **Path to GC Roots:** this is the difference. The list is reachable from a **local variable**, not a static field. It is the batch being processed right now. Too big, that is all.
- The path tells you about ownership. Static = lives forever (leak). Local = active work that is just too big.

## How to spot this in real life

1. **Dies on specific input, not after running a while.** Works fine for weeks, then dies on a Tuesday. Check what was different about Tuesday. Usually one huge partition or a backfill.
2. **No warning.** Heap looks healthy right up to the spike. Leaks give you hours of warning. This gives you none.
3. **Same input kills it again.** Reprocess the same data, dies in the same place. That is the batch, not a leak.
4. **One partition is the outlier.** 999 partitions are fine. One is 10x bigger.

## Quick checklist

- [ ] Did it die fast? Seconds from healthy to OOM means oversized batch.
- [ ] Does the same input kill it again at the same spot?
- [ ] Heap dump: is the big object reachable from a local variable?
- [ ] Check partition sizes. The killer is usually obvious.
- [ ] Fix it: read in chunks, set a fetch size, use a streaming parser. Test with your biggest partition, not the average one.

## The fix

Read 5,000 records at a time instead of 250,000. Each chunk gets processed and dropped. Memory stays flat no matter how big the input is.

In real code this looks like a JDBC fetch size, paginated API calls, or a streaming parser. Same idea: **make the batch fit the heap, not the input.**
