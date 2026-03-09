package dev.tally.api;

import dev.tally.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatementApiTest extends ApiTestHarness {

    private static final String WORLD = new UUID(0L, 0L).toString();

    private List<JsonValue.JsonObject> entries(HttpResponse<String> r) {
        JsonValue.JsonArray arr = (JsonValue.JsonArray) body(r).members().get("entries");
        return arr.items().stream().map(v -> (JsonValue.JsonObject) v).toList();
    }

    private long entryLong(JsonValue.JsonObject e, String name) {
        return ((JsonValue.JsonNumber) e.members().get(name)).value();
    }

    private String entryStr(JsonValue.JsonObject e, String name) {
        return ((JsonValue.JsonString) e.members().get(name)).value();
    }

    @Test
    void emptyStatementForFreshZeroAccount() {
        String id = createAccount("fresh", 0);
        HttpResponse<String> r = get("/accounts/" + id + "/statement");
        assertEquals(200, r.statusCode());
        assertEquals(id, stringField(r, "accountId"));
        assertTrue(entries(r).isEmpty());
    }

    @Test
    void openingAppearsAsWorldTransfer() {
        String id = createAccount("funded", 10_000);
        HttpResponse<String> r = get("/accounts/" + id + "/statement");
        List<JsonValue.JsonObject> entries = entries(r);
        assertEquals(1, entries.size());
        JsonValue.JsonObject opening = entries.get(0);
        assertEquals(WORLD, entryStr(opening, "counterpartyAccountId"));
        assertEquals(10_000, entryLong(opening, "amountMinor"));
        assertEquals(10_000, entryLong(opening, "balanceAfterMinor"));
        assertFalse(entryStr(opening, "transferId").isBlank());
        assertTrue(entryLong(opening, "postingId") > 0);
    }

    @Test
    void transferShowsOppositeSignsOnBothStatements() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        transfer(a, b, 250, freshKey());

        JsonValue.JsonObject aLeg = entries(get("/accounts/" + a + "/statement")).get(0);
        JsonValue.JsonObject bLeg = entries(get("/accounts/" + b + "/statement")).get(0);
        assertEquals(-250, entryLong(aLeg, "amountMinor"));
        assertEquals(b, entryStr(aLeg, "counterpartyAccountId"));
        assertEquals(250, entryLong(bLeg, "amountMinor"));
        assertEquals(a, entryStr(bLeg, "counterpartyAccountId"));
        assertEquals(entryStr(aLeg, "transferId"), entryStr(bLeg, "transferId"));
    }

    @Test
    void entriesNewestFirstWithRunningBalance() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        transfer(a, b, 100, freshKey());
        transfer(a, b, 200, freshKey());
        transfer(a, b, 50, freshKey());

        List<JsonValue.JsonObject> entries = entries(get("/accounts/" + a + "/statement"));
        // Newest first: the -50 transfer leads, then -200, then -100, then the +1000 opening.
        assertEquals(4, entries.size());
        assertEquals(-50, entryLong(entries.get(0), "amountMinor"));
        assertEquals(-200, entryLong(entries.get(1), "amountMinor"));
        assertEquals(-100, entryLong(entries.get(2), "amountMinor"));
        assertEquals(1000, entryLong(entries.get(3), "amountMinor"));
        // The newest entry's running balance equals the current balance.
        assertEquals(balanceOf(a), entryLong(entries.get(0), "balanceAfterMinor"));
        assertEquals(650, balanceOf(a));
    }

    @Test
    void capsAtMostRecent100Entries() {
        String a = createAccount("A", 200);
        String b = createAccount("B", 0);
        for (int i = 0; i < 101; i++) {
            transfer(a, b, 1, freshKey());
        }
        List<JsonValue.JsonObject> entries = entries(get("/accounts/" + a + "/statement"));
        assertEquals(100, entries.size());
        // The oldest posting, the opening from world, is cut off, so no entry names world.
        assertFalse(entries.stream().anyMatch(e -> WORLD.equals(entryStr(e, "counterpartyAccountId"))));
    }

    @Test
    void statementForUnknownAccountReturns404() {
        HttpResponse<String> r = get("/accounts/" + UUID.randomUUID() + "/statement");
        assertEquals(404, r.statusCode());
        assertEquals("ACCOUNT_NOT_FOUND", errorCode(r));
    }
}
