import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * SCENARIO 04 (fixed): the ThreadLocal leak, plugged.
 *
 * Same workload as the broken version — 3,000 tasks on a 4-thread pool, 32 KB
 * per task — but each task cleans up after itself in a finally block:
 * BUFFER.remove(). The thread keeps its slot in the pool; the per-task
 * garbage is eligible for collection the moment the task ends.
 *
 * remove() (not just clear()) is the canonical fix: it drops the entry from
 * the thread's ThreadLocalMap entirely, so neither the value nor a stale
 * key lingers. clear() would empty this task's list but leave the entry —
 * fine here, but remove() is the habit that survives copy-paste.
 *
 * The principle: a ThreadLocal's lifetime must match the task's, not the
 * thread's. In production this is try/finally around the ThreadLocal use, a
 * request filter that cleans up, or — better — not using ThreadLocal for
 * request-scoped data at all.
 *
 *   javac ThreadLocalLeakFixed.java && java -Xmx64m ThreadLocalLeakFixed
 */
public class ThreadLocalLeakFixed {

    private static final ThreadLocal<List<byte[]>> BUFFER =
            ThreadLocal.withInitial(ArrayList::new);

    private static final int POOL_SIZE = 4;
    private static final int TASKS = 3_000;
    private static final int PAYLOAD_BYTES = 32 * 1024; // 32 KB per task

    public static void main(String[] args) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(POOL_SIZE);
        List<Future<?>> futures = new ArrayList<>(TASKS);
        try {
            for (int i = 0; i < TASKS; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        BUFFER.get().add(new byte[PAYLOAD_BYTES]);
                        // ... task does its work with the buffer ...
                    } finally {
                        BUFFER.remove(); // the task ends; its garbage may go
                    }
                }));
                if ((i + 1) % 500 == 0) {
                    System.out.println("submitted " + (i + 1) + " tasks...");
                }
            }
            for (Future<?> f : futures) {
                f.get();
            }
            System.out.println("all " + TASKS + " tasks done, heap survived.");
        } finally {
            pool.shutdownNow();
        }
    }
}
