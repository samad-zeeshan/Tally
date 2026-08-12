package dev.tally.db;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A partition that drops packets without closing anything must not hold pool connections forever.
 *
 * Found by the fault harness on kind: after a NetworkPolicy partition was lifted, the API never served
 * again, because every pooled connection was stuck reading a reply that would never come.
 */
@Tag("integration")
class PartitionTest {

    // Forwards bytes both ways until told to swallow them. Nothing is closed, so neither side notices.
    private static final class BlackHole implements AutoCloseable {
        final AtomicBoolean dropping = new AtomicBoolean();
        final ServerSocket server;
        final String host;
        final int port;

        BlackHole(String host, int port) throws IOException {
            this.host = host;
            this.port = port;
            this.server = new ServerSocket(0);
            Thread.ofVirtual().start(this::accept);
        }

        private void accept() {
            try {
                while (true) {
                    Socket client = server.accept();
                    Socket upstream = new Socket(host, port);
                    pipe(client, upstream);
                    pipe(upstream, client);
                }
            } catch (IOException closed) {
                // the test is over
            }
        }

        private void pipe(Socket from, Socket to) {
            Thread.ofVirtual().start(() -> {
                byte[] buf = new byte[8192];
                try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
                    int n;
                    while ((n = in.read(buf)) >= 0) {
                        if (!dropping.get()) {
                            out.write(buf, 0, n);
                            out.flush();
                        }
                    }
                } catch (IOException gone) {
                    // one side went away
                }
            });
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }

    @Test
    // A separate thread, because a thread blocked in a socket read ignores the interrupt a same-thread timeout sends.
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aQueryDuringAPartitionFailsAndThePoolServesAgainAfterIt() throws Exception {
        URI db = URI.create(PostgresTestSupport.url().substring("jdbc:".length()));
        try (BlackHole hole = new BlackHole(db.getHost(), db.getPort())) {
            String url = "jdbc:postgresql://127.0.0.1:" + hole.server.getLocalPort() + db.getRawPath()
                    + (db.getRawQuery() == null ? "" : "?" + db.getRawQuery());
            DbConfig config = new DbConfig(url, PostgresTestSupport.user(), PostgresTestSupport.password(), 2, 2);
            try (Pool pool = Pool.open(config)) {
                Connection conn = pool.borrow();
                hole.dropping.set(true);
                long start = System.nanoTime();
                assertThrows(SQLException.class, () -> {
                    try (Statement s = conn.createStatement()) {
                        s.execute("SELECT 1");
                    }
                });
                assertTrue(System.nanoTime() - start < 10_000_000_000L, "the query gave up on its socket timeout");
                pool.giveBack(conn);
                hole.dropping.set(false);

                Connection again = pool.borrow();
                try (Statement s = again.createStatement()) {
                    assertTrue(s.execute("SELECT 1"), "the pool serves once the partition heals");
                }
                pool.giveBack(again);
            }
        }
    }
}
