package dev.tally.api;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GET /metrics over real HTTP: token required, Prometheus text, and each listed series moves when the
 * thing it counts happens.
 */
class MetricsHttpTest extends ApiTestHarness {

    private String metricsText() {
        HttpResponse<String> r = get("/metrics");
        assertEquals(200, r.statusCode());
        return r.body();
    }

    private static long sample(String text, String series) {
        return Arrays.stream(text.split("\n"))
                .filter(l -> l.startsWith(series + " "))
                .mapToLong(l -> Long.parseLong(l.substring(series.length() + 1)))
                .findFirst()
                .orElse(0);
    }

    @Test
    void metricsNeedTheToken() {
        HttpResponse<String> r = send(HttpRequest.newBuilder(base.resolve("/metrics")).GET().build());
        assertEquals(401, r.statusCode());
    }

    @Test
    void metricsAreServedAsPrometheusText() {
        HttpResponse<String> r = get("/metrics");
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElseThrow().startsWith("text/plain; version=0.0.4"));
        for (String series : new String[] {"tally_http_requests_total", "tally_http_request_duration_seconds",
                "tally_transfers_total", "tally_reconciliation_duration_seconds", "tally_db_pool_wait_seconds",
                "tally_rate_limited_total"}) {
            assertTrue(r.body().contains("# TYPE " + series + " "), series + " missing from\n" + r.body());
        }
    }

    @Test
    void transfersAreCountedByOutcome() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        String key = freshKey();
        assertEquals(201, transfer(a, b, 250, key).statusCode());
        assertEquals(201, transfer(a, b, 250, key).statusCode());   // the replay
        assertEquals(422, transfer(a, b, 5_000, freshKey()).statusCode());
        String text = metricsText();
        assertEquals(1, sample(text, "tally_transfers_total{outcome=\"applied\"}"));
        assertEquals(1, sample(text, "tally_transfers_total{outcome=\"replayed\"}"));
        assertEquals(1, sample(text, "tally_transfers_total{outcome=\"rejected\"}"));
    }

    @Test
    void requestsAreLabelledByRouteTemplateNeverTheRawPath() {
        String id = createAccount("Ada", 100);
        get("/accounts/" + id);
        String text = metricsText();
        assertEquals(1, sample(text,
                "tally_http_requests_total{method=\"GET\",route=\"/accounts/{id}\",status=\"200\"}"));
        assertEquals(1, sample(text,
                "tally_http_request_duration_seconds_count{method=\"GET\",route=\"/accounts/{id}\"}"));
        assertFalse(text.contains(id), "an account id must never become a label value");
    }

    @Test
    void unknownPathsShareOneRouteLabel() {
        get("/no/such/" + "x".repeat(40));
        assertEquals(1, sample(metricsText(),
                "tally_http_requests_total{method=\"GET\",route=\"unmatched\",status=\"404\"}"));
    }

    @Test
    void reconciliationIsTimed() {
        assertEquals(200, get("/reconciliation").statusCode());
        assertEquals(1, sample(metricsText(), "tally_reconciliation_duration_seconds_count"));
    }

    @Test
    void throttledRequestsAreCounted() {
        for (int i = 0; i <= 10; i++) {
            send(HttpRequest.newBuilder(base.resolve("/transfers"))
                    .header("Authorization", "Bearer wrong-token-" + i)
                    .POST(BodyPublishers.ofString("{}")).build());
        }
        // Read from the object: this address is now throttled, /metrics included.
        assertEquals(1, server.metrics().rateLimited.value());
    }
}
