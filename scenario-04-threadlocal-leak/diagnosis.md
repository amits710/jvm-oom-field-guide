# Scenario 04 — Diagnosis: the ThreadLocal leak in pooled threads

## The GC log signature

Run the broken version (`./run.sh` enables GC logging to `gc.log`) and you'll
see a staircase — rising post-GC floor, full GCs reclaiming nothing, then
death:

```
[0.497s][info][gc] GC(62) Pause Full (Allocation Failure) 58M->58M(61M) 10.832ms
[0.509s][info][gc] GC(63) Pause Full (Ergonomics) 58M->58M(61M) 11.413ms
[0.515s][info][gc] GC(64) Pause Full (Allocation Failure) 58M->58M(61M) 5.862ms
[0.527s][info][gc] GC(65) Pause Full (Ergonomics) 58M->58M(61M) 9.253ms
Exception in thread "main" java.lang.OutOfMemoryError: Java heap space
```

(On some runs you'll see `GC overhead limit exceeded` instead — HotSpot's
safeguard tripping on the same retention. Same root cause; the article
"Reading GC Logs" explains the distinction.)

Read it like this:

- `58M->58M(61M)` — the full GC reclaimed *zero* bytes. Everything on the heap
  is live, and the floor rose to get here (earlier collections sat at 46M).
  This is retention, same family as scenario 01.
- **The GC log cannot tell you this is a ThreadLocal leak.** The staircase
  says *retention*; it says nothing about *where the retention is rooted*.
  Scenario 01's staircase looks the same. The heap dump is what separates
  them — that's the whole lesson of this scenario.
- The program printed `submitted 3000 tasks...` and died draining them. Each
  task added only 32 KB — no single task, batch, or cache is oversized. The
  killer is accumulation across tasks that were supposed to be independent.

## Same staircase, different root: 01 vs. 04

| | Scenario 01 (unbounded cache) | Scenario 04 (ThreadLocal leak) |
|---|---|---|
| Post-GC floor | Rises (staircase) | Rises (staircase) — the log can't tell them apart |
| What's retained | Everything ever cached | Per-thread task garbage |
| Root path | `static` field → Map | Thread → ThreadLocalMap → Entry → List |
| Visible in | GC log + heap dump | Heap dump only |
| Fix | Eviction policy | `remove()` in `finally` (match the ThreadLocal's lifetime to the task's) |

If the heap dump's top retainers come in N similar-sized chunks — one per
pool thread — and each is rooted through a `ThreadLocalMap`, you're here,
not in scenario 01.

## Heap-dump clues

Take a heap dump on OOM (`-XX:+HeapDumpOnOutOfMemoryError`) and open it in
Eclipse MAT or JDK Mission Control:

- **Dominator tree:** not one giant collection but **four** large `ArrayList`s
  (one per pool thread), each holding hundreds of `byte[]` payloads. N
  similar-sized retainers for N pool threads is the fingerprint.
- **Path to GC roots:** right-click a list → *Path to GC Roots* → *exclude
  weak/soft references*. The path runs
  `ArrayList` ← `ThreadLocal$ThreadLocalMap$Entry` ← `ThreadLocalMap` ←
  `Thread` (the pool worker). The thread is the root: as long as the pool
  keeps the thread alive, everything the thread ever touched stays alive.
- **The weak-key trap:** `ThreadLocalMap` keys are weak references, values
  are strong. Even if the `ThreadLocal` key itself were garbage-collected,
  the *value* (your list) stays pinned until the thread dies or someone
  calls `remove()`. This is why "but I nulled the ThreadLocal!" doesn't save
  you — and why `remove()` beats `clear()`.
- **Histogram:** `byte[]` dominates, same as scenarios 01 and 02. The payload
  type never identifies the leak; the root path does.

## How to recognize it in a real pipeline

You won't have a loop printing "submitted N tasks" in production. What you
will have:

1. **"Works in tests, OOMs under sustained load."** Tests run dozens of
   requests on fresh threads; production reuses the same pool threads for
   thousands. The leak is a function of *tasks per thread*, which only
   production reaches.
2. **Heap grows with request count, not wall-clock time.** Traffic spike on
   Tuesday → OOM on Tuesday. Correlate heap growth with throughput, and the
   pool is your prime suspect.
3. **Restarts "fix" it temporarily.** Killing the JVM kills the pool threads,
   which drops every ThreadLocalMap with them. Same as scenario 01's restart
   test — but here the re-accumulation tracks the request rate.
4. **The usual disguises.** Request contexts, per-request buffers,
   `SimpleDateFormat` instances, logging MDC maps, ORM sessions — anything
   someone put in a `ThreadLocal` "temporarily" inside a task that runs on a
   pooled thread.

## The 2-minute triage checklist

When a service OOMs on a retention signature and no cache is the culprit:

- [ ] **Retention in the GC log?** Rising post-GC floor = something is being
      kept. The log says *what shape*; the dump says *where*.
- [ ] **Heap dump → dominator tree → N similar-sized retainers?** One per
      pool thread is the ThreadLocal fingerprint. A single giant collection
      points back at scenario 01.
- [ ] **Path to GC roots through `ThreadLocalMap`?** Thread → threadLocals
      → Entry → payload. That's the diagnosis, full stop.
- [ ] **Does growth track request count?** Check heap against throughput. A
      ThreadLocal leak is a per-task leak wearing a per-thread disguise.
- [ ] **Fix = `remove()` in `finally`.** Or better: stop keeping
      request-scoped data in thread-scoped storage — pass it as an argument,
      or clean it in a request filter. Then verify under sustained load, not
      a ten-request test.

## The fix (what `fixed/` demonstrates)

Each task cleans up in a `finally` block: `BUFFER.remove()`. The thread
keeps its slot in the pool; the task's garbage becomes collectable the
moment the task ends. `remove()` — not just `clear()` — is the canonical
fix: it drops the entry from the `ThreadLocalMap` entirely, so neither the
value nor a stale weak key lingers. The principle: **a ThreadLocal's
lifetime must match the task's, not the thread's.** In production this is
try/finally around every ThreadLocal use, a servlet filter or interceptor
that cleans up per request — or, best of all, not using ThreadLocal for
request-scoped data in the first place.
