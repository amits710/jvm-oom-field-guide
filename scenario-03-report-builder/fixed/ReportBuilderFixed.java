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
 * (see makeSummary, duplicated from the broken version deliberately) — but
 * each summary is streamed to a file incrementally instead of being retained
 * in memory. Live heap stays flat regardless of how many records arrive; the
 * file holds the report.
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
                case "--items":         items        = Integer.parseInt(args[++i]); break;
                case "--input-bytes":   inputBytes   = Integer.parseInt(args[++i]); break;
                case "--summary-bytes": summaryBytes = Integer.parseInt(args[++i]); break;
                case "--output":        outputArg    = args[++i]; break;
                default: throw new IllegalArgumentException("unknown arg: " + args[i]);
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

                // Identical summary bytes to the broken version — streamed
                // out and released instead of retained.
                byte[] summary = makeSummary(i, summaryBytes);
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

    /**
     * Byte-for-byte identical to ReportBuilder.makeSummary. Duplicated
     * deliberately so each version is self-contained; keep the two in sync.
     */
    static byte[] makeSummary(int index, int size) {
        byte[] s = new byte[size];
        for (int j = 0; j < size; j++) {
            s[j] = (byte) ((index * 31 + j) & 0xFF);
        }
        return s;
    }
}
