# Scenario 03: Report Builder

> [Back to the main README](../README.md)

## What the GC log looks like

Same staircase as scenario 01. Post-GC heap keeps climbing until it dies.

```
[0.057s][info][gc] GC(0) Pause Young (Allocation Failure) 16M->8M(61M) 4.880ms
[0.090s][info][gc] GC(2) Pause Young (Allocation Failure) 33M->26M(61M) 5.035ms
[0.128s][info][gc] GC(4) Pause Full (Ergonomics) 51M->39M(61M) 21.965ms
[0.172s][info][gc] GC(10) Pause Full (Ergonomics) 58M->58M(61M) 4.334ms
Exception in thread "main" java.lang.OutOfMemoryError: GC overhead limit exceeded
```

The post-GC numbers climb: 8M, 26M, 39M, 53M, 58M. Same shape as 01.

This time it dies with `GC overhead limit exceeded` instead of `Java heap space`. Different message, same problem. Do not get hung up on which error you get.

It printed "processed 40000 records" then died. The input was fine. The killer was 40,000 summaries sitting in a list, waiting to be written "at the end."

## How this is different from 01

The GC log looks the same. The heap dump looks similar. But what is being held is different:

| | 01 (cache) | 03 (report builder) |
|---|---|---|
| What is held | Inputs, saved for lookup | Outputs, waiting to be written |
| Where it lives | A cache or map | The result object |
| Fix | Evict old entries | Write it out as you go |

This is the one that gets you after you "fixed" the leak. You added eviction, you chunked the input, the OOM went away. Then it came back. Because the problem moved from what you read to what you write.

## Heap dump: what to look for

- **Dominator tree:** one big ArrayList. Same as 01 at this level.
- **Path to GC Roots:** the list is reachable through the **builder object**, not a cache. Ask yourself: is this the product or the infrastructure? If it is the report itself, you are here.
- **Histogram:** the objects are summaries and DTOs (things your code made), not input records (things your code read).

## How to spot this in real life

1. **You already fixed the input side.** Chunking and eviction helped once. Then the OOM came back. The problem moved downstream.
2. **Output grows with input.** 10x input means 10x report. Nobody sized the heap for the report.
3. **"We will write it at the end."** If the design collects everything then writes once, that is the bug.
4. **Same staircase as 01.** Slow buildup, restart helps temporarily.

## Quick checklist

- [ ] Heap dump: is the big object full of outputs (summaries, DTOs) not inputs?
- [ ] Is there a "write at the end" step? Everything before that is suspect.
- [ ] Did this OOM move here after you fixed something else?
- [ ] Fix it: stream output as you produce it. Write to disk, to a DB, to S3. Do not hold it all in memory.

## The fix

Write each summary to a file as soon as it is made. Do not put it in a list. Heap stays flat. The file holds the report.

One catch: this works when you can write incrementally. If you need to sort everything first, you need external sorting or chunked merging. But most reports can stream.

**Do not hold the whole output in memory just because you have not written it yet.**
