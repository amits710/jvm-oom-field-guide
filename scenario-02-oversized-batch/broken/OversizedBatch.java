import java.util.ArrayList;
import java.util.List;

/**
 * SCENARIO 02 (broken): the oversized batch.
 *
 * Models a pipeline stage that reads a whole partition into memory before
 * processing a single record: "read everything, then compute." The batch size
 * is a function of the input partition, not of available memory — so the first
 * partition that outgrows the heap kills the JVM.
 *
 * This is NOT a leak. Nothing here accumulates across batches; each batch is
 * released after processing. The failure is a single unit of work that is
 * bigger than the heap. In production this hides behind partition skew (one
 * giant partition), unbounded "LIMIT 1000000" reads, or a batch size tuned
 * for yesterday's data volume.
 *
 * Compare with scenario 01: there the heap dies on a slow staircase (rising
 * post-GC floor across the whole run). Here it dies on a cliff — one steep
 * climb inside a single batch, then OutOfMemoryError.
 *
 * Run with a small heap and watch it die during the FIRST batch:
 *   javac OversizedBatch.java && java -Xmx64m OversizedBatch
 */
public class OversizedBatch {

    // Records per batch. Sized for the input partition, not for the heap.
    // 250K records x ~1 KB each ~= 250 MB live — far past a 64 MB heap.
    private static final int BATCH_SIZE = 250_000;
    private static final int PAYLOAD_BYTES = 1024;

    public static void main(String[] args) {
        int batch = 0;
        try {
            while (true) {
                batch++;
                System.out.println("reading batch " + batch + " (" + BATCH_SIZE + " records)...");
                List<byte[]> records = new ArrayList<>(BATCH_SIZE);
                for (int i = 0; i < BATCH_SIZE; i++) {
                    records.add(new byte[PAYLOAD_BYTES]); // one record's payload
                }
                // "Process" the batch, then release it before the next one.
                long checksum = 0;
                for (byte[] r : records) {
                    checksum += r.length;
                }
                System.out.println("batch " + batch + " done, checksum=" + checksum);
                records = null; // batch is garbage now — the NEXT batch is the problem, not this one
            }
        } catch (OutOfMemoryError e) {
            System.err.println("OutOfMemoryError while reading batch " + batch + ".");
            throw e; // rethrow: the stack trace is part of the lesson
        }
    }
}
