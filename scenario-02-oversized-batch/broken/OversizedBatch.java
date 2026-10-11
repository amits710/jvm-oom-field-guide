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
 * Accepts the same workload parameters as the fixed version (--batches,
 * --batch-size, --payload-bytes). The broken version dies reading the FIRST
 * batch; the fixed version processes every batch by chunking.
 *
 *   ./run.sh [--batches 3] [--batch-size 250000] [--payload-bytes 1024]
 */
public class OversizedBatch {

    public static void main(String[] args) {
        int batches = 3;
        int batchSize = 250_000;
        int payloadBytes = 1024;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--batches":       batches      = Integer.parseInt(args[++i]); break;
                case "--batch-size":    batchSize    = Integer.parseInt(args[++i]); break;
                case "--payload-bytes": payloadBytes = Integer.parseInt(args[++i]); break;
                default: throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }
        System.out.println("STARTED scenario-02-oversized-batch batches=" + batches
                + " batchSize=" + batchSize + " payloadBytes=" + payloadBytes
                + " (expect OutOfMemoryError)");

        // No try/catch around the OutOfMemoryError: the raw error and stack
        // trace are the lesson. Allocating a diagnostic message on an
        // exhausted heap can itself throw.
        for (int batch = 1; batch <= batches; batch++) {
            System.out.println("reading batch " + batch + " (" + batchSize + " records)...");
            List<byte[]> records = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++) {
                records.add(new byte[payloadBytes]); // one record's payload
            }
            // "Process" the batch, then release it before the next one.
            long checksum = 0;
            for (byte[] r : records) {
                checksum += r.length;
            }
            System.out.println("batch " + batch + " done, checksum=" + checksum);
            records = null; // batch is garbage now — the NEXT batch is the problem, not this one
        }
        System.out.println("finished all " + batches + " batches without OOM"
                + " (unexpected for the broken version — was the heap enlarged?)");
    }
}
