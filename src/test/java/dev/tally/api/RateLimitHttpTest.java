package dev.tally.api;

import dev.tally.http.RateLimiter;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The throttle over real HTTP: a token guesser is cut off by address, and the refusal is the ordinary
 * error envelope with a Retry-After a client can act on.
 */
class RateLimitHttpTest extends ApiTestHarness {

    private HttpResponse<String> guess(String token) {
        return send(HttpRequest.newBuilder(base.resolve("/transfers"))
                .header("Authorization", "Bearer " + token)
                .POST(BodyPublishers.ofString("{}")).build());
    }

    @Test
    void repeatedWrongTokensEarnA429WithRetryAfter() {
        for (int i = 0; i < RateLimiter.MAX_AUTH_FAILURES_PER_WINDOW; i++) {
            assertEquals(401, guess("wrong-token-attempt-" + i).statusCode(), "attempt " + i);
        }
        HttpResponse<String> throttled = guess("wrong-token-one-too-many");
        assertEquals(429, throttled.statusCode());
        assertEquals("RATE_LIMITED", errorCode(throttled));
        int retryAfter = Integer.parseInt(throttled.headers().firstValue("Retry-After").orElseThrow());
        assertTrue(retryAfter > 0 && retryAfter <= RateLimiter.WINDOW_MILLIS / 1000, "Retry-After " + retryAfter);
    }

    @Test
    void theLockoutIsByAddressAndCoversTheValidTokenToo() {
        for (int i = 0; i < RateLimiter.MAX_AUTH_FAILURES_PER_WINDOW; i++) {
            assertEquals(401, guess("wrong-token-attempt-" + i).statusCode());
        }
        // Even the real token is refused, which is the point: the throttle is on the address.
        assertEquals(429, post("/accounts", "{\"name\":\"Ada\"}").statusCode());
        assertEquals(429, get("/accounts").statusCode());
    }

    @Test
    void aThrottledRequestNeverReachesTheStore() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        for (int i = 0; i < RateLimiter.MAX_AUTH_FAILURES_PER_WINDOW; i++) {
            guess("wrong-token-attempt-" + i);
        }
        String key = freshKey();
        assertEquals(429, transfer(a, b, 250, key).statusCode());
        // The key is still free, because the limiter runs ahead of the handler that would claim it. Same
        // ordering argument as auth, one step earlier.
        assertEquals(429, get("/accounts/" + a).statusCode());
    }

    @Test
    void theErrorEnvelopeIsTheStandardShape() {
        for (int i = 0; i < RateLimiter.MAX_AUTH_FAILURES_PER_WINDOW; i++) {
            guess("wrong-token-attempt-" + i);
        }
        HttpResponse<String> throttled = guess("one-more");
        assertEquals("too many requests, slow down and retry later", errorMessage(throttled));
        assertTrue(throttled.headers().firstValue("X-Request-Id").isPresent());
        assertTrue(throttled.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
    }

    // Under Kubernetes the kubelet probes from the node address, which a NodePort can share with every
    // browser behind it. A throttled probe would pull the pod out of service, so /health is never counted.
    @Test
    void healthStaysUpForAThrottledAddress() {
        for (int i = 0; i < RateLimiter.MAX_AUTH_FAILURES_PER_WINDOW; i++) {
            guess("wrong-token-attempt-" + i);
        }
        assertEquals(429, get("/accounts").statusCode());
        HttpResponse<String> health = send(HttpRequest.newBuilder(base.resolve("/health")).GET().build());
        assertEquals(200, health.statusCode());
    }
}
