package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.Drift;
import dev.tally.core.ReconciliationReport;
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
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Reconciliation against Postgres, where a raw UPDATE can bump a stored balance without a matching
 * posting, the exact corruption reconcile exists to catch. The in-memory store cannot be torn like
 * this, so this drift check lives only here.
 */
@Tag("integration")
class JdbcReconciliationTest {
    private static Pool pool;
    private JdbcStore store;

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
        store = new JdbcStore(pool);
    }

    @Test
    void rawBalanceBumpIsReportedAsDrift() throws SQLException {
        Account a = store.createAccount("a", 1000);
        bumpBalance(a.id().value(), 50);   // stored says 1050, its postings still sum to 1000

        ReconciliationReport report = store.reconcile();
        assertFalse(report.consistent());
        assertEquals(50, report.globalSumMinor());   // the book no longer nets zero

        Drift drift = only(report.drifts());
        assertEquals(a.id(), drift.accountId());
        assertEquals(1050, drift.storedBalanceMinor());
        assertEquals(1000, drift.derivedBalanceMinor());
        assertEquals(50, drift.driftMinor());
    }

    @Test
    void offsettingDriftsAreCaughtPerAccount() throws SQLException {
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 1000);
        bumpBalance(a.id().value(), 50);
        bumpBalance(b.id().value(), -50);   // the global sum is still zero, but both accounts drifted

        ReconciliationReport report = store.reconcile();
        assertEquals(0, report.globalSumMinor());   // a global-only check would call this clean
        assertFalse(report.consistent());           // the per-account check does not
        assertEquals(2, report.drifts().size());
        assertEquals(50, driftFor(report, a.id().value()));
        assertEquals(-50, driftFor(report, b.id().value()));
    }

    private static Drift only(List<Drift> drifts) {
        assertEquals(1, drifts.size());
        return drifts.getFirst();
    }

    private static long driftFor(ReconciliationReport report, UUID accountId) {
        return report.drifts().stream()
                .filter(d -> d.accountId().value().equals(accountId))
                .findFirst()
                .orElseThrow()
                .driftMinor();
    }

    // A balance change with no posting behind it, the drift a real audit is meant to surface.
    private void bumpBalance(UUID accountId, long delta) throws SQLException {
        try (Connection c = PostgresTestSupport.connect();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE accounts SET balance_minor = balance_minor + ? WHERE id = ?")) {
            ps.setLong(1, delta);
            ps.setObject(2, accountId);
            ps.executeUpdate();
        }
    }
}
