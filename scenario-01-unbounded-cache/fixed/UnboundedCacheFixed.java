import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SCENARIO 01 (fixed): the same workload, with a bounded cache.
 *
 * Identical workload to the broken version — same --items, same
 * --payload-bytes, same deterministic sequential keys — but the map now
 * evicts: it never holds more than MAX_ENTRIES records, so the same million
 * records run clean under the same 64 MB heap that kills the broken version.
 *
 * This is a *bounded* cache, not an LRU cache: keys are never revisited in
 * this workload, so access-order/LRU behavior would be unobservable here.
 * Insertion order + removeEldestEntry is the honest description.
 *
 * Two caveats carried over from the lesson, stated plainly:
 * - This hand-rolled map is NOT thread-safe. Production caches need
 *   concurrency control, eviction metrics, hit-rate tracking, and usually
 *   size- or weight-based (not entry-count) bounds.
 * - In production, prefer a real cache library (Caffeine is the modern
 *   default; Guava's CacheBuilder is the classic): size bounds, TTL/TTI
 *   expiration, async eviction, stats. This is just the principle with
 *   plain javac/java.
 *
 *   ./run.sh [--items 1000000] [--payload-bytes 4096]
 */
public class UnboundedCacheFixed {

    private static final int MAX_ENTRIES = 1_000;

    // Insertion-order LinkedHashMap + removeEldestEntry = bounded cache,
    // zero dependencies.
    private static final Map<String, byte[]> CACHE =
            new LinkedHashMap<String, byte[]>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
                    return size() > MAX_ENTRIES;
                }
            };

    public static void main(String[] args) {
        long items = 1_000_000;
        int payloadBytes = 4096;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--items":         items        = Long.parseLong(args[++i]); break;
                case "--payload-bytes": payloadBytes = Integer.parseInt(args[++i]); break;
                default: throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }
        System.out.println("STARTED scenario-01-unbounded-cache(fixed) items=" + items
                + " payloadBytes=" + payloadBytes + " maxEntries=" + MAX_ENTRIES);

        long checksum = 0;
        for (long i = 0; i < items; i++) {
            String key = "record-" + i;
            byte[] payload = new byte[payloadBytes];
            payload[0] = (byte) (i & 0xFF);
            CACHE.put(key, payload);
            checksum += payload[0] & 0xFF; // proves every record was really processed
            if ((i + 1) % 250_000 == 0) {
                System.out.println("processed " + (i + 1) + " records, cache size: " + CACHE.size());
            }
        }
        System.out.println("COMPLETED records=" + items
                + " bytes=" + (items * payloadBytes)
                + " checksum=" + checksum
                + " cacheSize=" + CACHE.size() + " (bounded at " + MAX_ENTRIES + ")");
    }
}
