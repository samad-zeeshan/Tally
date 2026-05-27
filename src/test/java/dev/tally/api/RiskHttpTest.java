package dev.tally.api;

import dev.tally.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /accounts/{id}/risk: the sender's scores after a transfer, once per posting, newest first.
 */
class RiskHttpTest extends ApiTestHarness {

    private List<JsonValue.JsonObject> scores(String accountId) {
        assertTrue(server.fraud().awaitIdle(Duration.ofSeconds(5)), "the scorer did not drain");
        HttpResponse<String> r = get("/accounts/" + accountId + "/risk");
        assertEquals(200, r.statusCode(), r.body());
        return ((JsonValue.JsonArray) body(r).members().get("scores")).items().stream()
                .map(v -> (JsonValue.JsonObject) v).toList();
    }

    private static String str(JsonValue.JsonObject o, String key) {
        return ((JsonValue.JsonString) o.members().get(key)).value();
    }

    private static long num(JsonValue.JsonObject o, String key) {
        return ((JsonValue.JsonNumber) o.members().get(key)).value();
    }

    @Test
    void riskNeedsTheToken() {
        String a = createAccount("A", 0);
        HttpResponse<String> r = send(HttpRequest.newBuilder(base.resolve("/accounts/" + a + "/risk")).GET().build());
        assertEquals(401, r.statusCode());
    }

    @Test
    void anUnknownAccountIsNotFound() {
        HttpResponse<String> r = get("/accounts/" + UUID.randomUUID() + "/risk");
        assertEquals(404, r.statusCode());
        assertEquals("ACCOUNT_NOT_FOUND", errorCode(r));
    }

    @Test
    void theSenderGetsOneScoreWithTheRulesThatFired() {
        String a = createAccount("A", 1_000_000);
        String b = createAccount("B", 0);
        HttpResponse<String> t = transfer(a, b, 50_000, freshKey());
        assertEquals(201, t.statusCode());

        List<JsonValue.JsonObject> scores = scores(a);
        assertEquals(1, scores.size());
        JsonValue.JsonObject s = scores.getFirst();
        assertEquals(stringField(t, "id"), str(s, "transferId"));
        assertEquals(b, str(s, "counterpartyAccountId"));
        assertEquals(50_000, num(s, "amountMinor"));
        assertEquals(15, num(s, "score"));
        assertEquals(new JsonValue.JsonBool(false), s.members().get("flagged"));
        assertEquals(new JsonValue.JsonArray(List.of(new JsonValue.JsonString("round_amount"))), s.members().get("rules"));
        assertTrue(num(s, "postingId") > 0);
        assertTrue(scores(b).isEmpty(), "only the debit side is scored");
    }

    @Test
    void aReplayedTransferIsNotScoredAgain() {
        String a = createAccount("A", 10_000);
        String b = createAccount("B", 0);
        String key = freshKey();
        assertEquals(201, transfer(a, b, 100, key).statusCode());
        assertEquals("true", transfer(a, b, 100, key).headers().firstValue("Idempotency-Replayed").orElseThrow());
        assertEquals(1, scores(a).size());
        assertEquals(1, server.metrics().fraudPostings.value("scored"));
        assertEquals(0, server.metrics().fraudPostings.value("duplicate"), "a replay publishes nothing at all");
    }

    @Test
    void aRejectedTransferIsNotScored() {
        String a = createAccount("A", 100);
        String b = createAccount("B", 0);
        assertEquals(422, transfer(a, b, 5_000, freshKey()).statusCode());
        assertTrue(scores(a).isEmpty());
    }

    @Test
    void scoresAreNewestFirstAndLimited() {
        String a = createAccount("A", 10_000);
        String b = createAccount("B", 0);
        for (int amount = 101; amount <= 103; amount++) {
            assertEquals(201, transfer(a, b, amount, freshKey()).statusCode());
        }
        assertEquals(List.of(103L, 102L, 101L), scores(a).stream().map(s -> num(s, "amountMinor")).toList());
        HttpResponse<String> limited = get("/accounts/" + a + "/risk?limit=2");
        assertEquals(2, ((JsonValue.JsonArray) body(limited).members().get("scores")).items().size());
        assertEquals(40, longField(limited, "flagThreshold"));
    }

    @Test
    void aBadLimitIsA400() {
        String a = createAccount("A", 0);
        assertEquals("INVALID_LIMIT", errorCode(get("/accounts/" + a + "/risk?limit=0")));
        assertEquals("INVALID_LIMIT", errorCode(get("/accounts/" + a + "/risk?limit=abc")));
        assertFalse(get("/accounts/" + a + "/risk?limit=500").statusCode() == 400);
        assertEquals("INVALID_LIMIT", errorCode(get("/accounts/" + a + "/risk?limit=501")));
    }
}
