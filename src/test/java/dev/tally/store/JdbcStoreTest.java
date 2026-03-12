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
import org.junit.jupiter.api.Timeout;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * The Postgres store runs the shared contract, plus restart and concurrency proofs.
 */
@Tag("integration")
class JdbcStoreTest extends StoreContractTest {
    private static Pool pool;

    @BeforeAll
    static void open() throws SQLException {
        PostgresTestSupport.migrate();
        pool = Pool.open(new DbConfig(PostgresTestSupport.url(), PostgresTestSupport.user(),
                PostgresTestSupport.password(), 8));
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

    @Override
    protected Store newStore() {
        return new JdbcStore(pool);
    }

    @Test
    void idempotencyKeySurvivesRestart() {
        JdbcStore first = new JdbcStore(pool);
        Account a = first.createAccount("a", 1000);
        Account b = first.createAccount("b", 0);
        TransferRequest request = new TransferRequest("survives-restart-key", a.id(), b.id(), 300);
        first.apply(request);

        // A fresh store on the same database is a restart in miniature.
        JdbcStore afterRestart = new JdbcStore(pool);
        assertInstanceOf(TransferOutcome.Replayed.class, afterRestart.apply(request));
        assertEquals(700, afterRestart.findAccount(a.id()).orElseThrow().balanceMinor());   // moved once
    }

    @Test
    @Timeout(60)
    void concurrentSameKeyAppliesOnce() throws Exception {
        JdbcStore store = new JdbcStore(pool);
        Account a = store.createAccount("a", 10_000);
        Account b = store.createAccount("b", 0);
        TransferRequest request = new TransferRequest("concurrent-key", a.id(), b.id(), 500);

        int threads = 32;
        ExecutorService workers = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<TransferOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(workers.submit(() -> {
                start.await();
                return store.apply(request);
            }));
        }
        start.countDown();
        int applied = 0;
        for (Future<TransferOutcome> f : futures) {
            if (f.get() instanceof TransferOutcome.Applied) {
                applied++;
            }
        }
        workers.shutdownNow();
        assertEquals(1, applied, "exactly one thread applied; the unique constraint did the work");
        assertEquals(9_500, store.findAccount(a.id()).orElseThrow().balanceMinor());   // moved once
    }
}
