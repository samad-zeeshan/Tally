package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.testsupport.NaiveInMemoryStore;
import dev.tally.testsupport.StressHarness;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demonstrations that the naive store corrupts money. These assert a defect occurred, so they
 * are tag-excluded from the default run and CI and are never toggled on. Run with -Prace-demo.
 * Each retries a few seeds so a lucky clean run cannot make the point disappear.
 */
@Tag("race-demo")
class RaceDemoTest {

    @Test
    void naiveStoreCorruptsMoneyUnderContention() throws InterruptedException {
        StressHarness.Result last = null;
        boolean corrupted = false;
        for (long seed = 1; seed <= 3 && !corrupted; seed++) {
            last = StressHarness.run(new NaiveInMemoryStore(), StressHarness.Config.overdraftPressure(seed));
            corrupted = last.corrupted();
        }
        assertTrue(corrupted, "expected the naive store to corrupt money under contention");
        System.out.println("naive store corruption: before=" + last.totalBefore()
                + " after=" + last.totalAfter() + " delta=" + (last.totalAfter() - last.totalBefore())
                + " negativeSeen=" + last.negativeBalanceSeen()
                + " exactlyOnceBroken=" + !last.expectedBalances().equals(last.finalBalances()));
    }

    @Test
    void naiveStoreDoubleAppliesConcurrentDuplicateKeys() throws InterruptedException {
        boolean doubleApplied = false;
        int observed = 0;
        for (long seed = 1; seed <= 3 && !doubleApplied; seed++) {
            NaiveInMemoryStore store = new NaiveInMemoryStore();
            Account a = store.createAccount("A", 1_000_000);
            Account b = store.createAccount("B", 0);
            int threads = 16;
            String key = "dup-key-" + seed;

            AtomicInteger appliedCount = new AtomicInteger();
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            for (int t = 0; t < threads; t++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        if (store.apply(new TransferRequest(key, a.id(), b.id(), 100)) instanceof TransferOutcome.Applied) {
                            appliedCount.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            done.await();
            pool.shutdownNow();

            // One key must apply exactly once. More than one Applied is the get-then-put race.
            observed = appliedCount.get();
            doubleApplied = observed > 1;
        }
        assertTrue(doubleApplied,
                "expected the naive get-then-put idempotency to apply one key more than once, observed " + observed);
        System.out.println("naive store double-apply: one key produced " + observed + " Applied outcomes");
    }
}
