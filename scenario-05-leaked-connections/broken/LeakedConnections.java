import java.util.HashSet;
import java.util.Set;

/**
 * SCENARIO 05 (broken): the leaked connection.
 *
 * Models a pipeline that opens a connection per unit of work — a database
 * handle, a socket, a file stream — and never closes it. Each connection pins
 * a buffer; a static "tracking" registry keeps every one of them reachable
 * forever. The registry grows 1:1 with work done until the heap gives out.
 *
 * Be precise about the mechanism: the missing close() *call* is the bug, but
 * the static registry is the strong-reference GC root that actually pins the
 * buffers in the heap. A leaked resource nobody tracks is still a leak (file
 * descriptors, native memory), but it would not show up as Java heap growth;
 * here the "observability" set is what turns the lifecycle bug into heap
 * exhaustion.
 *
 * Note the class shape, shared with the fixed version: Connection implements
 * AutoCloseable and HAS a close() method that deregisters from the set. The
 * bug is not a missing method — it is that main() never calls it. The
 * studied variable is lifecycle management (broken: never close; fixed:
 * try-with-resources), not the class design.
 *
 * This is a resource-lifecycle bug, not a cache and not a batch. Nothing here
 * is *supposed* to accumulate: each connection's useful life ends the moment
 * its unit of work finishes. But close() is never called, and the registry
 * that was meant for observability ("how many connections are open?") becomes
 * the thing that keeps them all alive.
 *
 * Compare with scenario 01: the GC signature is similar (a rising post-GC
 * staircase — both are retention), but the root cause and the fix differ.
 * 01's map holds data the program *chose* to keep; here the set holds
 * resources the program *forgot* to release. 01 is fixed with an eviction
 * policy; this is fixed by closing things.
 *
 * A production footnote: real unclosed resources often do NOT die as a Java
 * heap OOM first. They usually surface earlier as connection-pool exhaustion,
 * "too many open files," socket timeouts, or direct-memory pressure. This lab
 * isolates the heap-visible shape of the bug; see diagnosis.md.
 *
 *   ./run.sh [--connections 100000] [--buffer-kb 256]
 */
public class LeakedConnections {

    // Stands in for a socket / DB handle / file stream: something with a
    // close() method and a buffer that makes the leak expensive.
    // SAME class shape as the fixed version (implements AutoCloseable, has
    // close()) — the difference is purely that main() never calls close().
    static final class Connection implements AutoCloseable {
        // Every open connection is registered here "for tracking."
        // Nothing ever removes them. THIS SET is the leak's strong-reference
        // GC root — the reason the garbage collector cannot reclaim a single
        // one of these buffers.
        private static final Set<Connection> openConnections = new HashSet<>();

        private final byte[] buffer;
        private final long id;

        Connection(long id, int bufferBytes) {
            this.id = id;
            this.buffer = new byte[bufferBytes]; // ~256 KB per connection by default
            openConnections.add(this); // registered on open...
            // ...and never deregistered, because close() is never called.
        }

        void use() {
            buffer[0] = (byte) id; // pretend to do work with the connection
        }

        @Override
        public void close() {
            openConnections.remove(this); // exists, but nobody calls it — that's the bug
        }
    }

    public static void main(String[] args) {
        int connections = 100_000;
        int bufferKb = 256;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--connections":
                    connections = positiveInt(args, i, "--connections");
                    i++;
                    break;
                case "--buffer-kb":
                    bufferKb = positiveInt(args, i, "--buffer-kb");
                    i++;
                    break;
                default:
                    usage("unknown argument: " + args[i]);
            }
        }
        int bufferBytes;
        try {
            bufferBytes = Math.multiplyExact(bufferKb, 1024);
        } catch (ArithmeticException e) {
            usage("--buffer-kb " + bufferKb + " overflows when converted to bytes"
                    + " (max " + (Integer.MAX_VALUE / 1024) + " KB)");
            throw new AssertionError("unreachable");
        }
        System.out.println("STARTED scenario-05-leaked-connections connections=" + connections
                + " bufferKb=" + bufferKb + " (expect OutOfMemoryError)");

        // No try/catch around the OutOfMemoryError: the raw error and stack
        // trace are the lesson.
        for (long i = 0; i < connections; i++) {
            Connection conn = new Connection(i, bufferBytes);
            conn.use();
            if ((i + 1) % 50 == 0) {
                System.out.println("opened " + (i + 1) + " connections, still open: "
                        + Connection.openConnections.size());
            }
        }
        System.out.println("finished all " + connections + " connections without OOM"
                + " (unexpected for the broken version — was the heap enlarged?)");
    }

    // ---------- CLI validation: bad input exits 2 with usage on stderr ----------

    private static void usage(String problem) {
        System.err.println("error: " + problem);
        System.err.println("usage: LeakedConnections [--connections N] [--buffer-kb N]");
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
