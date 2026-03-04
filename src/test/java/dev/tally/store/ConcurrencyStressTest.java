package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.testsupport.StressHarness;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The real InMemoryStore under the stress harness: the same instrument the naive store fails.
 */
class ConcurrencyStressTest {

    @Test
    @Timeout(60)
    void stressRunKeepsAllInvariants() throws InterruptedException {
        StressHarness.assertClean(StressHarness.run(new InMemoryStore(), StressHarness.Config.standard(42)));
    }

    @Test
    @Timeout(60)
    void stressRunUnderOverdraftPressureKeepsAllInvariants() throws InterruptedException {
        StressHarness.assertClean(StressHarness.run(new InMemoryStore(), StressHarness.Config.overdraftPressure(7)));
    }

    @Test
    @Timeout(60)
    void concurrentFirstTimeDuplicatesApplyExactlyOnce() throws Exception {
        InMemoryStore store = new InMemoryStore();
        Account a = store.createAccount("A", 10_000);
        Account b = store.createAccount("B", 0);
        long amount = 10;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 200; round++) {
                String key = "dup-round-" + round;
                long prevB = store.findAccount(b.id()).orElseThrow().balanceMinor();
                CountDownLatch start = new CountDownLatch(1);
                Callable<TransferOutcome> fire = () -> {
                    start.await();
                    return store.apply(new TransferRequest(key, a.id(), b.id(), amount));
                };
                Future<TransferOutcome> f1 = pool.submit(fire);
                Future<TransferOutcome> f2 = pool.submit(fire);
                start.countDown();
                TransferOutcome o1 = f1.get();
                TransferOutcome o2 = f2.get();

                assertEquals(underlying(o1), underlying(o2), "both duplicates saw the same result");
                boolean r1 = o1 instanceof TransferOutcome.Replayed;
                boolean r2 = o2 instanceof TransferOutcome.Replayed;
                assertTrue(r1 ^ r2, "exactly one of the pair should be a replay in round " + round);
                assertEquals(prevB + amount, store.findAccount(b.id()).orElseThrow().balanceMinor(),
                        "money moved exactly once in round " + round);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    private static TransferOutcome underlying(TransferOutcome o) {
        return o instanceof TransferOutcome.Replayed r ? r.first() : o;
    }
}
