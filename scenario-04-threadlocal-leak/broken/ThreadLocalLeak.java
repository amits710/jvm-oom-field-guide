import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * SCENARIO 04 (broken): the ThreadLocal leak in pooled threads.
 *
 * Models a depressingly common service pattern: a fixed thread pool where
 * each task stashes a payload in a per-thread buffer held in a static
 * ThreadLocal — request context, a scratch buffer, a reused formatter's
 * working set — and never clears it. The buffer was meant to be
 * request-scoped, but the pool reuses threads, so each thread's list grows
 * with every task it executes. Unbounded retention, rooted in thread
 * lifecycle rather than in any collection you can see.
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
 * Run with a small heap and watch the pool threads eat it task by task:
 *   javac ThreadLocalLeak.java && java -Xmx64m ThreadLocalLeak
 */
public class ThreadLocalLeak {

    // The buffer. Note: populated per task, never cleared, never removed.
    // Each pool thread gets its own list — and keeps it forever.
    private static final ThreadLocal<List<byte[]>> BUFFER =
            ThreadLocal.withInitial(ArrayList::new);

    private static final int POOL_SIZE = 4;
    private static final int TASKS = 3_000;
    private static final int PAYLOAD_BYTES = 32 * 1024; // 32 KB per task

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
        List<Future<?>> futures = new ArrayList<>(TASKS);
        int submitted = 0;
        try {
            for (int i = 0; i < TASKS; i++) {
                futures.add(pool.submit(() -> {
                    // Simulate per-task buffering. In production this is the
                    // request context, the batch scratch space, the thing
                    // someone put in a ThreadLocal "temporarily."
                    BUFFER.get().add(new byte[PAYLOAD_BYTES]);
                }));
                submitted++;
                if (submitted % 200 == 0) {
                    System.out.println("submitted " + submitted + " tasks...");
                }
            }
            for (Future<?> f : futures) {
                f.get(); // surfaces a worker thread's OOM as ExecutionException
            }
            System.out.println("all " + TASKS + " tasks done — heap survived?!");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof OutOfMemoryError) {
                System.err.println("OutOfMemoryError after " + submitted + " tasks submitted.");
                throw (OutOfMemoryError) cause; // rethrow: the stack trace is part of the lesson
            }
            throw new RuntimeException(cause);
        } catch (OutOfMemoryError e) {
            System.err.println("OutOfMemoryError after " + submitted + " tasks submitted.");
            throw e; // rethrow: the stack trace is part of the lesson
        } finally {
            pool.shutdownNow();
        }
    }
}
