package dev.tally.http;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;

/**
 * A per-address throttle the kernel consults before it dispatches. Two budgets share one window: a
 * request count, and a much smaller failed-auth count, because one static bearer token invites guessing.
 *
 * The address is the socket peer, never X-Forwarded-For. A client-supplied header would let one attacker
 * mint unlimited buckets, turning this into the memory exhaustion it exists to prevent. Behind a real
 * proxy the proxy's own limiter has to be the front line.
 */
public final class RateLimiter {
    // 300 a minute is ~5 a second sustained from one address, far above a person clicking through the
    // client and far below anything that would flood /transfers.
    public static final long WINDOW_MILLIS = 60_000;
    public static final int MAX_REQUESTS_PER_WINDOW = 300;
    // Ten wrong tokens in a minute is never a real client. Blowing this budget throttles the address for
    // the rest of the window rather than only its next auth attempt, so a guesser cannot probe elsewhere.
    public static final int MAX_AUTH_FAILURES_PER_WINDOW = 10;
    public static final int MAX_TRACKED_CLIENTS = 10_000;

    /** Allowed, or throttled with the seconds a client should wait before retrying. */
    public record Decision(boolean allowed, int retryAfterSeconds) {}

    private static final Decision ALLOWED = new Decision(true, 0);

    // Mutated only under its own monitor, so contention is per address and the map is never locked whole.
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
    private final int maxRequestsPerWindow;

    public RateLimiter() {
        this(System::currentTimeMillis);
    }

    public RateLimiter(int maxRequestsPerWindow) {
        this(System::currentTimeMillis, maxRequestsPerWindow);
    }

    // The clock is injected so a test can roll a window without sleeping through it.
    RateLimiter(LongSupplier clock) {
        this(clock, MAX_REQUESTS_PER_WINDOW);
    }

    RateLimiter(LongSupplier clock, int maxRequestsPerWindow) {
        this.clock = clock;
        this.lastSweepMillis = new AtomicLong(clock.getAsLong());
        this.maxRequestsPerWindow = maxRequestsPerWindow;
    }

    // Only the request budget is configurable. The offline fraud evaluation replays thousands of transfers
    // from one address and needs it raised; the failed-auth budget has no such reason and stays fixed.
    public static RateLimiter fromEnv(UnaryOperator<String> getenv) {
        String raw = getenv.apply("TALLY_RATE_LIMIT_PER_MINUTE");
        if (raw == null) {
            return new RateLimiter();
        }
        int max;
        try {
            max = Integer.parseInt(raw.strip());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("TALLY_RATE_LIMIT_PER_MINUTE must be a whole number, got " + raw);
        }
        if (max < 1) {
            throw new IllegalStateException("TALLY_RATE_LIMIT_PER_MINUTE must be at least 1, got " + raw);
        }
        return new RateLimiter(max);
    }

    public int maxRequestsPerWindow() {
        return maxRequestsPerWindow;
    }

    /** Count one request from this address and say whether to serve it. */
    public Decision check(String client) {
        long now = clock.getAsLong();
        sweepIfDue(now);
        Window window = windows.get(client);
        if (window == null) {
            window = admit(client, now);
            if (window == null) {
                // The table is full and nothing in it has expired, so this is a flood from many sources.
                // Shedding arrivals beats an unbounded map, which would deny service to everybody, and it
                // heals on its own once those windows run out.
                return new Decision(false, (int) (WINDOW_MILLIS / 1000));
            }
        }
        synchronized (window) {
            rollIfExpired(window, now);
            window.requests++;
            if (window.requests > maxRequestsPerWindow || window.authFailures >= MAX_AUTH_FAILURES_PER_WINDOW) {
                return new Decision(false, retryAfterSeconds(now, window.startMillis));
            }
            return ALLOWED;
        }
    }

    /** Charge a rejected credential to this address. The kernel calls it for any 401. */
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

    private static void rollIfExpired(Window window, long now) {
        if (now - window.startMillis >= WINDOW_MILLIS) {
            window.startMillis = now;
            window.requests = 0;
            window.authFailures = 0;
        }
    }

    // The size check and the insert are not one step, so the table can overshoot by however many threads
    // are inserting at that instant. Locking the map to make the bound exact would serialize every
    // request for no security gain.
    private Window admit(String client, long now) {
        if (windows.size() >= MAX_TRACKED_CLIENTS) {
            sweep(now);
            if (windows.size() >= MAX_TRACKED_CLIENTS) {
                return null;
            }
        }
        return windows.computeIfAbsent(client, unused -> new Window(now));
    }

    // At most once a window, so the scan over every entry costs a minute rather than a request.
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

    // Never advertise zero: a Retry-After of 0 invites an immediate retry that is still throttled.
    private static int retryAfterSeconds(long now, long windowStart) {
        long remaining = WINDOW_MILLIS - (now - windowStart);
        return (int) Math.max(1, (remaining + 999) / 1000);
    }
}
