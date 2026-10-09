import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SCENARIO 01 (broken): the unbounded in-memory cache.
 *
 * Models a depressingly common pipeline pattern: records are cached in a plain
 * HashMap "for performance," keyed by some record ID, with no eviction policy
 * at all. Every record the pipeline ever sees stays in the map forever.
 *
 * In production this usually hides behind a nicer name — a "lookup cache," a
 * "dedup set," a "recently seen" registry. The shape is always the same:
 * a collection whose size is a function of total input volume, not of time.
 *
 * Run with a small heap and watch old-gen climb until the JVM gives up:
 *   javac UnboundedCache.java && java -Xmx64m UnboundedCache
 */
public class UnboundedCache {

    // The cache. Note: no max size, no TTL, no eviction. Just vibes.
    private static final Map<String, byte[]> CACHE = new HashMap<>();

    public static void main(String[] args) {
        long count = 0;
        try {
            while (true) {
                // Simulate a pipeline stage caching each record's payload
                // (e.g. for dedup or lookup) as records stream through.
                String key = "record-" + UUID.randomUUID();
                CACHE.put(key, new byte[4096]); // ~4 KB payload per record
                count++;
                if (count % 2_000 == 0) {
                    System.out.println("cached " + count + " records, map size: " + CACHE.size());
                }
            }
        } catch (OutOfMemoryError e) {
            System.err.println("OutOfMemoryError after caching " + count + " records.");
            throw e; // rethrow: the stack trace is part of the lesson
        }
    }
}
