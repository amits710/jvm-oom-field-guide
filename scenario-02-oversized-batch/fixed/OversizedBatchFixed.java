import java.util.ArrayList;
import java.util.List;

/**
 * SCENARIO 02 (fixed): the oversized batch, chunked.
 *
 * Same total workload as the broken version — 250K records per batch — but
 * each batch is read and processed in bounded chunks of 5,000 records. A
 * chunk's worth of payloads (~5 MB) fits comfortably under the 64 MB heap,
 * and each chunk becomes garbage before the next one is read.
 *
 * The principle: size the unit of work for the heap, not for the input.
 * In production this is a fetch size / chunk size / page size config, a
 * streaming cursor instead of "SELECT *", or a spill-to-disk sort — anything
 * that bounds live memory independent of partition size.
 *
 *   javac OversizedBatchFixed.java && java -Xmx64m OversizedBatchFixed
 */
public class OversizedBatchFixed {

    private static final int BATCH_SIZE = 250_000;   // same logical batch as broken
    private static final int CHUNK_SIZE = 5_000;     // ...but read in bounded chunks
    private static final int PAYLOAD_BYTES = 1024;
    private static final int BATCHES = 3;            // a few batches, to prove the point

    public static void main(String[] args) {
        for (int batch = 1; batch <= BATCHES; batch++) {
            long checksum = 0;
            int remaining = BATCH_SIZE;
            while (remaining > 0) {
                int n = Math.min(CHUNK_SIZE, remaining);
                List<byte[]> chunk = new ArrayList<>(n);
                for (int i = 0; i < n; i++) {
                    chunk.add(new byte[PAYLOAD_BYTES]);
                }
                for (byte[] r : chunk) {
                    checksum += r.length;
                }
                chunk = null;   // chunk is garbage before the next one is read
                remaining -= n;
            }
            System.out.println("batch " + batch + " done, checksum=" + checksum);
        }
        System.out.println("all " + BATCHES + " batches processed, heap survived.");
    }
}
