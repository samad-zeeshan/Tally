package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.db.DbConfig;
import dev.tally.db.Pool;
import dev.tally.db.PostgresTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A transfer that fails mid-flight leaves no half-applied money. The fault hook throws between the
 * debit and the credit, the widest half-applied window, and the transaction rolls back whole.
 */
@Tag("integration")
class JdbcCrashSafetyTest {
    private static Pool pool;

    @BeforeAll
    static void open() throws SQLException {
        PostgresTestSupport.migrate();
        pool = Pool.open(new DbConfig(PostgresTestSupport.url(), PostgresTestSupport.user(),
                PostgresTestSupport.password(), 4));
    }

    @AfterAll
    static void close() {
        if (pool != null) {
            pool.close();
        }
    }

    @BeforeEach
    void reset() {
        PostgresTestSupport.resetSchema();
    }

    @Test
    void rollbackOnMidTransferFault() throws Exception {
        JdbcStore faulty = new JdbcStore(pool, () -> {
            throw new RuntimeException("injected fault");
        });
        Account a = faulty.createAccount("a", 1000);
        Account b = faulty.createAccount("b", 0);
        TransferRequest request = new TransferRequest("fault-key-1", a.id(), b.id(), 300);

        assertThrows(StoreException.class, () -> faulty.apply(request));
        assertEquals(1000, faulty.findAccount(a.id()).orElseThrow().balanceMinor());   // both unchanged
        assertEquals(0, faulty.findAccount(b.id()).orElseThrow().balanceMinor());
        assertEquals(0, countTransfers("fault-key-1"));   // the transfers row rolled back with the debit
    }

    @Test
    void retryAfterFaultSucceeds() {
        JdbcStore faulty = new JdbcStore(pool, () -> {
            throw new RuntimeException("injected fault");
        });
        Account a = faulty.createAccount("a", 1000);
        Account b = faulty.createAccount("b", 0);
        TransferRequest request = new TransferRequest("fault-key-2", a.id(), b.id(), 300);
        assertThrows(StoreException.class, () -> faulty.apply(request));

        // The fault rolled back before commit, so the key was not burned: a clean store applies it.
        JdbcStore clean = new JdbcStore(pool);
        assertInstanceOf(TransferOutcome.Applied.class, clean.apply(request));
        assertEquals(700, clean.findAccount(a.id()).orElseThrow().balanceMinor());
    }

    private long countTransfers(String key) throws SQLException {
        try (Connection c = PostgresTestSupport.connect();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM transfers WHERE idempotency_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }
}
