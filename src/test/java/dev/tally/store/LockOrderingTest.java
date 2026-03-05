package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.TransferRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The ordering helper, and liveness proofs: opposing and ring transfers complete because the
 * fixed lock order cannot form a cycle. A deadlock would show as the @Timeout firing.
 */
class LockOrderingTest {

    @Test
    void lowerReturnsTheUuidThatComparesFirst() {
        AccountId zero = new AccountId(new UUID(0L, 0L));
        AccountId highBit = new AccountId(new UUID(Long.MIN_VALUE, 0L));   // signed-least, not the nil UUID
        AccountId mid = new AccountId(new UUID(5L, 0L));

        assertEquals(highBit, AccountLocks.lower(highBit, zero));
        assertEquals(highBit, AccountLocks.lower(zero, highBit));   // order-independent
        assertEquals(zero, AccountLocks.lower(zero, mid));
        assertEquals(highBit, AccountLocks.lower(mid, highBit));
    }

    @Test
    @Timeout(10)
    void opposingTransfersCompleteWithoutDeadlock() throws InterruptedException {
        InMemoryStore store = new InMemoryStore();
        Account a = store.createAccount("A", 1_000_000);
        Account b = store.createAccount("B", 1_000_000);
        int rounds = 2_000;
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread t1 = new Thread(() -> pound(store, a.id(), b.id(), rounds, "ab", barrier, failure));
        Thread t2 = new Thread(() -> pound(store, b.id(), a.id(), rounds, "ba", barrier, failure));
        t1.start();
        t2.start();
        t1.join();
        t2.join();

        assertNull(failure.get(), "a worker failed");
        long aBal = store.findAccount(a.id()).orElseThrow().balanceMinor();
        long bBal = store.findAccount(b.id()).orElseThrow().balanceMinor();
        assertEquals(2_000_000, aBal + bBal, "conservation");
        assertEquals(1_000_000, aBal, "each account sent and received the same total");
        assertEquals(1_000_000, bBal);
    }

    @Test
    @Timeout(10)
    void ringOfTransfersCompletesWithoutDeadlock() throws InterruptedException {
        InMemoryStore store = new InMemoryStore();
        int n = 8;
        List<AccountId> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(store.createAccount("r" + i, 1_000_000).id());
        }
        int rounds = 1_000;
        CyclicBarrier barrier = new CyclicBarrier(n);   // all threads move at once, so every lock is contended
        AtomicReference<Throwable> failure = new AtomicReference<>();

        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            AccountId from = ids.get(i);
            AccountId to = ids.get((i + 1) % n);
            threads.add(new Thread(() -> pound(store, from, to, rounds, "ring" + from.value(), barrier, failure)));
        }
        threads.forEach(Thread::start);
        for (Thread t : threads) {
            t.join();
        }

        assertNull(failure.get(), "a worker failed");
        long total = 0;
        for (AccountId id : ids) {
            long bal = store.findAccount(id).orElseThrow().balanceMinor();
            assertEquals(1_000_000, bal, "each account sent and received the same total");
            total += bal;
        }
        assertEquals((long) n * 1_000_000, total, "conservation across the ring");
    }

    // Each round the barrier aligns the threads, then one transfer fires, so opposing directions
    // maximize the chance of a bad interleaving. With ordered locks it always completes.
    private static void pound(InMemoryStore store, AccountId from, AccountId to, int rounds,
                              String tag, CyclicBarrier barrier, AtomicReference<Throwable> failure) {
        try {
            for (int i = 0; i < rounds; i++) {
                barrier.await();
                store.apply(new TransferRequest(tag + "-" + i, from, to, 1));
            }
        } catch (InterruptedException | BrokenBarrierException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            failure.compareAndSet(null, t);
        }
    }
}
