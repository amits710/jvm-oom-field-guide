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
 * The per-record summary bytes are deterministic — a pure function of the
 * record index and input byte, computed by the shared SummaryCodec — so they
 * are byte-for-byte identical to what the fixed version streams to disk.
 * Same output, different retention: that is the whole experiment.
 *
 * Note the GC-root shape, and how it differs from scenario 01: here the
 * report list is an *instance* field of a ReportBuilder object held by
 * main's local variable — main -> builder -> list -> summaries. In 01 the
 * cache is a *static* field. Same staircase in the GC log, different root
 * path in the heap dump (see diagnosis.md).
 *
 * Compare with scenario 02: there one batch exceeds the heap (a cliff).
 * Here the heap dies on a slow staircase, just like 01 — but the heap dump
 * tells a different story.
 *
 *   ./run.sh [--items 300000] [--input-bytes 1024] [--summary-bytes 1024]
 */
public class ReportBuilder {

    // The report: an INSTANCE field, not static. The builder object is held
    // by main's local variable, so the GC root path is
    // main frame -> builder -> report -> summaries (contrast with 01's
    // static cache). Appended to for every record, never flushed, never
    // cleared.
    private final List<byte[]> report = new ArrayList<>();

    public static void main(String[] args) {
        int items = 300_000;
        int inputBytes = 1024;
        int summaryBytes = 1024;
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
                default:
                    usage("unknown argument: " + args[i]);
            }
        }
        System.out.println("STARTED scenario-03-report-builder items=" + items
                + " inputBytes=" + inputBytes + " summaryBytes=" + summaryBytes
                + " (expect OutOfMemoryError)");

        ReportBuilder builder = new ReportBuilder();
        builder.run(items, inputBytes, summaryBytes);
    }

    private void run(int items, int inputBytes, int summaryBytes) {
        // No try/catch around the OutOfMemoryError: the raw error and stack
        // trace are the lesson.
        for (int i = 0; i < items; i++) {
            // Input side: bounded. Read a chunk, use it, drop it.
            byte[] input = new byte[inputBytes];
            input[0] = (byte) (i & 0xFF); // touch it so it isn't optimized away

            // "Process" the record into a summary — identical bytes to the
            // fixed version's streamed output (same shared codec) — and keep
            // every summary for the final report.
            report.add(SummaryCodec.makeSummary(i, input[0], summaryBytes));

            if ((i + 1) % 20_000 == 0) {
                System.out.println("processed " + (i + 1) + " records, report size: " + report.size());
            }
        }
        System.out.println("done, report has " + report.size() + " summaries"
                + " (unexpected for the broken version — was the heap enlarged?)");
    }

    // ---------- CLI validation: bad input exits 2 with usage on stderr ----------

    private static void usage(String problem) {
        System.err.println("error: " + problem);
        System.err.println("usage: ReportBuilder [--items N] [--input-bytes N] [--summary-bytes N]");
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
