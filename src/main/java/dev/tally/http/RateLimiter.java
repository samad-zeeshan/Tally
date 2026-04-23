package dev.tally.http;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A per-address throttle the kernel consults before it dispatches. Two budgets, one window: a general
 * request count that keeps the write endpoints from being flooded, and a much smaller failed-auth count,
 * because one static bearer token with unlimited guesses is the brute-force path.
 *
 * The address is the socket peer, never X-Forwarded-For. A client-supplied header would let one attacker
 * mint an unlimited number of buckets, which turns the limiter itself into the memory exhaustion it is
 * supposed to prevent. A deployment behind a real proxy has to make the proxy's own limiter the front
 * line, or teach this one which hop to trust; guessing is worse than not looking.
 */
public final class RateLimiter {
    // Fixed windows, one length for both budgets, so expiry, eviction, and Retry-After are a single rule.
    // 300 requests a minute is ~5 a second sustained from one address: far above a person clicking through
    // the client (a page load is a handful of requests) and far below anything that would flood /transfers.
    public static final long WINDOW_MILLIS = 60_000;
    public static final int MAX_REQUESTS_PER_WINDOW = 300;
    // Ten wrong tokens in a minute from one address is never a real client, it is someone guessing. The
    // budget is deliberately an order of magnitude under the general one, and blowing it throttles the
    // address for the rest of the window, not just its next auth attempt.
    public static final int MAX_AUTH_FAILURES_PER_WINDOW = 10;
    // The map is bounded because an attacker who can vary its source address controls how many entries
    // exist. 10k live addresses is far more than this service will ever legitimately see at once.
    public static final int MAX_TRACKED_CLIENTS = 10_000;

    /** Allowed, or throttled with the seconds a client should wait before retrying. */
    public record Decision(boolean allowed, int retryAfterSeconds) {}

    private static final Decision ALLOWED = new Decision(true, 0);

    // Counters for one address. Mutated only under its own monitor, so contention is per address and the
    // map is never globally locked.
    private static final class Window {
        private long startMillis;
        private int requests;
        private int authFailures;

        private Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final LongSupplier clock;
    private final AtomicLong lastSweepMillis;

    public RateLimiter() {
        this(System::currentTimeMillis);
    }

    // The clock is injected so a test can roll a window without sleeping through it.
    RateLimiter(LongSupplier clock) {
        this.clock = clock;
        this.lastSweepMillis = new AtomicLong(clock.getAsLong());
    }

    /**
     * Count one request from this address and say whether to serve it. Called once per request, before
     * routing, so a throttled caller costs a map lookup and never reaches a handler or the store.
     */
    public Decision check(String client) {
        long now = clock.getAsLong();
        sweepIfDue(now);
        Window window = windows.get(client);
        if (window == null) {
            window = admit(client, now);
            if (window == null) {
                // The table is full of addresses that are all still inside their window, which means a
                // flood from many sources. Shedding the new ones is the point: the alternative is an
                // unbounded map, and running out of memory denies service to everybody, not just the
                // arrivals during the flood. Entries expire on their own, so this heals within a window.
                return new Decision(false, (int) (WINDOW_MILLIS / 1000));
            }
        }
        synchronized (window) {
            rollIfExpired(window, now);
            window.requests++;
            // The auth budget gates every request from the address, not only its next auth attempt, so a
            // guesser cannot keep probing other endpoints while it waits out the lockout.
            if (window.requests > MAX_REQUESTS_PER_WINDOW || window.authFailures >= MAX_AUTH_FAILURES_PER_WINDOW) {
                return new Decision(false, retryAfterSeconds(now, window.startMillis));
            }
            return ALLOWED;
        }
    }

    /**
     * Charge a rejected credential to this address. The kernel calls it for any 401, which is the only
     * signal available at the edge: the check itself is deliberately silent about why it failed.
     */
    public void recordAuthFailure(String client) {
        Window window = windows.get(client);
        if (window == null) {
            return;   // only reachable when the address was shed at the cap, and a shed request never ran
        }
        synchronized (window) {
            rollIfExpired(window, clock.getAsLong());
            window.authFailures++;
        }
    }

    int trackedClients() {
        return windows.size();
    }

    // Reuse one entry per address across windows rather than allocating a new one each window.
    private static void rollIfExpired(Window window, long now) {
        if (now - window.startMillis >= WINDOW_MILLIS) {
            window.startMillis = now;
            window.requests = 0;
            window.authFailures = 0;
        }
    }

    // A window for a new address, or null when the table is at capacity even after a sweep. The size
    // check and the insert are not one atomic step, so the table can overshoot by at most the number of
    // threads inserting at that instant; locking the whole map to make the bound exact would serialize
    // every request for no security gain.
    private Window admit(String client, long now) {
        if (windows.size() >= MAX_TRACKED_CLIENTS) {
            sweep(now);
            if (windows.size() >= MAX_TRACKED_CLIENTS) {
                return null;
            }
        }
        return windows.computeIfAbsent(client, unused -> new Window(now));
    }

    // Evict expired entries at most once a window, so an address that goes quiet is forgotten rather than
    // held forever, and the O(n) scan is paid once a minute instead of once a request.
    private void sweepIfDue(long now) {
        long last = lastSweepMillis.get();
        if (now - last >= WINDOW_MILLIS && lastSweepMillis.compareAndSet(last, now)) {
            sweep(now);
        }
    }

    private void sweep(long now) {
        windows.values().removeIf(window -> {
            synchronized (window) {
                return now - window.startMillis >= WINDOW_MILLIS;
            }
        });
    }

    // Round up, and never advertise zero: a Retry-After of 0 invites an immediate retry that is still throttled.
    private static int retryAfterSeconds(long now, long windowStart) {
        long remaining = WINDOW_MILLIS - (now - windowStart);
        return (int) Math.max(1, (remaining + 999) / 1000);
    }
}
