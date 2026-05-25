package dev.tally.api;

import dev.tally.json.Json;
import dev.tally.json.JsonValue;
import dev.tally.obs.JsonFormatter;
import dev.tally.obs.LogCapture;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The access log carries the request id and the route template, and no log line ever leaks a full
 * account id, an idempotency key, or the API token.
 */
class LoggingTest extends ApiTestHarness {

    @Test
    void accessLineHasIdAndFields() {
        HttpResponse<String> r;
        List<String> lines;
        try (LogCapture capture = new LogCapture()) {
            r = post("/accounts", "{\"name\":\"Ada\"}");
            lines = capture.lines();
        }
        String id = r.headers().firstValue("X-Request-Id").orElseThrow();
        List<String> access = lines.stream().filter(l -> l.contains("path=/accounts status=201")).toList();
        assertEquals(1, access.size(), lines.toString());
        String line = access.getFirst();
        assertTrue(line.contains("INFO"), line);
        assertTrue(line.contains("req=" + id), line);
        assertTrue(line.contains("method=POST"), line);
        assertTrue(line.matches(".*ms=\\d+\\s*"), line);
    }

    @Test
    void accessLineUsesRouteTemplateNotRawPath() {
        String id = createAccount("Ada", 100);
        List<String> lines;
        try (LogCapture capture = new LogCapture()) {
            get("/accounts/" + id);
            lines = capture.lines();
        }
        assertTrue(lines.stream().anyMatch(l -> l.contains("path=/accounts/{id}")), lines.toString());
        assertTrue(lines.stream().noneMatch(l -> l.contains(id)), "the raw account id must not appear in logs");
    }

    @Test
    void logsNeverContainFullAccountIdOrToken() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        String key = freshKey();
        List<String> lines;
        try (LogCapture capture = new LogCapture()) {
            assertEquals(201, transfer(a, b, 250, key).statusCode());
            lines = capture.lines();
        }
        for (String line : lines) {
            assertFalse(line.contains(a), "full from id leaked: " + line);
            assertFalse(line.contains(b), "full to id leaked: " + line);
            assertFalse(line.contains(key), "idempotency key leaked: " + line);
            assertFalse(line.contains(TOKEN), "api token leaked: " + line);
        }
        String last4 = a.substring(a.length() - 4);
        assertTrue(lines.stream().anyMatch(l -> l.contains("..." + last4)), "expected a redacted from id in the logs");
    }

    @Test
    void aJsonAccessLineCarriesTheSameRequestIdAsTheResponseHeader() {
        HttpResponse<String> r;
        List<String> lines;
        try (LogCapture capture = new LogCapture(new JsonFormatter())) {
            r = get("/accounts");
            lines = capture.lines();
        }
        String id = r.headers().firstValue("X-Request-Id").orElseThrow();
        JsonValue.JsonObject access = lines.stream()
                .map(l -> (JsonValue.JsonObject) Json.parse(l.strip()))
                .filter(o -> o.members().get("path") instanceof JsonValue.JsonString(var p) && p.equals("/accounts"))
                .findFirst().orElseThrow(() -> new AssertionError(lines.toString()));
        assertEquals(new JsonValue.JsonString(id), access.members().get("requestId"));
        assertEquals(new JsonValue.JsonNumber(200), access.members().get("status"));
    }
}
