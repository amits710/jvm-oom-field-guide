import java.util.HashSet;
import java.util.Set;

/**
 * SCENARIO 05 (fixed): the leaked connection, closed properly.
 *
 * Identical workload to the broken version — same --connections, same
 * --buffer-kb — but each connection is opened in try-with-resources and
 * deregisters itself on close. Live memory stays at roughly one connection's
 * buffer regardless of how many units of work run.
 *
 * The principle: every resource acquisition needs a matching release on
 * every path, and the release must also drop the bookkeeping reference.
 * In production this is try-with-resources (or close in finally), a
 * connection pool with leak detection, and treating "connections open"
 * metrics as an alert, not a dashboard decoration.
 *
 *   ./run.sh [--connections 100000] [--buffer-kb 256]
 */
public class LeakedConnectionsFixed {

    static final class Connection implements AutoCloseable {
        // Plain HashSet: this demonstration is single-threaded, so there is
        // no reason for synchronization theater. (A production registry
        // shared across threads would need real concurrency control.)
        private static final Set<Connection> openConnections = new HashSet<>();

        private final byte[] buffer;
        private final long id;

        Connection(long id, int bufferBytes) {
            this.id = id;
            this.buffer = new byte[bufferBytes];
            openConnections.add(this);
        }

        void use() {
            buffer[0] = (byte) id; // pretend to do work with the connection
        }

        @Override
        public void close() {
            openConnections.remove(this); // deregister: the reference dies with the resource
        }

        static int openCount() {
            return openConnections.size();
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
        System.out.println("STARTED scenario-05-leaked-connections(fixed) connections=" + connections
                + " bufferKb=" + bufferKb);

        for (long i = 0; i < connections; i++) {
            try (Connection conn = new Connection(i, bufferBytes)) { // closed at the end of every iteration
                conn.use();
            }
            if ((i + 1) % 10_000 == 0) {
                System.out.println("processed " + (i + 1) + " connections, still open: "
                        + Connection.openCount());
            }
        }
        System.out.println("COMPLETED connections=" + connections
                + " open=" + Connection.openCount());
    }

    // ---------- CLI validation: bad input exits 2 with usage on stderr ----------

    private static void usage(String problem) {
        System.err.println("error: " + problem);
        System.err.println("usage: LeakedConnectionsFixed [--connections N] [--buffer-kb N]");
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
