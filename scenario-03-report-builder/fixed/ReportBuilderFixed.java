import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.zip.CRC32;

/**
 * SCENARIO 03 (fixed): the report-builder, spilling to disk.
 *
 * Identical workload to the broken version — same --items, same
 * --input-bytes, same --summary-bytes, and byte-for-byte identical summaries
 * (same shared SummaryCodec) — but each summary is streamed to a file
 * incrementally instead of being retained in memory. Live heap stays flat
 * regardless of how many records arrive; the file holds the report.
 *
 * This is the honest fix for an unbounded report: don't bound the report,
 * remove the need to hold it. In production this is a streaming writer, an
 * append-only file, or a database sink — anything where "the report" lives
 * somewhere cheaper than the heap.
 *
 * One real limitation, stated plainly: streaming works when the output can be
 * emitted incrementally. If the final output requires a global sort,
 * deduplication, or reordering, the production fix is chunking, external
 * sorting, spill files, or partitioned merging — not naive streaming.
 *
 * The output file is left on disk (not deleted) so you can inspect it; the
 * printed record count, byte count, and CRC32 let you verify it is complete.
 *
 *   ./run.sh [--items 300000] [--input-bytes 1024] [--summary-bytes 1024] [--output report.bin]
 */
public class ReportBuilderFixed {

    public static void main(String[] args) throws IOException {
        int items = 300_000;
        int inputBytes = 1024;
        int summaryBytes = 1024;
        String outputArg = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--items":
                    items = positiveInt(args, i, "--items");
                    i++;
                    break;
                case "--input-bytes":
                    inputBytes = positiveInt(args, i, "--input-bytes");
                    i++;
                    break;
                case "--summary-bytes":
                    summaryBytes = positiveInt(args, i, "--summary-bytes");
                    i++;
                    break;
                case "--output":
                    if (i + 1 >= args.length) {
                        usage("missing value for --output");
                    }
                    outputArg = args[++i];
                    break;
                default:
                    usage("unknown argument: " + args[i]);
            }
        }
        Path out = (outputArg != null) ? Paths.get(outputArg)
                                       : Files.createTempFile("report-", ".bin");
        System.out.println("STARTED scenario-03-report-builder(fixed) items=" + items
                + " inputBytes=" + inputBytes + " summaryBytes=" + summaryBytes
                + " output=" + out.toAbsolutePath());

        CRC32 crc = new CRC32();
        long totalBytes = 0;
        int count = 0;
        try (OutputStream w = new BufferedOutputStream(Files.newOutputStream(out))) {
            for (int i = 0; i < items; i++) {
                // Input side: bounded, same as the broken version.
                byte[] input = new byte[inputBytes];
                input[0] = (byte) (i & 0xFF);

                // Identical summary bytes to the broken version (shared
                // codec) — streamed out and released instead of retained.
                byte[] summary = SummaryCodec.makeSummary(i, input[0], summaryBytes);
                w.write(summary);
                crc.update(summary);
                totalBytes += summary.length;
                count++;
                if (count % 20_000 == 0) {
                    System.out.println("processed " + count + " records");
                }
            }
        }
        System.out.println("COMPLETED records=" + count
                + " bytes=" + totalBytes
                + " crc32=" + crc.getValue()
                + " output=" + out.toAbsolutePath());
    }

    // ---------- CLI validation: bad input exits 2 with usage on stderr ----------

    private static void usage(String problem) {
        System.err.println("error: " + problem);
        System.err.println("usage: ReportBuilderFixed [--items N] [--input-bytes N] [--summary-bytes N] [--output PATH]");
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
