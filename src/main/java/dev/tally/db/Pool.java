package dev.tally.db;

import dev.tally.store.StoreException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

/**
 * A fixed-size JDBC connection pool that validates on borrow and heals on give-back.
 *
 * Fixed size bounds the load on Postgres no matter how many virtual threads exist. Because JEP 491
 * (JDK 24, present in Java 25) removed synchronized pinning, a virtual thread that blocks here does
 * not pin its carrier.
 */
public final class Pool implements AutoCloseable {
    private final DbConfig config;
    private final BlockingQueue<Connection> idle;
    private final LongConsumer waitNanos;
    // Connections the pool owes itself: ones that broke while the database was down and could not be
    // replaced then. Borrow opens them again, so an outage shrinks the pool only while it lasts.
    private final AtomicInteger missing = new AtomicInteger();

    private Pool(DbConfig config, BlockingQueue<Connection> idle, LongConsumer waitNanos) {
        this.config = config;
        this.idle = idle;
        this.waitNanos = waitNanos;
    }

    public static Pool open(DbConfig config) throws SQLException {
        return open(config, nanos -> {});
    }

    // A plain callback, so the db package does not depend on obs. Main passes the pool-wait histogram.
    public static Pool open(DbConfig config, LongConsumer waitNanos) throws SQLException {
        BlockingQueue<Connection> idle = new ArrayBlockingQueue<>(config.poolSize());
        for (int i = 0; i < config.poolSize(); i++) {
            idle.add(fresh(config));   // eager fill
        }
        return new Pool(config, idle, waitNanos);
    }

    // No Class.forName: the driver self-registers through ServiceLoader. The driver's default socket timeout
    // is none, so a partition that drops packets left a read waiting forever and its pool slot with it.
    private static Connection fresh(DbConfig config) throws SQLException {
        Properties props = new Properties();
        props.setProperty("user", config.user());
        props.setProperty("password", config.password());
        props.setProperty("connectTimeout", "5");
        props.setProperty("loginTimeout", String.valueOf(config.socketTimeoutSeconds()));
        props.setProperty("socketTimeout", String.valueOf(config.socketTimeoutSeconds()));
        props.setProperty("tcpKeepAlive", "true");
        return DriverManager.getConnection(config.url(), props);
    }

    // Validate before handing out: a database bounce drops every socket, so a dead connection is
    // discarded and replaced with a fresh one rather than served to a borrower.
    public Connection borrow() {
        try {
            long start = System.nanoTime();
            Connection conn = idle.poll();
            if (conn == null && missing.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                waitNanos.accept(System.nanoTime() - start);
                return refill();
            }
            if (conn == null) {
                conn = idle.poll(5, TimeUnit.SECONDS);
            }
            waitNanos.accept(System.nanoTime() - start);
            if (conn == null) {
                throw new StoreException("timed out waiting for a database connection");
            }
            if (isDead(conn)) {
                closeQuietly(conn);
                return fresh(config);
            }
            return conn;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StoreException("interrupted while borrowing a connection", e);
        } catch (SQLException e) {
            throw new StoreException("could not open a replacement connection", e);
        }
    }

    // Heal and reset: replace a broken connection so the pool never shrinks, and roll back a leaked
    // open transaction, the safety net that makes a leaked transaction impossible even if a store
    // bug skips its own finally.
    public void giveBack(Connection conn) {
        try {
            if (isDead(conn)) {
                closeQuietly(conn);
                offerFresh();
                return;
            }
            if (!conn.getAutoCommit()) {
                conn.rollback();
                conn.setAutoCommit(true);
            }
            // Reset what reconcile changes, so a read-only REPEATABLE READ connection never leaks to a
            // transfer that must run read-committed and writable.
            if (conn.isReadOnly()) {
                conn.setReadOnly(false);
            }
            if (conn.getTransactionIsolation() != Connection.TRANSACTION_READ_COMMITTED) {
                conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            }
            idle.offer(conn);
        } catch (SQLException broken) {
            closeQuietly(conn);
            offerFresh();
        }
    }

    private void offerFresh() {
        try {
            idle.offer(fresh(config));
        } catch (SQLException databaseDown) {
            missing.incrementAndGet();
        }
    }

    // The slot stays owed if the database is still down, and this borrow fails at once instead of
    // waiting five seconds for a connection that no one will give back.
    private Connection refill() {
        try {
            return fresh(config);
        } catch (SQLException e) {
            missing.incrementAndGet();
            throw new StoreException("could not open a connection, the database looks down", e);
        }
    }

    private static boolean isDead(Connection conn) {
        try {
            return conn.isClosed() || !conn.isValid(1);
        } catch (SQLException e) {
            return true;
        }
    }

    private static void closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // nothing to do while closing
        }
    }

    @Override
    public void close() {
        Connection conn;
        while ((conn = idle.poll()) != null) {
            closeQuietly(conn);
        }
    }
}
