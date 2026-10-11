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
 * Be precise about the mechanism: the missing close() is the bug, but the
 * static registry is the strong-reference GC root that actually pins the
 * buffers in the heap. A leaked resource nobody tracks is still a leak (file
 * descriptors, native memory), but it would not show up as Java heap growth;
 * here the "observability" set is what turns the lifecycle bug into heap
 * exhaustion.
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
    static final class Connection {
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
            // ...and never deregistered. close() is never called.
        }

        void use() {
            buffer[0] = (byte) id; // pretend to do work with the connection
        }
    }

    public static void main(String[] args) {
        int connections = 100_000;
        int bufferKb = 256;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--connections": connections = Integer.parseInt(args[++i]); break;
                case "--buffer-kb":   bufferKb     = Integer.parseInt(args[++i]); break;
                default: throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }
        System.out.println("STARTED scenario-05-leaked-connections connections=" + connections
                + " bufferKb=" + bufferKb + " (expect OutOfMemoryError)");

        // No try/catch around the OutOfMemoryError: the raw error and stack
        // trace are the lesson.
        for (long i = 0; i < connections; i++) {
            Connection conn = new Connection(i, bufferKb * 1024);
            conn.use();
            if ((i + 1) % 1_000 == 0) {
                System.out.println("opened " + (i + 1) + " connections, still open: "
                        + Connection.openConnections.size());
            }
        }
        System.out.println("finished all " + connections + " connections without OOM"
                + " (unexpected for the broken version — was the heap enlarged?)");
    }
}
