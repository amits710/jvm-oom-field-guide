# Scenario 03 — Diagnosis: the report-builder retention

## The GC log signature

Run the broken version (`./run.sh` enables GC logging to `gc.log`) and you'll
see a staircase — the same rising post-GC floor as scenario 01. Young
collections reclaim less and less; full collections take over and reclaim
nothing; the JVM gives up after ~100 full GCs:

```
[0.057s][info][gc] GC(0) Pause Young (Allocation Failure) 16M->8M(61M) 4.880ms
[0.090s][info][gc] GC(2) Pause Young (Allocation Failure) 33M->26M(61M) 5.035ms
[0.128s][info][gc] GC(4) Pause Full (Ergonomics) 51M->39M(61M) 21.965ms
[0.149s][info][gc] GC(6) Pause Full (Ergonomics) 58M->53M(61M) 6.490ms
[0.172s][info][gc] GC(10) Pause Full (Ergonomics) 58M->58M(61M) 4.334ms
Exception in thread "main" java.lang.OutOfMemoryError: GC overhead limit exceeded
```

Read it like this:

- The post-GC floor climbs: **8M → 26M → 39M → 53M → 58M → 58M**. That rising
  floor is the signature of retention — each collection leaves more behind
  than the last. This is the same shape as scenario 01.
- The JVM dies here with `GC overhead limit exceeded` rather than `Java heap
  space` — HotSpot's safeguard firing because ~98% of time went to GC
  recovering <2% of heap. Same retention pattern, different final error. In
  production you will see both; the staircase is what matters, not which
  message ends it.
- The program printed `processed 40000 records` and died at 40K of 300K. The
  input side was fine — bounded chunks, released each iteration. The killer
  was the 40,000 summaries sitting in the report list.

## Staircase vs. staircase: 01 vs. 03 (and the 02 cliff)

Scenarios 01 and 03 look identical in the GC log. The difference is *what* is
being retained and *where* — and the heap dump is what separates them:

| | Scenario 01 (unbounded cache) | Scenario 02 (oversized batch) | Scenario 03 (report-builder) |
|---|---|---|---|
| Post-GC floor | **Rises** (staircase) | Flat — dies too fast | **Rises** (staircase) |
| Time to OOM | Gradual | Sudden, one batch | Gradual |
| What's live | Inputs, kept for lookup | The current batch | Outputs, kept for the report |
| Retainer shape | Map keyed for reuse | Local list, too big | Append-only list, the "product" |
| Fix | Eviction policy | Smaller unit of work | Stream the output out |

The 01-vs-03 distinction is the one that bites in practice: you fix the input
side (chunking, bounded caches, eviction — all the right things), the OOM
goes away for a while, and then it comes back. Because the accumulation moved
downstream, from what you read to what you write. If you've "already fixed
the leak" and the staircase is back, look at the output side.

## Heap-dump clues

Take a heap dump on OOM (`-XX:+HeapDumpOnOutOfMemoryError`) and open it in
Eclipse MAT or JDK Mission Control:

- **Dominator tree:** one `ArrayList` of summaries dominates — same as 01 and
  02 at this level. The dominator tree alone can't tell the three apart.
- **Path to GC roots:** this is the differentiator. The list is reachable
  through the **builder/result object** — the thing whose job is producing the
  report — not through a cache, not through a local variable. Ask: *is this
  structure the product, or is it infrastructure?* If the answer is "the
  product" (the report, the response, the export), you're in scenario 03:
  the output is unbounded, and no eviction policy makes sense because nothing
  here is disposable.
- **Histogram:** the payload classes are the *summary* types, not the input
  types. In 01 the retained objects look like inputs (cached records); here
  they look like outputs (summaries, DTOs, formatted rows). If the dominator
  is full of things your code *created* rather than things it *read*, suspect
  the builder.

## How to recognize it in a real pipeline

You won't have a loop printing "processed N records" in production. What you
will have:

1. **The OOM moved after you fixed ingest.** The classic tell: chunking,
   streaming reads, or eviction fixed the problem once — then it returned.
   The accumulation didn't go away; it relocated to the output stage.
2. **Output size tracks input volume.** The report/response/export grows with
   the number of records processed, not with time. A job that handles 10x
   input produces a 10x report — and nobody sized the heap for the report.
3. **"We'll write it at the end."** Listen for the design smell: collect all
   results in memory, then serialize / upload / return. The flush-at-the-end
   is the bug wearing a plan as a disguise.
4. **Restarts buy time, like scenario 01.** The staircase rebuilds after every
   restart. Unlike 02, the same input won't kill it instantly — it takes the
   full run to accumulate.

## The 2-minute triage checklist

When a pipeline OOMs on a staircase and the input side looks clean:

- [ ] **Is the retainer the output?** Heap dump → dominator tree → is the top
      retainer full of summaries/results/DTOs rather than cached inputs?
- [ ] **Does something get "written at the end"?** Search the code for the
      final flush/serialize/upload. Everything accumulated before that call
      is suspect.
- [ ] **Did this OOM move?** If a previous fix addressed the input side and
      the staircase came back, the accumulation relocated downstream.
- [ ] **Does output volume track input volume?** A report that grows with
      records-processed is unbounded by construction.
- [ ] **Fix = stream the output.** Write each piece out as it's produced —
      streaming writer, append-only file, database sink — so "the report"
      lives somewhere cheaper than the heap. Eviction doesn't apply: the
      report isn't a cache, it's the product.

## The fix (what `fixed/` demonstrates)

Stream each summary to a temp file via `BufferedWriter` as soon as it's
produced, instead of appending it to an in-memory list. Live heap stays flat
no matter how many records arrive; the file holds the report. In production
the same idea wears different clothes — a streaming JSON/XML writer, a
database batch insert, an S3 multipart upload — but the principle is
identical: **never hold the whole output in memory just because you haven't
written it yet.**
