import java.util.HashMap;
import java.util.Map;

/**
 * SCENARIO 01 (broken): the unbounded in-memory cache.
 *
 * Models a depressingly common pipeline pattern: records are cached in a plain
 * HashMap "for performance," keyed by record ID, with no eviction policy at
 * all. Every record the pipeline ever sees stays in the map forever.
 *
 * In production this usually hides behind a nicer name — a "lookup cache," a
 * "dedup set," a "recently seen" registry. The shape is always the same:
 * a collection whose size is a function of total input volume, not of time.
 *
 * The keys are deterministic sequential IDs ("record-0", "record-1", ...),
 * not UUIDs, so the only thing growing the heap is the cache itself — no
 * allocation noise from key generation.
 *
 * Run with a small heap and watch old-gen climb until the JVM gives up:
 *   ./run.sh [--items 1000000] [--payload-bytes 4096]
 *
 * Accepts the same CLI parameters as the fixed version. The broken version
 * dies with OutOfMemoryError long before reaching --items; the fixed version
 * processes every item.
 */
public class UnboundedCache {

    // The cache. Note: no max size, no TTL, no eviction. Just vibes.
    private static final Map<String, byte[]> CACHE = new HashMap<>();

    public static void main(String[] args) {
        long items = 1_000_000;
        int payloadBytes = 4096;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--items":
                    items = positiveLong(args, i, "--items");
                    i++;
                    break;
                case "--payload-bytes":
                    payloadBytes = positiveInt(args, i, "--payload-bytes");
                    i++;
                    break;
                default:
                    usage("unknown argument: " + args[i]);
            }
        }
        System.out.println("STARTED scenario-01-unbounded-cache items=" + items
                + " payloadBytes=" + payloadBytes + " (expect OutOfMemoryError)");

        // No try/catch around the OutOfMemoryError: when the heap is
        // exhausted, even building a diagnostic message can allocate and
        // throw. The raw error and stack trace are the lesson.
        for (long i = 0; i < items; i++) {
            String key = "record-" + i;
            byte[] payload = new byte[payloadBytes];
            payload[0] = (byte) (i & 0xFF); // touch it so it isn't optimized away
            CACHE.put(key, payload);
            if ((i + 1) % 5_000 == 0) {
                System.out.println("cached " + (i + 1) + " records, map size: " + CACHE.size());
            }
        }
        System.out.println("finished all " + items + " records without OOM"
                + " (unexpected for the broken version — was the heap enlarged?)");
    }

    // ---------- CLI validation: bad input exits 2 with usage on stderr ----------

    private static void usage(String problem) {
        System.err.println("error: " + problem);
        System.err.println("usage: UnboundedCache [--items N] [--payload-bytes N]");
        System.err.println("  N must be a positive integer");
        System.exit(2);
    }

    /** Parses args[i+1] as a positive long; exits 2 on missing/invalid/non-positive. */
    private static long positiveLong(String[] args, int i, String name) {
        if (i + 1 >= args.length) {
            usage("missing value for " + name);
        }
        long v;
        try {
            v = Long.parseLong(args[i + 1]);
        } catch (NumberFormatException e) {
            usage("not a number for " + name + ": " + args[i + 1]);
            throw new AssertionError("unreachable");
        }
        if (v <= 0) {
            usage(name + " must be positive, got " + v);
        }
        return v;
    }

    /** Parses args[i+1] as a positive int; exits 2 on missing/invalid/non-positive. */
    private static int positiveInt(String[] args, int i, String name) {
        if (i + 1 >= args.length) {
            usage("missing value for " + name);
        }
        int v;
        try {
            v = Integer.parseInt(args[i + 1]);
        } catch (NumberFormatException e) {
            usage("not a number for " + name + ": " + args[i + 1]);
            throw new AssertionError("unreachable");
        }
        if (v <= 0) {
            usage(name + " must be positive, got " + v);
        }
        return v;
    }
}
