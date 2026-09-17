package dev.tally.db;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pool bounds connections, times out cleanly when exhausted, and heals a database bounce.
 */
@Tag("integration")
class PoolTest {

    private Pool pool(int size) throws SQLException {
        return Pool.open(new DbConfig(PostgresTestSupport.url(), PostgresTestSupport.user(),
                PostgresTestSupport.password(), size));
    }

    @Test
    @Timeout(15)
    void borrowTimesOutWhenExhausted() throws SQLException {
        try (Pool pool = pool(1)) {
            Connection held = pool.borrow();
            assertThrows(RuntimeException.class, pool::borrow);   // the one connection is out, so this waits then fails
            pool.giveBack(held);
        }
    }

    @Test
    @Timeout(15)
    void everyBorrowReportsHowLongItWaited() throws SQLException {
        List<Long> waits = new CopyOnWriteArrayList<>();
        try (Pool pool = Pool.open(new DbConfig(PostgresTestSupport.url(), PostgresTestSupport.user(),
                PostgresTestSupport.password(), 1), waits::add)) {
            pool.giveBack(pool.borrow());
            Connection held = pool.borrow();
            assertThrows(RuntimeException.class, pool::borrow);
            pool.giveBack(held);
        }
        assertEquals(3, waits.size(), "a timed-out borrow is a wait too");
        assertTrue(waits.get(2) >= 4_000_000_000L, "the timed-out borrow waited about five seconds: " + waits);
    }

    @Test
    void giveBackResetsOpenTransaction() throws SQLException {
        try (Pool pool = pool(1)) {
            Connection conn = pool.borrow();
            conn.setAutoCommit(false);
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TEMP TABLE leaked (x int)");   // uncommitted work
            }
            pool.giveBack(conn);

            Connection again = pool.borrow();
            assertTrue(again.getAutoCommit(), "the connection came back with autocommit reset");
            pool.giveBack(again);
        }
    }

    @Test
    void brokenConnectionGivenBackIsReplaced() throws SQLException {
        try (Pool pool = pool(2)) {
            Connection conn = pool.borrow();
            conn.close();          // simulate a broken socket
            pool.giveBack(conn);   // the pool must replace it, not shrink

            Connection a = pool.borrow();
            Connection b = pool.borrow();
            assertTrue(a.isValid(1) && b.isValid(1), "the pool still hands out working connections");
            pool.giveBack(a);
            pool.giveBack(b);
        }
    }

    @Test
    void invalidConnectionOnBorrowIsReplaced() throws Exception {
        try (Pool pool = pool(2)) {
            terminateOtherBackends();   // kill the pool's idle connections server-side
            Connection conn = pool.borrow();   // borrow validates, discards the dead one, opens a fresh one
            assertTrue(conn.isValid(1));
            pool.giveBack(conn);
        }
    }

    // Found by the fault harness: while Postgres restarts, every connection given back is dead and cannot
    // be replaced, so the pool drained to nothing and the API stayed down after the database came back.
    @Test
    @Timeout(60)
    void poolRefillsAfterAnOutageThatDrainedIt() throws Exception {
        try (Connection admin = PostgresTestSupport.connect(); Statement s = admin.createStatement()) {
            s.execute("DROP ROLE IF EXISTS tally_pool_outage");
            s.execute("CREATE ROLE tally_pool_outage LOGIN PASSWORD 'outage-only-in-tests'");
            try (Pool pool = Pool.open(new DbConfig(PostgresTestSupport.urlWithoutCredentials(), "tally_pool_outage", "outage-only-in-tests", 2))) {
                Connection a = pool.borrow();
                Connection b = pool.borrow();
                // The outage: no new logins, and every open session cut.
                s.execute("ALTER ROLE tally_pool_outage NOLOGIN");
                s.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'tally_pool_outage'");
                PostgresTestSupport.awaitNoSessions(s, "tally_pool_outage");
                pool.giveBack(a);
                pool.giveBack(b);
                s.execute("ALTER ROLE tally_pool_outage LOGIN");

                Connection back = pool.borrow();
                assertTrue(back.isValid(1), "the pool serves again once the database is back");
                Connection second = pool.borrow();
                assertTrue(second.isValid(1), "and at its full size");
                pool.giveBack(back);
                pool.giveBack(second);
            } finally {
                s.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'tally_pool_outage'");
                s.execute("DROP ROLE IF EXISTS tally_pool_outage");
            }
        }
    }

    // The same outage, but the pool finds the dead connections on borrow rather than on give-back. Found
    // on kind, where one API pod answered every request with a pool timeout long after Postgres was back.
    @Test
    @Timeout(60)
    void borrowsDuringAnOutageDoNotLoseTheirSlots() throws Exception {
        try (Connection admin = PostgresTestSupport.connect(); Statement s = admin.createStatement()) {
            s.execute("DROP ROLE IF EXISTS tally_pool_outage_borrow");
            s.execute("CREATE ROLE tally_pool_outage_borrow LOGIN PASSWORD 'outage-only-in-tests'");
            try (Pool pool = Pool.open(new DbConfig(PostgresTestSupport.urlWithoutCredentials(), "tally_pool_outage_borrow", "outage-only-in-tests", 2))) {
                s.execute("ALTER ROLE tally_pool_outage_borrow NOLOGIN");
                s.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'tally_pool_outage_borrow'");
                PostgresTestSupport.awaitNoSessions(s, "tally_pool_outage_borrow");
                assertThrows(RuntimeException.class, pool::borrow);
                assertThrows(RuntimeException.class, pool::borrow);
                s.execute("ALTER ROLE tally_pool_outage_borrow LOGIN");

                Connection a = pool.borrow();
                Connection b = pool.borrow();
                assertTrue(a.isValid(1) && b.isValid(1), "both slots come back once the database does");
                pool.giveBack(a);
                pool.giveBack(b);
            } finally {
                s.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = 'tally_pool_outage_borrow'");
                s.execute("DROP ROLE IF EXISTS tally_pool_outage_borrow");
            }
        }
    }

    private void terminateOtherBackends() throws SQLException {
        try (Connection c = PostgresTestSupport.connect(); Statement s = c.createStatement()) {
            s.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity "
                    + "WHERE datname = current_database() AND pid <> pg_backend_pid()");
        }
    }
}
