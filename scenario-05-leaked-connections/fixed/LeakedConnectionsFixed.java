import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * SCENARIO 05 (fixed): the leaked connection, closed properly.
 *
 * Same workload as the broken version — 1,000 connections opened, one per
 * unit of work — but each connection is opened in try-with-resources and
 * deregisters itself on close. Live memory stays at roughly one connection's
 * buffer (~256 KB) regardless of how many units of work run.
 *
 * The principle: every resource acquisition needs a matching release on
 * every path, and the release must also drop the bookkeeping reference.
 * In production this is try-with-resources (or close in finally), a
 * connection pool with leak detection, and treating "connections open"
 * metrics as an alert, not a dashboard decoration.
 *
 *   javac LeakedConnectionsFixed.java && java -Xmx64m LeakedConnectionsFixed
 */
public class LeakedConnectionsFixed {

    static final class Connection implements AutoCloseable {
        private static final Set<Connection> openConnections =
                Collections.synchronizedSet(new HashSet<>());

        private final byte[] buffer = new byte[256 * 1024]; // ~256 KB per connection
        private final long id;

        Connection(long id) {
            this.id = id;
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

    private static final int CONNECTIONS = 1_000; // same order of work as the broken run

    public static void main(String[] args) {
        for (long i = 0; i < CONNECTIONS; i++) {
            try (Connection conn = new Connection(i)) { // closed at the end of every iteration
                conn.use();
            }
            if ((i + 1) % 100 == 0) {
                System.out.println("processed " + (i + 1) + " connections, still open: "
                        + Connection.openCount());
            }
        }
        System.out.println("all " + CONNECTIONS + " connections processed and closed, heap survived.");
    }
}
