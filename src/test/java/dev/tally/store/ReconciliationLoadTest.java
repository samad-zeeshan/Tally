package dev.tally.store;

import dev.tally.core.AccountId;
import dev.tally.core.ReconciliationReport;
import dev.tally.core.TransferRequest;
import dev.tally.db.DbConfig;
import dev.tally.db.Pool;
import dev.tally.db.PostgresTestSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The audit sampled while money is moving. Reconcile reads a REPEATABLE READ snapshot, so an in-flight
 * transfer is invisible until it commits whole; every sample, mid-load or after, must net to zero with
 * no drift. A snapshot that ever caught a half-applied transfer would fail here.
 */
@Tag("integration")
class ReconciliationLoadTest {
    private static Pool pool;

    @BeforeAll
    static void open() throws SQLException {
        PostgresTestSupport.migrate();
        pool = Pool.open(new DbConfig(PostgresTestSupport.url(), PostgresTestSupport.user(),
                PostgresTestSupport.password(), 16));
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
    @Timeout(120)
    void booksBalanceAfterConcurrentTraffic() throws Exception {
        JdbcStore store = new JdbcStore(pool);
        int accountCount = 6;
        List<AccountId> ids = new ArrayList<>();
        for (int i = 0; i < accountCount; i++) {
            ids.add(store.createAccount("acct-" + i, 10_000).id());
        }
        int workers = 6;
        int perWorker = 150;

        try (ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> workerRuns = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                int seed = w;
                workerRuns.add(exec.submit(() -> {
                    Random random = new Random(seed);
                    for (int i = 0; i < perWorker; i++) {
                        int from = random.nextInt(accountCount);
                        int to = (from + 1 + random.nextInt(accountCount - 1)) % accountCount;   // never itself
                        store.apply(new TransferRequest(
                                "load-" + seed + "-" + i, ids.get(from), ids.get(to), random.nextLong(1, 300)));
                    }
                }));
            }
            // Hammer the audit from the main thread while transfers commit underneath it.
            while (!workerRuns.stream().allMatch(Future::isDone)) {
                assertBalanced(store.reconcile());
            }
            for (Future<?> run : workerRuns) {
                run.get();   // surface any worker failure
            }
        }
        assertBalanced(store.reconcile());
    }

    private static void assertBalanced(ReconciliationReport report) {
        assertEquals(0, report.globalSumMinor(), "book did not net to zero");
        assertTrue(report.consistent(), () -> "drift under load: " + report.drifts());
    }
}
