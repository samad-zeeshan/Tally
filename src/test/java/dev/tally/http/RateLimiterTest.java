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
        // The brute-force path: the failure budget is an order of magnitude under the request budget, so
        // an address guessing tokens is stopped while a busy honest client is nowhere near its limit.
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
        // Nothing about the next request identifies it as an auth attempt; the address is throttled outright.
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
        // Two windows on, every one of those addresses has gone quiet and the sweep has run.
        advance(RateLimiter.WINDOW_MILLIS * 2);
        limiter.check("10.2.0.1");
        assertEquals(1, limiter.trackedClients(), "expired entries must not be held forever");
    }

    @Test
    void theTableIsBoundedUnderASpoofedSourceFlood() {
        // The limiter must not be the denial of service. An attacker varying its source address decides
        // how many entries exist, so the table has a hard ceiling and sheds beyond it.
        for (int i = 0; i < RateLimiter.MAX_TRACKED_CLIENTS + 500; i++) {
            limiter.check("192.168." + (i / 256 % 256) + "." + (i % 256) + ":" + i);
        }
        assertTrue(limiter.trackedClients() <= RateLimiter.MAX_TRACKED_CLIENTS,
                "tracked " + limiter.trackedClients() + " addresses, over the cap");
        // And it heals: once the flood's windows expire the table sweeps clean and new callers are served.
        advance(RateLimiter.WINDOW_MILLIS * 2);
        assertTrue(limiter.check("10.3.0.1").allowed());
    }

    @Test
    void concurrentCallersFromOneAddressGetExactlyTheBudget() throws Exception {
        // The server runs a virtual thread per exchange, so the counters are hit concurrently. A lost
        // increment here would hand an attacker extra attempts for free.
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
