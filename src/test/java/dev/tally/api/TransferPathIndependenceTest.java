package dev.tally.api;

import dev.tally.ApiServer;
import dev.tally.core.AccountId;
import dev.tally.fraud.FraudScoring;
import dev.tally.fraud.InMemoryScoreStore;
import dev.tally.fraud.Rules;
import dev.tally.fraud.Score;
import dev.tally.fraud.ScoreStore;
import dev.tally.http.RateLimiter;
import dev.tally.obs.Metrics;
import dev.tally.store.InMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The transfer path must not wait for, or fail with, the fraud scorer (ADR-0024). Each test gives the
 * server a broken scorer and shows transfers answer as fast and as correctly as ever.
 */
class TransferPathIndependenceTest extends ApiTestHarness {
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void unstick() {
        release.countDown();
    }

    // A score store whose every call first runs the given behaviour, so the scorer is as slow or as
    // broken as the test wants.
    private static ScoreStore wrapping(Runnable before) {
        InMemoryScoreStore real = new InMemoryScoreStore();
        return new ScoreStore() {
            @Override
            public boolean insertIfAbsent(Score score) {
                before.run();
                return real.insertIfAbsent(score);
            }

            @Override
            public boolean contains(long postingId) {
                before.run();
                return real.contains(postingId);
            }

            @Override
            public List<Score> outgoingBefore(AccountId account, long beforePostingId, int limit) {
                return real.outgoingBefore(account, beforePostingId, limit);
            }

            @Override
            public List<Score> incomingSince(AccountId account, Instant since, long beforePostingId) {
                return real.incomingSince(account, since, beforePostingId);
            }
        };
    }

    private void serveWith(ScoreStore scores, int capacity) {
        Metrics metrics = new Metrics();
        FraudScoring fraud = new FraudScoring(scores, Rules.DEFAULT, capacity, false, metrics);
        useServer(new ApiServer(0, new InMemoryStore(), TOKEN, null, metrics, fraud, new RateLimiter()));
    }

    private long[] timedTransfers(String from, String to, int n) {
        long[] millis = new long[n];
        for (int i = 0; i < n; i++) {
            long start = System.nanoTime();
            assertEquals(201, transfer(from, to, 10, freshKey()).statusCode());
            millis[i] = (System.nanoTime() - start) / 1_000_000;
        }
        return millis;
    }

    @Test
    @Timeout(30)
    void aScorerThatNeverReturnsDoesNotHoldUpATransfer() {
        serveWith(wrapping(() -> await(release)), FraudScoring.DEFAULT_CAPACITY);
        String a = createAccount("A", 10_000);
        String b = createAccount("B", 0);
        timedTransfers(a, b, 30);
        assertEquals(10_000 - 300, balanceOf(a));
        assertEquals(300, balanceOf(b));
        assertEquals(0, server.metrics().fraudPostings.value("scored"), "the scorer really was stuck");
    }

    @Test
    @Timeout(30)
    void aSlowScorerDoesNotMakeTransfersSlow() {
        // One second per score. If a transfer waited on its score, each would take at least that long.
        serveWith(wrapping(() -> sleep(1_000)), FraudScoring.DEFAULT_CAPACITY);
        String a = createAccount("A", 10_000);
        String b = createAccount("B", 0);
        long[] millis = timedTransfers(a, b, 10);
        long total = 0;
        for (long m : millis) {
            assertTrue(m < 500, "a transfer took " + m + " ms behind a one-second scorer");
            total += m;
        }
        assertTrue(total < 2_000, "ten transfers took " + total + " ms, the scorer alone needs ten seconds");
    }

    @Test
    @Timeout(30)
    void aScorerThatThrowsLeavesTransfersSucceeding() {
        serveWith(wrapping(() -> {
            throw new IllegalStateException("scorer down");
        }), FraudScoring.DEFAULT_CAPACITY);
        String a = createAccount("A", 10_000);
        String b = createAccount("B", 0);
        timedTransfers(a, b, 5);
        assertTrue(server.fraud().awaitIdle(Duration.ofSeconds(5)));
        assertEquals(5, server.metrics().fraudPostings.value("failed"));
        assertEquals(50, balanceOf(b));
    }

    @Test
    @Timeout(30)
    void aFullQueueDropsScoresNotTransfers() {
        serveWith(wrapping(() -> await(release)), 1);
        String a = createAccount("A", 10_000);
        String b = createAccount("B", 0);
        timedTransfers(a, b, 10);
        assertEquals(100, balanceOf(b));
        assertTrue(server.metrics().fraudPostings.value("dropped") >= 8,
                "one event is held by the stuck scorer and one fits in the queue, the rest are dropped");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
