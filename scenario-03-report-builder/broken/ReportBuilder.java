import java.util.ArrayList;
import java.util.List;

/**
 * SCENARIO 03 (broken): the report-builder retention.
 *
 * Models a pipeline stage that handles its INPUT correctly — records stream
 * through in bounded chunks, nothing is cached — but accumulates its OUTPUT:
 * every record produces a per-record summary that gets appended to an
 * in-memory report "to be written at the end." The report grows with total
 * input volume, so the JVM dies long before the last record arrives.
 *
 * This is the failure that fools people who already fixed their ingest. The
 * input side is clean (scenario 02's lesson applied); the output side quietly
 * re-introduces unbounded growth. In production it hides behind names like
 * "result collector," "summary buffer," or "the list we flush when done."
 *
 * Compare with scenario 01: there a cache retains inputs across the run.
 * Here nothing is cached — the report is the product, and the product is
 * unbounded. Compare with scenario 02: there one batch exceeds the heap
 * (a cliff). Here the heap dies on a slow staircase, just like 01 — but the
 * heap dump tells a different story (see diagnosis.md).
 *
 * Run with a small heap and watch the report eat it:
 *   javac ReportBuilder.java && java -Xmx64m ReportBuilder
 */
public class ReportBuilder {

    // The report. Appended to for every record, never flushed, never cleared.
    private static final List<byte[]> REPORT = new ArrayList<>();

    private static final int TOTAL_RECORDS = 300_000;
    private static final int INPUT_BYTES = 1024;   // bounded input chunk, released each iteration
    private static final int SUMMARY_BYTES = 1024; // ~1 KB summary per record, retained forever

    public static void main(String[] args) {
        int count = 0;
        try {
            for (int i = 0; i < TOTAL_RECORDS; i++) {
                // Input side: bounded. Read a chunk, use it, drop it.
                byte[] input = new byte[INPUT_BYTES];
                input[0] = (byte) (i & 0xFF); // touch it so it isn't optimized away

                // "Process" the record into a summary...
                byte[] summary = new byte[SUMMARY_BYTES];
                summary[0] = input[0];

                // ...and keep every summary for the final report.
                REPORT.add(summary);

                input = null; // input is garbage now — the REPORT is the problem
                count++;
                if (count % 20_000 == 0) {
                    System.out.println("processed " + count + " records, report size: " + REPORT.size());
                }
            }
            System.out.println("done, report has " + REPORT.size() + " summaries.");
        } catch (OutOfMemoryError e) {
            // Note: string literal only — no concatenation. When the heap is
            // this exhausted, even building the message can throw.
            System.err.println("OutOfMemoryError: the report outgrew the heap.");
            throw e; // rethrow: the stack trace is part of the lesson
        }
    }
}
