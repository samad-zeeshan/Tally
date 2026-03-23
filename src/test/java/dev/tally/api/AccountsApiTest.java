package dev.tally.api;

import dev.tally.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountsApiTest extends ApiTestHarness {

    @Test
    void createsAccountWithZeroDefault() {
        HttpResponse<String> r = post("/accounts", "{\"name\":\"alice\"}");
        assertEquals(201, r.statusCode());
        assertFalse(stringField(r, "id").isBlank());
        assertEquals("alice", stringField(r, "name"));
        assertEquals(0, longField(r, "balanceMinor"));
        assertDoesNotThrow(() -> Instant.parse(stringField(r, "createdAt")));
        assertEquals("/accounts/" + stringField(r, "id"), r.headers().firstValue("Location").orElseThrow());
    }

    @Test
    void createsAccountWithOpeningBalance() {
        HttpResponse<String> r = post("/accounts", "{\"name\":\"bob\",\"openingBalanceMinor\":5000}");
        assertEquals(201, r.statusCode());
        assertEquals(5000, longField(r, "balanceMinor"));
    }

    @Test
    void getReturnsCreatedAccount() {
        String id = createAccount("carol", 700);
        HttpResponse<String> r = get("/accounts/" + id);
        assertEquals(200, r.statusCode());
        assertEquals(id, stringField(r, "id"));
        assertEquals("carol", stringField(r, "name"));
        assertEquals(700, longField(r, "balanceMinor"));
    }

    @Test
    void rejectsMissingName() {
        HttpResponse<String> r = post("/accounts", "{}");
        assertEquals(400, r.statusCode());
        assertEquals("NAME_REQUIRED", errorCode(r));
        assertEquals("name", errorField(r));
    }

    @Test
    void rejectsBlankName() {
        // A blank name is present and a string, so it clears NAME_REQUIRED and fails the length rule.
        HttpResponse<String> r = post("/accounts", "{\"name\":\"\"}");
        assertEquals(400, r.statusCode());
        assertEquals("NAME_LENGTH", errorCode(r));
    }

    @Test
    void rejectsWrongTypeName() {
        HttpResponse<String> r = post("/accounts", "{\"name\":42}");
        assertEquals(400, r.statusCode());
        assertEquals("NAME_REQUIRED", errorCode(r));
    }

    @Test
    void rejectsNegativeOpeningBalance() {
        HttpResponse<String> r = post("/accounts", "{\"name\":\"x\",\"openingBalanceMinor\":-1}");
        assertEquals(400, r.statusCode());
        assertEquals("OPENING_BALANCE_NEGATIVE", errorCode(r));
        assertEquals("openingBalanceMinor", errorField(r));
    }

    @Test
    void rejectsWrongTypeOpeningBalance() {
        HttpResponse<String> r = post("/accounts", "{\"name\":\"x\",\"openingBalanceMinor\":\"100\"}");
        assertEquals(400, r.statusCode());
        assertEquals("OPENING_BALANCE_NOT_INTEGER", errorCode(r));
    }

    @Test
    void rejectsUnknownField() {
        HttpResponse<String> r = post("/accounts", "{\"name\":\"a\",\"x\":1}");
        assertEquals(400, r.statusCode());
        assertEquals("UNKNOWN_FIELD", errorCode(r));
        assertEquals("x", errorField(r));
    }

    @Test
    void rejectsNonObjectBody() {
        HttpResponse<String> r = post("/accounts", "[1]");
        assertEquals(400, r.statusCode());
        assertEquals("BODY_NOT_OBJECT", errorCode(r));
    }

    @Test
    void listAccountsReturnsCreatedAccountsWithoutWorld() {
        createAccount("alice", 1000);
        createAccount("bob", 0);
        HttpResponse<String> r = get("/accounts");   // open, no token needed
        assertEquals(200, r.statusCode());
        JsonValue.JsonArray accounts = (JsonValue.JsonArray) body(r).members().get("accounts");
        List<JsonValue> items = accounts.items();
        assertEquals(2, items.size());   // world is never listed
        assertEquals("alice", name(items.get(0)));
        assertEquals("bob", name(items.get(1)));
    }

    private static String name(JsonValue account) {
        return ((JsonValue.JsonString) ((JsonValue.JsonObject) account).members().get("name")).value();
    }

    @Test
    void getUnknownAccountReturns404() {
        HttpResponse<String> r = get("/accounts/" + UUID.randomUUID());
        assertEquals(404, r.statusCode());
        assertEquals("ACCOUNT_NOT_FOUND", errorCode(r));
    }

    @Test
    void deleteOnAccountReturns405() {
        String id = createAccount("d", 0);
        HttpResponse<String> r = method("DELETE", "/accounts/" + id);
        assertEquals(405, r.statusCode());
        assertTrue(r.headers().firstValue("Allow").orElseThrow().contains("GET"));
    }
}
