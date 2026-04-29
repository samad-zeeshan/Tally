package dev.tally.http;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The limiter's own rules, on a clock the test controls so a window can roll without sleeping through it.
 */
class RateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final RateLimiter limiter = new RateLimiter(now::get);

    private void advance(long millis) {
        now.addAndGet(millis);
    }

    private int allowedInARow(String client, int attempts) {
        int allowed = 0;
        for (int i = 0; i < attempts; i++) {
            if (limiter.check(client).allowed()) {
                allowed++;
            }
        }
        return allowed;
    }

    @Test
    void allowsUpToTheRequestBudgetThenThrottles() {
        assertEquals(RateLimiter.MAX_REQUESTS_PER_WINDOW,
                allowedInARow("10.0.0.1", RateLimiter.MAX_REQUESTS_PER_WINDOW));
        RateLimiter.Decision over = limiter.check("10.0.0.1");
        assertFalse(over.allowed());
        assertTrue(over.retryAfterSeconds() > 0, "a throttled caller must be told how long to wait");
        assertTrue(over.retryAfterSeconds() <= RateLimiter.WINDOW_MILLIS / 1000);
    }

    @Test
    void aFewFailedTokensThrottleTheAddressLongBeforeTheRequestBudget() {
        // A guesser is stopped while a busy honest client is nowhere near its own limit.
        for (int i = 0; i < RateLimiter.MAX_AUTH_FAILURES_PER_WINDOW; i++) {
            assertTrue(limiter.check("10.0.0.2").allowed(), "attempt " + i + " is still within budget");
            limiter.recordAuthFailure("10.0.0.2");
        }
        assertFalse(limiter.check("10.0.0.2").allowed());
        assertTrue(RateLimiter.MAX_AUTH_FAILURES_PER_WINDOW * 10 <= RateLimiter.MAX_REQUESTS_PER_WINDOW);
    }

    @Test
    void theAuthLockoutCoversEveryEndpointNotJustTheNextAuthAttempt() {
        for (int i = 0; i < RateLimiter.MAX_AUTH_FAILURES_PER_WINDOW; i++) {
            limiter.check("10.0.0.3");
            limiter.recordAuthFailure("10.0.0.3");
        }
        assertFalse(limiter.check("10.0.0.3").allowed(), "a locked-out address cannot go probing elsewhere");
    }

    @Test
    void oneAddressCannotThrottleAnother() {
        allowedInARow("10.0.0.4", RateLimiter.MAX_REQUESTS_PER_WINDOW + 5);
        assertFalse(limiter.check("10.0.0.4").allowed());
        assertTrue(limiter.check("10.0.0.5").allowed(), "budgets are per address");
    }

    @Test
    void theWindowRollsAndTheAddressIsServedAgain() {
        allowedInARow("10.0.0.6", RateLimiter.MAX_REQUESTS_PER_WINDOW);
        limiter.recordAuthFailure("10.0.0.6");
        assertFalse(limiter.check("10.0.0.6").allowed());
        advance(RateLimiter.WINDOW_MILLIS);
        assertTrue(limiter.check("10.0.0.6").allowed(), "both counters clear when the window rolls");
    }

    @Test
    void retryAfterCountsDownWithinTheWindow() {
        allowedInARow("10.0.0.7", RateLimiter.MAX_REQUESTS_PER_WINDOW);
        int atStart = limiter.check("10.0.0.7").retryAfterSeconds();
        advance(RateLimiter.WINDOW_MILLIS / 2);
        int halfway = limiter.check("10.0.0.7").retryAfterSeconds();
        assertTrue(halfway < atStart, halfway + " should be under " + atStart);
        assertTrue(halfway >= 1, "never advertise a zero-second wait");
    }

    @Test
    void quietAddressesAreEvictedRatherThanAccumulating() {
        for (int i = 0; i < 500; i++) {
            limiter.check("10.1." + (i / 256) + "." + (i % 256));
        }
        assertEquals(500, limiter.trackedClients());
        // Two windows, because the sweep runs at most once a window and the check below has to be past it.
        advance(RateLimiter.WINDOW_MILLIS * 2);
        limiter.check("10.2.0.1");
        assertEquals(1, limiter.trackedClients(), "expired entries must not be held forever");
    }

    @Test
    void theTableIsBoundedUnderASpoofedSourceFlood() {
        // The limiter must not become the denial of service it is there to stop.
        for (int i = 0; i < RateLimiter.MAX_TRACKED_CLIENTS + 500; i++) {
            limiter.check("192.168." + (i / 256 % 256) + "." + (i % 256) + ":" + i);
        }
        assertTrue(limiter.trackedClients() <= RateLimiter.MAX_TRACKED_CLIENTS,
                "tracked " + limiter.trackedClients() + " addresses, over the cap");
        // And it heals on its own once the flood's windows expire, with no operator involved.
        advance(RateLimiter.WINDOW_MILLIS * 2);
        assertTrue(limiter.check("10.3.0.1").allowed());
    }

    @Test
    void concurrentCallersFromOneAddressGetExactlyTheBudget() throws Exception {
        // A virtual thread per exchange means the counters are hit concurrently, and a lost increment
        // would hand a guesser free attempts.
        int threads = 16;
        int perThread = 100;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    if (limiter.check("10.9.9.9").allowed()) {
                        allowed.incrementAndGet();
                    }
                }
                return null;
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get();
        }
        pool.shutdownNow();
        assertEquals(RateLimiter.MAX_REQUESTS_PER_WINDOW, allowed.get(),
                "exactly the budget, no more from a race and no fewer");
    }
}
