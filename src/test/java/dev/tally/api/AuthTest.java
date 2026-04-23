package dev.tally.api;

import org.junit.jupiter.api.Test;

import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The auth matrix: every route needs a bearer token, /health excepted.
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
    void readsRequireToken() {
        String id = createAccount("Ada", 1000);   // created with the token
        // A balance and a statement are account data. Reads used to be open as a demo convenience; they
        // are not, because "anyone can list every account and read every statement" is not a small gap.
        for (String path : new String[]{"/accounts", "/accounts/" + id, "/accounts/" + id + "/statement"}) {
            HttpResponse<String> r = rawGet(path, null);
            assertEquals(401, r.statusCode(), path);
            assertEquals("AUTH_MISSING", errorCode(r), path);
        }
        // The same reads succeed with the token, so the client that already sends it keeps working.
        assertEquals(200, rawGet("/accounts", "Bearer " + TOKEN).statusCode());
        assertEquals(200, rawGet("/accounts/" + id, "Bearer " + TOKEN).statusCode());
        assertEquals(200, rawGet("/accounts/" + id + "/statement", "Bearer " + TOKEN).statusCode());
    }

    @Test
    void healthStaysOpenWithoutToken() {
        // A liveness probe must not need a credential, and the container healthcheck has none.
        assertEquals(200, rawGet("/health", null).statusCode());
    }

    @Test
    void schemeIsCaseInsensitiveAndShapeIsStrict() {
        assertEquals(201, rawPost("/accounts", ACCOUNT_BODY, "bearer " + TOKEN).statusCode());   // scheme ci
        assertEquals(401, rawPost("/accounts", ACCOUNT_BODY, "Basic " + TOKEN).statusCode());     // wrong scheme
        assertEquals(401, rawPost("/accounts", ACCOUNT_BODY, "Bearer").statusCode());             // no token
        assertEquals(401, rawPost("/accounts", ACCOUNT_BODY, "Bearer  " + TOKEN).statusCode());   // two spaces
    }
}
