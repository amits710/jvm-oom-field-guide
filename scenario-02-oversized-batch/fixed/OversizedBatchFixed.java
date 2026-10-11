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
                case "--batches":       batches      = Integer.parseInt(args[++i]); break;
                case "--batch-size":    batchSize    = Integer.parseInt(args[++i]); break;
                case "--payload-bytes": payloadBytes = Integer.parseInt(args[++i]); break;
                case "--chunk-size":    chunkSize    = Integer.parseInt(args[++i]); break;
                default: throw new IllegalArgumentException("unknown arg: " + args[i]);
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
}
