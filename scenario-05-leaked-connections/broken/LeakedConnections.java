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
 * Run with a small heap and watch the registry grow until the JVM gives up:
 *   javac LeakedConnections.java && java -Xmx64m LeakedConnections
 */
public class LeakedConnections {

    // Stands in for a socket / DB handle / file stream: something with a
    // close() method and a buffer that makes the leak expensive.
    static final class Connection {
        // Every open connection is registered here "for tracking."
        // Nothing ever removes them. This set is the leak's GC root.
        private static final Set<Connection> openConnections = new HashSet<>();

        private final byte[] buffer = new byte[256 * 1024]; // ~256 KB per connection
        private final long id;

        Connection(long id) {
            this.id = id;
            openConnections.add(this); // registered on open...
            // ...and never deregistered. close() is never called.
        }

        void use() {
            buffer[0] = (byte) id; // pretend to do work with the connection
        }
    }

    public static void main(String[] args) {
        long count = 0;
        try {
            while (true) {
                Connection conn = new Connection(count);
                conn.use();
                count++;
                if (count % 100 == 0) {
                    System.out.println("opened " + count + " connections, still open: "
                            + Connection.openConnections.size());
                }
            }
        } catch (OutOfMemoryError e) {
            System.err.println("OutOfMemoryError after opening " + count + " connections.");
            throw e; // rethrow: the stack trace is part of the lesson
        }
    }
}
