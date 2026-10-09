import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SCENARIO 01 (fixed): the same workload, with a bounded cache.
 *
 * The only change from the broken version: the map now evicts. Here we use a
 * LinkedHashMap in access order with removeEldestEntry overridden, which gives
 * simple LRU behavior with zero dependencies.
 *
 * In production, prefer a real cache library (Caffeine is the modern default;
 * Guava's CacheBuilder is the classic). They give you size bounds, TTL /
 * TTI expiration, async eviction, and stats — this hand-rolled version is just
 * to prove the point with plain javac/java.
 *
 * Run the identical workload under the identical heap and watch it survive:
 *   javac UnboundedCacheFixed.java && java -Xmx64m UnboundedCacheFixed
 */
public class UnboundedCacheFixed {

    private static final int MAX_ENTRIES = 1_000;

    // Access-order LinkedHashMap + removeEldestEntry = poor man's LRU cache.
    private static final Map<String, byte[]> CACHE =
            new LinkedHashMap<String, byte[]>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    public static void main(String[] args) {
        final long total = 200_000; // far more records than the broken version survived
        for (long i = 1; i <= total; i++) {
            String key = "record-" + UUID.randomUUID();
            CACHE.put(key, new byte[4096]);
            if (i % 50_000 == 0) {
                System.out.println("processed " + i + " records, cache size: " + CACHE.size());
            }
        }
        System.out.println("Done. Processed " + total + " records; cache held at "
                + CACHE.size() + " entries (bounded at " + MAX_ENTRIES + "). No OOM.");
    }
}
