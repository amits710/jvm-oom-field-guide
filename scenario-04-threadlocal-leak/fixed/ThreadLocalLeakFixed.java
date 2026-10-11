import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SCENARIO 04 (fixed): growing ThreadLocal state, cleaned up per task.
 *
 * Identical workload to the broken version — same --pool-size, same --tasks,
 * same --payload-bytes — but each task cleans up after itself in a finally
 * block: BUFFER.remove(). The thread keeps its slot in the pool; the
 * per-task garbage is eligible for collection the moment the task ends.
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
 *   ./run.sh [--pool-size 4] [--tasks 3000] [--payload-bytes 32768]
 */
public class ThreadLocalLeakFixed {

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
        System.out.println("STARTED scenario-04-threadlocal-leak(fixed) poolSize=" + poolSize
                + " tasks=" + tasks + " payloadBytes=" + payloadBytes);

        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            for (int i = 0; i < tasks; i++) {
                final int bytes = payloadBytes;
                pool.submit(() -> {
                    try {
                        BUFFER.get().add(new byte[bytes]);
                        // ... task does its work with the buffer ...
                    } finally {
                        BUFFER.remove(); // the task ends; its garbage may go
                    }
                }).get();
                if ((i + 1) % 500 == 0) {
                    System.out.println("completed " + (i + 1) + " tasks...");
                }
            }
            System.out.println("COMPLETED tasks=" + tasks
                    + " poolSize=" + poolSize + " payloadBytes=" + payloadBytes);
        } finally {
            pool.shutdownNow();
        }
    }
}
