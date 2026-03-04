package dev.tally.testsupport;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.store.Store;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Fires many transfers across a small pool of accounts on many threads, then reports facts.
 *
 * It asserts nothing itself, so the race demo can assert corruption happened while the real
 * tests call assertClean. Reused against the Postgres store later.
 */
public final class StressHarness {
    private StressHarness() {}

    public record Config(int accountCount, long initialBalanceMinor, int threadCount,
                         int totalOperations, long seed, Duration timeout) {
        private static int clampThreads() {
            return Math.clamp(2L * Runtime.getRuntime().availableProcessors(), 8, 32);
        }
        public static Config standard(long seed) {
            return new Config(8, 10_000, clampThreads(), 20_000, seed, Duration.ofSeconds(30));
        }
        public static Config overdraftPressure(long seed) {
            return new Config(8, 1_000, clampThreads(), 20_000, seed, Duration.ofSeconds(30));
        }
    }

    public record AppliedTransfer(AccountId from, AccountId to, long amountMinor) {}

    public record Result(long totalBefore, long totalAfter,
                         Map<AccountId, Long> expectedBalances,
                         Map<AccountId, Long> finalBalances,
                         List<AppliedTransfer> appliedLog,
                         int operations, int appliedCount, int rejectedCount, int replayedCount,
                         boolean negativeBalanceSeen, boolean finishedInTime,
                         List<String> deadlockedThreads) {
        public boolean corrupted() {
            return totalBefore != totalAfter
                    || negativeBalanceSeen
                    || !expectedBalances.equals(finalBalances)
                    || replayedCount > 0;
        }
    }

    private record WorkerResult(List<AppliedTransfer> applied, int appliedCount, int rejectedCount,
                                int replayedCount, boolean negativeSeen) {}

