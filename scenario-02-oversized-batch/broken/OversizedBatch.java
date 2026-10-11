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
                default:
                    usage("unknown argument: " + args[i]);
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

    // ---------- CLI validation: bad input exits 2 with usage on stderr ----------

    private static void usage(String problem) {
        System.err.println("error: " + problem);
        System.err.println("usage: OversizedBatch [--batches N] [--batch-size N] [--payload-bytes N]");
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
