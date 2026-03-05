package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.testsupport.NaiveInMemoryStore;
import dev.tally.testsupport.StressHarness;
import dev.tally.testsupport.UnorderedLockStore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    @Test
    void unorderedDoubleLockingDeadlocks() throws InterruptedException {
        UnorderedLockStore store = new UnorderedLockStore();
        Account a = store.createAccount("A", 1_000_000);
        Account b = store.createAccount("B", 1_000_000);
        CyclicBarrier barrier = new CyclicBarrier(2);
        Thread t1 = new Thread(() -> poundUnordered(store, a.id(), b.id(), barrier), "unordered-1");
        Thread t2 = new Thread(() -> poundUnordered(store, b.id(), a.id(), barrier), "unordered-2");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        // findDeadlockedThreads covers ReentrantLock's ownable synchronizers. Poll up to 15 s.
        var mx = ManagementFactory.getThreadMXBean();
        long[] deadlocked = null;
        for (int i = 0; i < 300 && deadlocked == null; i++) {
            Thread.sleep(50);
            deadlocked = mx.findDeadlockedThreads();
        }
        assertNotNull(deadlocked, "expected unordered double-locking to deadlock");
        System.out.println("unordered locks deadlocked " + deadlocked.length + " threads");

        t1.interrupt();   // lockInterruptibly lets the stuck threads unwind so the JVM is left clean
        t2.interrupt();
        t1.join(2_000);
        t2.join(2_000);
    }

    private static void poundUnordered(UnorderedLockStore store, AccountId from, AccountId to, CyclicBarrier barrier) {
        try {
            for (int i = 0; i < 100_000; i++) {
                barrier.await();
                store.apply(new TransferRequest("pound-" + i, from, to, 1));
            }
        } catch (InterruptedException | BrokenBarrierException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException interruptedMidLock) {
            // the store wraps an interrupt during lockInterruptibly; stop the loop
        }
    }
}