    public static Result run(Store store, Config config) throws InterruptedException {
        List<AccountId> ids = new ArrayList<>();
        for (int i = 0; i < config.accountCount(); i++) {
            ids.add(store.createAccount("pool-" + i, config.initialBalanceMinor()).id());
        }
        long totalBefore = (long) config.accountCount() * config.initialBalanceMinor();

        int threads = config.threadCount();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        WorkerResult[] slots = new WorkerResult[threads];
        AtomicInteger nextName = new AtomicInteger();
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "stress-w" + nextName.getAndIncrement());
            t.setDaemon(true);   // a stuck lock() must not hang JVM exit
            return t;
        };
        ExecutorService pool = Executors.newFixedThreadPool(threads, factory);
        int base = config.totalOperations() / threads;
        int remainder = config.totalOperations() % threads;
        for (int w = 0; w < threads; w++) {
            final int worker = w;
            final int ops = base + (worker < remainder ? 1 : 0);
            pool.submit(() -> {
                try {
                    slots[worker] = runWorker(store, config, ids, worker, ops, start);
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();   // all workers pile onto the store together
        // The await timeout doubles as the deadlock detector: a real deadlock never finishes.
        boolean finishedInTime = done.await(config.timeout().toMillis(), TimeUnit.MILLISECONDS);
        List<String> deadlocked = List.of();
        if (!finishedInTime) {
            deadlocked = snapshotDeadlocked();
            pool.shutdownNow();
        } else {
            pool.shutdown();
        }

        List<AppliedTransfer> appliedLog = new ArrayList<>();
        int applied = 0;
        int rejected = 0;
        int replayed = 0;
        boolean negativeSeen = false;
        for (WorkerResult wr : slots) {
            if (wr == null) {
                continue;   // a worker that never finished (timeout)
            }
            appliedLog.addAll(wr.applied());
            applied += wr.appliedCount();
            rejected += wr.rejectedCount();
            replayed += wr.replayedCount();
            negativeSeen |= wr.negativeSeen();
        }

        // Expected balances: initial, then replay the applied log. Addition commutes, so order is moot.
        Map<AccountId, Long> expected = new HashMap<>();
        for (AccountId id : ids) {
            expected.put(id, config.initialBalanceMinor());
        }
        for (AppliedTransfer t : appliedLog) {
            expected.merge(t.from(), -t.amountMinor(), Long::sum);
            expected.merge(t.to(), t.amountMinor(), Long::sum);
        }

        Map<AccountId, Long> finalBalances = new HashMap<>();
        long totalAfter = 0;
        for (AccountId id : ids) {
            long bal = store.findAccount(id).map(Account::balanceMinor).orElse(0L);
            finalBalances.put(id, bal);
            totalAfter += bal;
            if (bal < 0) {
                negativeSeen = true;
            }
        }

        return new Result(totalBefore, totalAfter, expected, finalBalances, appliedLog,
                config.totalOperations(), applied, rejected, replayed, negativeSeen, finishedInTime, deadlocked);
    }

    private static WorkerResult runWorker(Store store, Config config, List<AccountId> ids,
                                          int worker, int ops, CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new WorkerResult(List.of(), 0, 0, 0, false);
        }
        // The seed pins the workload (which transfers, which amounts), not the interleaving.
        Random random = new Random(config.seed() + worker);
        List<AppliedTransfer> applied = new ArrayList<>();
        int appliedCount = 0;
        int rejectedCount = 0;
        int replayedCount = 0;
        boolean negativeSeen = false;
        int n = config.accountCount();
        long maxAmount = config.initialBalanceMinor() / 10;

        for (int i = 0; i < ops; i++) {
            int fromIdx = random.nextInt(n);
            int toIdx = random.nextInt(n);
            while (toIdx == fromIdx) {
                toIdx = random.nextInt(n);
            }
            long amount = random.nextLong(1, maxAmount + 1);
            AccountId from = ids.get(fromIdx);
            AccountId to = ids.get(toIdx);
            String key = "stress-" + config.seed() + "-w" + worker + "-op" + i;   // unique, so no replay expected
            switch (store.apply(new TransferRequest(key, from, to, amount))) {
                case TransferOutcome.Applied _ -> {
                    applied.add(new AppliedTransfer(from, to, amount));
                    appliedCount++;
                }
                case TransferOutcome.InsufficientFunds _ -> rejectedCount++;
                // Both are anomalies with unique keys, and assertClean requires replayedCount == 0.
                case TransferOutcome.Replayed _ -> replayedCount++;
                case TransferOutcome.KeyConflict _ -> replayedCount++;
                case TransferOutcome.UnknownAccount _ -> throw new IllegalStateException("pool accounts exist");
                case TransferOutcome.ReservedAccount _ -> throw new IllegalStateException("the workload never names world");
            }
            if (store.findAccount(from).map(Account::balanceMinor).orElse(0L) < 0) {
                negativeSeen = true;   // catch a transient negative, not just the final scan
            }
        }
        return new WorkerResult(applied, appliedCount, rejectedCount, replayedCount, negativeSeen);
    }

    // findDeadlockedThreads, not findMonitorDeadlockedThreads, because it covers ReentrantLock's
    // ownable synchronizers, which the monitor variant misses.
    private static List<String> snapshotDeadlocked() {
        long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        if (ids == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (var info : ManagementFactory.getThreadMXBean().getThreadInfo(ids)) {
            if (info != null) {
                names.add(info.getThreadName());
            }
        }
        return names;
    }

    public static void assertClean(Result r) {
        assertTrue(r.finishedInTime(), "did not finish in time; deadlocked threads: " + r.deadlockedThreads());
        assertEquals(r.totalBefore(), r.totalAfter(), "conservation broken: pool total changed");
        assertFalse(r.negativeBalanceSeen(), "an account balance went below zero");
        assertEquals(r.expectedBalances(), r.finalBalances(),
                "exactly-once broken: replaying the applied log does not reproduce the final balances");
        assertEquals(r.operations(), r.appliedCount() + r.rejectedCount() + r.replayedCount(), "an operation was lost");
        assertEquals(0, r.replayedCount(), "unexpected replay or key conflict with unique keys");
    }
}
