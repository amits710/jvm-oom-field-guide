import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * SCENARIO 03 (fixed): the report-builder, spilling to disk.
 *
 * Same workload as the broken version — 300,000 records, one summary each —
 * but the report is streamed to a temp file incrementally instead of being
 * held in memory. Each summary is written out and released as soon as it's
 * produced, so live heap stays flat regardless of how many records arrive.
 *
 * This is the honest fix for an unbounded report: don't bound the report,
 * remove the need to hold it. In production this is a streaming writer, an
 * append-only file, or a database sink — anything where "the report" lives
 * somewhere cheaper than the heap.
 *
 *   javac ReportBuilderFixed.java && java -Xmx64m ReportBuilderFixed
 */
public class ReportBuilderFixed {

    private static final int TOTAL_RECORDS = 300_000;
    private static final int INPUT_BYTES = 1024;
    private static final int SUMMARY_BYTES = 1024;

    public static void main(String[] args) throws IOException {
        Path reportFile = Files.createTempFile("report-", ".txt");
        int count = 0;
        try (BufferedWriter out = Files.newBufferedWriter(reportFile)) {
            for (int i = 0; i < TOTAL_RECORDS; i++) {
                // Input side: bounded, same as the broken version.
                byte[] input = new byte[INPUT_BYTES];
                input[0] = (byte) (i & 0xFF);

                // "Process" the record into a summary...
                byte[] summary = new byte[SUMMARY_BYTES];
                summary[0] = input[0];

                // ...and stream it out instead of retaining it.
                out.write("record-" + i + " summary-checksum=" + (summary[0] & 0xFF));
                out.newLine();

                summary = null; // summary is garbage now — the file holds the report
                input = null;
                count++;
                if (count % 20_000 == 0) {
                    System.out.println("processed " + count + " records");
                }
            }
        }
        System.out.println("done, " + count + " summaries written to " + reportFile + ", heap survived.");
        Files.deleteIfExists(reportFile);
    }
}
