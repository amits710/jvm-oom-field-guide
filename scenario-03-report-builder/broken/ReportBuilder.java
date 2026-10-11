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
 * The per-record summary bytes are deterministic (a pure function of the
 * record index) — byte-for-byte identical to what the fixed version streams
 * to disk. Same output, different retention: that is the whole experiment.
 *
 * Compare with scenario 01: there a cache retains inputs across the run.
 * Here nothing is cached — the report is the product, and the product is
 * unbounded. Compare with scenario 02: there one batch exceeds the heap
 * (a cliff). Here the heap dies on a slow staircase, just like 01 — but the
 * heap dump tells a different story (see diagnosis.md).
 *
 *   ./run.sh [--items 300000] [--input-bytes 1024] [--summary-bytes 1024]
 */
public class ReportBuilder {

    // The report. Appended to for every record, never flushed, never cleared.
    private static final List<byte[]> REPORT = new ArrayList<>();

    public static void main(String[] args) {
        int items = 300_000;
        int inputBytes = 1024;
        int summaryBytes = 1024;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--items":         items        = Integer.parseInt(args[++i]); break;
                case "--input-bytes":   inputBytes   = Integer.parseInt(args[++i]); break;
                case "--summary-bytes": summaryBytes = Integer.parseInt(args[++i]); break;
                default: throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }
        System.out.println("STARTED scenario-03-report-builder items=" + items
                + " inputBytes=" + inputBytes + " summaryBytes=" + summaryBytes
                + " (expect OutOfMemoryError)");

        // No try/catch around the OutOfMemoryError: the raw error and stack
        // trace are the lesson.
        for (int i = 0; i < items; i++) {
            // Input side: bounded. Read a chunk, use it, drop it.
            byte[] input = new byte[inputBytes];
            input[0] = (byte) (i & 0xFF); // touch it so it isn't optimized away

            // "Process" the record into a summary — identical bytes to the
            // fixed version's streamed output — and keep every summary for
            // the final report.
            REPORT.add(makeSummary(i, summaryBytes));

            if ((i + 1) % 20_000 == 0) {
                System.out.println("processed " + (i + 1) + " records, report size: " + REPORT.size());
            }
        }
        System.out.println("done, report has " + REPORT.size() + " summaries"
                + " (unexpected for the broken version — was the heap enlarged?)");
    }

    /**
     * Deterministic per-record summary: a pure function of the record index.
     * Deliberately duplicated in ReportBuilderFixed — both versions must
     * generate byte-for-byte identical output so the experiment isolates
     * retention, not content. Keep the two copies in sync.
     */
    static byte[] makeSummary(int index, int size) {
        byte[] s = new byte[size];
        for (int j = 0; j < size; j++) {
            s[j] = (byte) ((index * 31 + j) & 0xFF);
        }
        return s;
    }
}
