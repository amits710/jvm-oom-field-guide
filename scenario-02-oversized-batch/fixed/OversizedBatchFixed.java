import java.util.ArrayList;
import java.util.List;

/**
 * SCENARIO 02 (fixed): the oversized batch, chunked.
 *
 * Identical workload to the broken version — same --batches, same
 * --batch-size, same --payload-bytes — but each batch is read and processed
 * in bounded chunks (--chunk-size). A chunk's worth of payloads fits
 * comfortably under the 64 MB heap, and each chunk becomes garbage before
 * the next one is read, so all batches complete.
 *
 * Note the scoping discipline: each chunk's list is local to processChunk(),
 * so it becomes unreachable the moment the method returns. No explicit
 * nulling of locals — prefer narrow scopes and helper methods over assigning
 * locals to null; nulling is occasionally useful in unusually long methods,
 * but it should not be taught as the general memory-management pattern.
 *
 * The principle: size the unit of work for the heap, not for the input.
 * In production this is a fetch size / chunk size / page size config, a
 * streaming cursor instead of "SELECT *", or a spill-to-disk sort — anything
 * that bounds live memory independent of partition size.
 *
 *   ./run.sh [--batches 3] [--batch-size 250000] [--payload-bytes 1024] [--chunk-size 5000]
 */
public class OversizedBatchFixed {

    public static void main(String[] args) {
        int batches = 3;
        int batchSize = 250_000;
        int payloadBytes = 1024;
        int chunkSize = 5_000;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--batches":
                    batches = positiveInt(args, i, "--batches");
                    i++;
                    break;
                case "--batch-size":
                    batchSize = positiveInt(args, i, "--batch-size");
                    i++;
                    break;
                case "--payload-bytes":
                    payloadBytes = positiveInt(args, i, "--payload-bytes");
                    i++;
                    break;
                case "--chunk-size":
                    chunkSize = positiveInt(args, i, "--chunk-size");
                    i++;
                    break;
                default:
                    usage("unknown argument: " + args[i]);
            }
        }
        System.out.println("STARTED scenario-02-oversized-batch(fixed) batches=" + batches
                + " batchSize=" + batchSize + " payloadBytes=" + payloadBytes
                + " chunkSize=" + chunkSize);

        for (int batch = 1; batch <= batches; batch++) {
            long checksum = processBatch(batchSize, chunkSize, payloadBytes);
            System.out.println("batch " + batch + " done, checksum=" + checksum);
        }
        System.out.println("COMPLETED batches=" + batches
                + " recordsPerBatch=" + batchSize
                + " payloadBytes=" + payloadBytes
                + " totalRecords=" + ((long) batches * batchSize));
    }

    private static long processBatch(int batchSize, int chunkSize, int payloadBytes) {
        long checksum = 0;
        int remaining = batchSize;
        while (remaining > 0) {
            int n = Math.min(chunkSize, remaining);
            checksum += processChunk(n, payloadBytes);
            remaining -= n;
        }
        return checksum;
    }

    /**
     * Reads and processes one bounded chunk. The chunk list is method-local:
     * it becomes eligible for collection as soon as this method returns.
     */
    private static long processChunk(int n, int payloadBytes) {
        List<byte[]> chunk = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            chunk.add(new byte[payloadBytes]);
        }
        long sum = 0;
        for (byte[] r : chunk) {
            sum += r.length;
        }
        return sum;
    }

    // ---------- CLI validation: bad input exits 2 with usage on stderr ----------
    // Note: --chunk-size is validated positive here, which also fixes the
    // infinite loop that --chunk-size 0 caused (while (remaining > 0) with
    // n = min(0, remaining) = 0 never terminates).

    private static void usage(String problem) {
        System.err.println("error: " + problem);
        System.err.println("usage: OversizedBatchFixed [--batches N] [--batch-size N] [--payload-bytes N] [--chunk-size N]");
        System.err.println("  N must be a positive integer");
        System.exit(2);
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
