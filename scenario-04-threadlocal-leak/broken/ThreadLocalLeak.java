import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SCENARIO 04 (broken): growing ThreadLocal state retained by pooled threads.
 *
 * Models a depressingly common service pattern: a fixed thread pool where
 * each task stashes a payload in a per-thread buffer held in a static
 * ThreadLocal — request context, a scratch buffer, a reused formatter's
 * working set — and never clears it. The buffer was meant to be
 * request-scoped, but the pool reuses threads, so each thread's list grows
 * with every task it executes. Unbounded retention, rooted in thread
 * lifecycle rather than in any collection you can see.
 *
 * (A note on naming: the classic "stale weak key" ThreadLocalMap subtlety —
 * weak keys, strong values — is real, but it is NOT what this lab shows.
 * Here the ThreadLocal is statically reachable and every thread is alive;
 * the failure is simply per-task state outliving the task. The stale-key
 * variation is covered as an advanced note in diagnosis.md.)
 *
 * This is distinct from scenario 01 (a cache that never evicts) and 02 (one
 * batch bigger than the heap). Nothing here is cached and no single unit of
 * work is oversized: 32 KB per task is nothing. The leak is that the
 * *thread* outlives the *task*, and nobody told the ThreadLocal.
 *
 * In production this hides behind "works fine in tests, OOMs after hours
 * under load" — tests use fresh threads or few requests; production pools
 * reuse the same threads for thousands of tasks.
 *
 * One task is submitted and awaited at a time, and no Future is retained:
 * the executor queue can never become a competing OOM source, and the ONLY
 * thing growing in the heap is each worker thread's list. A worker thread's
 * OutOfMemoryError surfaces wrapped in ExecutionException — the "Caused by"
 * line is the real error.
 *
 *   ./run.sh [--pool-size 4] [--tasks 3000] [--payload-bytes 32768]
 */
public class ThreadLocalLeak {

    // The buffer. Note: populated per task, never cleared, never removed.
    // Each pool thread gets its own list — and keeps it forever.
    private static final ThreadLocal<List<byte[]>> BUFFER =
            ThreadLocal.withInitial(ArrayList::new);

    public static void main(String[] args) throws Exception {
        int poolSize = 4;
        int tasks = 3000;
        int payloadBytes = 32 * 1024; // 32 KB per task
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--pool-size":     poolSize     = Integer.parseInt(args[++i]); break;
                case "--tasks":         tasks        = Integer.parseInt(args[++i]); break;
                case "--payload-bytes": payloadBytes = Integer.parseInt(args[++i]); break;
                default: throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }
        System.out.println("STARTED scenario-04-threadlocal-leak poolSize=" + poolSize
                + " tasks=" + tasks + " payloadBytes=" + payloadBytes
                + " (expect OutOfMemoryError)");

        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            for (int i = 0; i < tasks; i++) {
                // Submit one task and wait for it. No task queue builds up,
                // no Future is retained — the per-thread lists are the only
                // retention path in this program.
                final int bytes = payloadBytes;
                pool.submit(() -> {
                    // Simulate per-task buffering. In production this is the
                    // request context, the batch scratch space, the thing
                    // someone put in a ThreadLocal "temporarily."
                    BUFFER.get().add(new byte[bytes]);
                }).get();
                if ((i + 1) % 500 == 0) {
                    System.out.println("completed " + (i + 1) + " tasks...");
                }
            }
            System.out.println("finished all " + tasks + " tasks without OOM"
                    + " (unexpected for the broken version — was the heap enlarged?)");
        } finally {
            pool.shutdownNow(); // no catch: the error propagates, the pool still shuts down
        }
    }
}
