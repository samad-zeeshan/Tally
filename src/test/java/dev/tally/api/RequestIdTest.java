package dev.tally.api;

import dev.tally.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every response carries X-Request-Id; a valid inbound one is honored, anything else is replaced, and
 * the error envelope's requestId matches the header.
 */
class RequestIdTest extends ApiTestHarness {
    private static final Pattern UUID_FORM =
            Pattern.compile("[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}");

    private HttpResponse<String> getWithRequestId(String path, String requestId) {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).GET();
        if (requestId != null) {
            b.header("X-Request-Id", requestId);
        }
        return send(b.build());
    }

    private String responseId(HttpResponse<String> r) {
        return r.headers().firstValue("X-Request-Id").orElseThrow();
    }

    private String envelopeRequestId(HttpResponse<String> r) {
        JsonValue.JsonObject error = (JsonValue.JsonObject) body(r).members().get("error");
        return ((JsonValue.JsonString) error.members().get("requestId")).value();
    }

    @Test
    void responseCarriesGeneratedId() {
        HttpResponse<String> r = get("/accounts/" + UUID.randomUUID());   // 404, still carries the header
        assertTrue(UUID_FORM.matcher(responseId(r)).matches(), responseId(r));
    }

    @Test
    void validInboundIdWins() {
        HttpResponse<String> r = getWithRequestId("/accounts/" + UUID.randomUUID(), "client-req-0001");
        assertEquals("client-req-0001", responseId(r));
    }

    @Test
    void invalidInboundIdReplaced() {
        HttpResponse<String> r = getWithRequestId("/accounts/" + UUID.randomUUID(), "bad id!");
        assertNotEquals("bad id!", responseId(r));
        assertTrue(UUID_FORM.matcher(responseId(r)).matches());
    }

    @Test
    void errorEnvelopeCarriesRequestId() {
        HttpResponse<String> bad = post("/accounts", "{}");   // 400, authenticated
        assertEquals(400, bad.statusCode());
        assertEquals(responseId(bad), envelopeRequestId(bad));

        HttpResponse<String> unauth = send(HttpRequest.newBuilder(base.resolve("/transfers"))
                .POST(BodyPublishers.ofString("{}")).build());   // 401, no token
        assertEquals(401, unauth.statusCode());
        assertEquals(responseId(unauth), envelopeRequestId(unauth));
    }
}
