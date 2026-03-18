package dev.tally.api;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The write/read auth matrix: writes and reconciliation need a bearer token, reads do not.
 */
class AuthTest extends ApiTestHarness {
    private static final String ACCOUNT_BODY = "{\"name\":\"Ada\",\"openingBalanceMinor\":1000}";

    private HttpResponse<String> rawPost(String path, String body, String authorization) {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).POST(BodyPublishers.ofString(body));
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return send(b.build());
    }

    private HttpResponse<String> rawGet(String path, String authorization) {
        HttpRequest.Builder b = HttpRequest.newBuilder(base.resolve(path)).GET();
        if (authorization != null) {
            b.header("Authorization", authorization);
        }
        return send(b.build());
    }

    @Test
    void postTransfersWithoutTokenIs401() {
        HttpResponse<String> r = rawPost("/transfers", "{}", null);
        assertEquals(401, r.statusCode());
        assertEquals("AUTH_MISSING", errorCode(r));
        assertEquals("Bearer realm=\"tally\"", r.headers().firstValue("WWW-Authenticate").orElseThrow());
    }

    @Test
    void postTransfersWithWrongTokenIs401() {
        HttpResponse<String> r = rawPost("/transfers", "{}", "Bearer not-the-real-token");
        assertEquals(401, r.statusCode());
        assertEquals("AUTH_INVALID", errorCode(r));
        assertTrue(r.headers().firstValue("WWW-Authenticate").orElseThrow().contains("error=\"invalid_token\""));
    }

    @Test
    void postAccountsRequiresToken() {
        HttpResponse<String> r = rawPost("/accounts", ACCOUNT_BODY, null);
        assertEquals(401, r.statusCode());
        assertEquals("AUTH_MISSING", errorCode(r));
    }

    @Test
    void reconciliationRequiresToken() {
        HttpResponse<String> r = rawGet("/reconciliation", null);
        assertEquals(401, r.statusCode());
        assertEquals("AUTH_MISSING", errorCode(r));
    }

    @Test
    void correctTokenAllowsWrite() {
        assertEquals(201, rawPost("/accounts", ACCOUNT_BODY, "Bearer " + TOKEN).statusCode());
    }

    @Test
    void readsAreOpenWithoutToken() {
        String id = createAccount("Ada", 1000);   // created with the token
        assertEquals(200, rawGet("/accounts/" + id, null).statusCode());
        assertEquals(200, rawGet("/accounts/" + id + "/statement", null).statusCode());
    }

    @Test
    void schemeIsCaseInsensitiveAndShapeIsStrict() {
        assertEquals(201, rawPost("/accounts", ACCOUNT_BODY, "bearer " + TOKEN).statusCode());   // scheme ci
        assertEquals(401, rawPost("/accounts", ACCOUNT_BODY, "Basic " + TOKEN).statusCode());     // wrong scheme
        assertEquals(401, rawPost("/accounts", ACCOUNT_BODY, "Bearer").statusCode());             // no token
        assertEquals(401, rawPost("/accounts", ACCOUNT_BODY, "Bearer  " + TOKEN).statusCode());   // two spaces
    }
}
