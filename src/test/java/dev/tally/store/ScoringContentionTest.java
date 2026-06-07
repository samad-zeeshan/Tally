package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.db.DbConfig;
import dev.tally.db.Pool;
import dev.tally.db.PostgresTestSupport;
import dev.tally.fraud.JdbcScoreStore;
import dev.tally.fraud.Score;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Transfers and score inserts on the same accounts at once must never deadlock each other.
 *
 * Found by the fault harness on kind with no fault injected: a score row's foreign keys lock the two
 * account rows in whatever order they come, and a transfer holding one of them waited on the other.
 */
@Tag("integration")
class ScoringContentionTest {

    @BeforeEach
    void reset() {
        PostgresTestSupport.migrate();
        PostgresTestSupport.resetSchema();
    }

    @Test
    @Timeout(120)
    void transfersAndScoreInsertsNeverDeadlock() throws Exception {
        try (Pool pool = Pool.open(new DbConfig(PostgresTestSupport.url(), PostgresTestSupport.user(),
                PostgresTestSupport.password(), 12))) {
            JdbcStore store = new JdbcStore(pool);
            JdbcScoreStore scores = new JdbcScoreStore(pool);
            List<Account> accounts = List.of(
                    store.createAccount("a", 1_000_000), store.createAccount("b", 1_000_000),
                    store.createAccount("c", 1_000_000), store.createAccount("d", 1_000_000));
            ConcurrentLinkedQueue<Score> toScore = new ConcurrentLinkedQueue<>();
            ConcurrentLinkedQueue<Throwable> failures = new ConcurrentLinkedQueue<>();
            AtomicBoolean done = new AtomicBoolean();
            int threads = 8;
            int perThread = 150;
            CountDownLatch start = new CountDownLatch(1);

            // The scorer runs beside the transfers, as the queue consumer does in the service.
            Thread scorer = Thread.ofVirtual().start(() -> {
                while (!done.get() || !toScore.isEmpty()) {
                    Score s = toScore.poll();
                    if (s == null) {
                        Thread.onSpinWait();
                        continue;
                    }
                    try {
                        scores.insertIfAbsent(s);
                    } catch (RuntimeException e) {
                        failures.add(e);
                    }
                }
            });
            Thread[] workers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                int id = t;
                workers[t] = Thread.ofVirtual().start(() -> {
                    Random rnd = new Random(id);
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    for (int i = 0; i < perThread; i++) {
                        Account from = accounts.get(rnd.nextInt(accounts.size()));
                        Account to = accounts.get((accounts.indexOf(from) + 1 + rnd.nextInt(accounts.size() - 1)) % accounts.size());
                        TransferRequest req = new TransferRequest("contention-" + id + "-" + i, from.id(), to.id(), 1 + rnd.nextInt(50));
                        try {
                            if (store.apply(req) instanceof TransferOutcome.Applied a) {
                                toScore.add(score(a, req));
                            }
                        } catch (RuntimeException e) {
                            failures.add(e);
                        }
                    }
                });
            }
            start.countDown();
            for (Thread w : workers) {
                w.join();
            }
            done.set(true);
            scorer.join();

            assertEquals(List.of(), failures.stream().map(ScoringContentionTest::rootMessage).distinct().toList(),
                    "no transfer and no score insert may fail on lock contention");
            assertEquals(0L, store.reconcile().globalSumMinor());
        }
    }

    private static Score score(TransferOutcome.Applied a, TransferRequest req) {
        return new Score(a.debitPostingId(), a.id(), req.from(), req.to(), req.amountMinor(), 0, List.of(),
                a.at(), Instant.now());
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.toString() : root.getMessage().lines().findFirst().orElse("");
    }
}
