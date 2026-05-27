package dev.tally.api;

import dev.tally.ApiServer;
import dev.tally.fraud.FraudScoring;
import dev.tally.fraud.InMemoryScoreStore;
import dev.tally.fraud.Rules;
import dev.tally.http.RateLimiter;
import dev.tally.json.JsonValue;
import dev.tally.obs.Metrics;
import dev.tally.store.InMemoryStore;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * X-Tally-Event-Time reaches the scorer only when the replay clock is on, and it never reaches the ledger.
 */
class ReplayClockHttpTest extends ApiTestHarness {
    private static final String NIGHT = "2026-01-05T03:00:00Z";

    private void serve(boolean replayClock) {
        Metrics metrics = new Metrics();
        FraudScoring fraud = new FraudScoring(new InMemoryScoreStore(), Rules.DEFAULT,
                FraudScoring.DEFAULT_CAPACITY, replayClock, metrics);
        useServer(new ApiServer(0, new InMemoryStore(), TOKEN, null, metrics, fraud, new RateLimiter()));
    }

    private HttpResponse<String> timedTransfer(String from, String to, String eventTime) {
        String body = "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to + "\",\"amountMinor\":100}";
        return post("/transfers", body, "Idempotency-Key", freshKey(), "X-Tally-Event-Time", eventTime);
    }

    private String scoredEventAt(String accountId) {
        assertTrue(server.fraud().awaitIdle(Duration.ofSeconds(5)));
        JsonValue.JsonArray scores = (JsonValue.JsonArray) body(get("/accounts/" + accountId + "/risk")).members().get("scores");
        JsonValue.JsonObject first = (JsonValue.JsonObject) scores.items().getFirst();
        return ((JsonValue.JsonString) first.members().get("eventAt")).value();
    }

    @Test
    void withTheReplayClockTheScorerSeesTheCarriedTime() {
        serve(true);
        String a = createAccount("A", 1_000);
        String b = createAccount("B", 0);
        HttpResponse<String> r = timedTransfer(a, b, NIGHT);
        assertEquals(201, r.statusCode());
        assertEquals(NIGHT, scoredEventAt(a));
        // The ledger keeps its own clock whatever the header says.
        assertNotEquals(NIGHT, stringField(r, "createdAt"));
    }

    @Test
    void aMalformedTimeIsRefusedBeforeAnyMoneyMoves() {
        serve(true);
        String a = createAccount("A", 1_000);
        String b = createAccount("B", 0);
        HttpResponse<String> r = timedTransfer(a, b, "yesterday");
        assertEquals(400, r.statusCode());
        assertEquals("EVENT_TIME_INVALID", errorCode(r));
        assertEquals(1_000, balanceOf(a));
    }

    @Test
    void withoutTheReplayClockTheHeaderIsIgnored() {
        String a = createAccount("A", 1_000);
        String b = createAccount("B", 0);
        Instant before = Instant.now().minusSeconds(5);
        assertEquals(201, timedTransfer(a, b, NIGHT).statusCode());
        Instant scored = Instant.parse(scoredEventAt(a));
        assertTrue(scored.isAfter(before), "the default server scored at the commit time, not the header: " + scored);
    }
}
