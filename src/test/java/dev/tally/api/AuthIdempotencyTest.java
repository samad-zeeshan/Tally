package dev.tally.api;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Auth sits before the body is read, so a 401 can never consume or reserve an idempotency key, and it
 * beats both JSON parsing and the body cap. This is the interplay the pipeline ordering exists to get right.
 */
class AuthIdempotencyTest extends ApiTestHarness {

    private static String transferBody(String from, String to, long amount) {
        return "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to + "\",\"amountMinor\":" + amount + "}";
    }

    private HttpResponse<String> rawTransfer(String body, String key, String authorization) {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve("/transfers"))
                .POST(BodyPublishers.ofString(body))
                .header("Idempotency-Key", key);
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return send(b.build());
    }

    @Test
    void rejected401DoesNotConsumeKey() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        String key = freshKey();
        String body = transferBody(a, b, 300);

        assertEquals(401, rawTransfer(body, key, null).statusCode());   // no token

        HttpResponse<String> authed = post("/transfers", body, "Idempotency-Key", key);
        assertEquals(201, authed.statusCode());   // the key was free, so it applies
        assertEquals(700, balanceOf(a));

        HttpResponse<String> replay = post("/transfers", body, "Idempotency-Key", key);
        assertEquals(201, replay.statusCode());
        assertEquals("true", replay.headers().firstValue("Idempotency-Replayed").orElse(""));
        assertEquals(700, balanceOf(a));   // moved exactly once
    }

    @Test
    void wrongTokenDoesNotConsumeKeyEither() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        String key = freshKey();
        String body = transferBody(a, b, 250);

        assertEquals(401, rawTransfer(body, key, "Bearer wrong-token-not-real").statusCode());

        assertEquals(201, post("/transfers", body, "Idempotency-Key", key).statusCode());
        assertEquals(750, balanceOf(a));
    }

    @Test
    void authRunsBeforeBodyHandling() {
        // No token plus unparseable JSON is a 401, not a 400: auth beats the parser (order 3 < 5).
        assertEquals(401, rawTransfer("{not json", freshKey(), null).statusCode());
        // No token plus an oversized body is a 401, not a 413: auth beats the body cap (order 3 < 4).
        String huge = "{\"x\":\"" + "a".repeat(5000) + "\"}";
        assertEquals(401, rawTransfer(huge, freshKey(), null).statusCode());
    }
}
