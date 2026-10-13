# Scenario 04: ThreadLocal Leak

> [Back to the main README](../README.md)

## What the GC log looks like

Same staircase as 01. The GC log cannot tell this apart from a cache leak. You need the heap dump.

```
[0.497s][info][gc] GC(62) Pause Full (Allocation Failure) 58M->58M(61M) 10.832ms
[0.509s][info][gc] GC(63) Pause Full (Ergonomics) 58M->58M(61M) 11.413ms
Exception in thread "main" java.util.concurrent.ExecutionException:
    java.lang.OutOfMemoryError: Java heap space
Caused by: java.lang.OutOfMemoryError: Java heap space
```

Note the `ExecutionException`. The OOM happened on a worker thread, not main. Check the "Caused by" line for the real error.

Each task only used 32 KB. No single task was too big. The problem is that 3,000 tasks each left 32 KB behind on their thread.

## How this is different from 01

| | 01 (cache) | 04 (ThreadLocal) |
|---|---|---|
| What the GC log shows | Staircase | Staircase (same) |
| What is held | One big map | N smaller lists, one per thread |
| Root | Static field | Thread -> ThreadLocalMap |
| Fix | Evict entries | Call `remove()` |

The GC log looks identical. Only the heap dump tells them apart.

## Heap dump: what to look for

- **Dominator tree:** not one big object, but **four** large ArrayLists (one per pool thread). N similar-sized chunks for N threads is the fingerprint.
- **Path to GC Roots:** the path goes `ArrayList` <- `ThreadLocalMap$Entry` <- `ThreadLocalMap` <- `Thread`. The thread is keeping it alive. As long as the pool thread lives, the data stays.
- **Why nulling does not help:** ThreadLocalMap keys are weak refs but values are strong. If you lose the key, you cannot call `remove()` anymore. Always call `remove()` in a `finally` block while you still have the reference.

## How to spot this in real life

1. **Works in tests, dies in production.** Tests use fresh threads. Production reuses pool threads for thousands of requests. The leak only shows up under sustained load.
2. **Heap grows with request count.** More traffic = faster OOM. Check heap against throughput.
3. **Restart helps temporarily.** Killing the JVM kills the threads, which drops the ThreadLocals. Same as 01, but the re-growth tracks requests, not time.
4. **Common culprits:** request contexts, date formatters, logging MDC, anything someone stuck in a ThreadLocal "just for this request."

## Quick checklist

- [ ] GC log shows retention (rising post-GC floor)?
- [ ] Heap dump: N similar-sized objects, one per thread?
- [ ] Path to GC roots goes through ThreadLocalMap?
- [ ] Does heap growth track request count?
- [ ] Fix it: `remove()` in `finally`. Or stop using ThreadLocal for request data.

## The fix

```java
try {
    context.set(new ArrayList<>());
    // do work
} finally {
    context.remove();  // This line. Always.
}
```

`remove()`, not `clear()`. Remove drops the whole entry. Clear leaves the entry there.

Better yet: do not put request data in a ThreadLocal at all. Pass it as a parameter.
