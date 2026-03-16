package dev.tally.api;

import dev.tally.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class StatementHttpTest extends ApiTestHarness {

    private List<JsonValue.JsonObject> entries(HttpResponse<String> r) {
        JsonValue.JsonArray arr = (JsonValue.JsonArray) body(r).members().get("entries");
        return arr.items().stream().map(v -> (JsonValue.JsonObject) v).toList();
    }

    private String nextCursor(HttpResponse<String> r) {
        JsonValue value = body(r).members().get("nextCursor");
        return value instanceof JsonValue.JsonString s ? s.value() : null;
    }

    @Test
    void defaultLimitIsFifty() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        for (int i = 0; i < 50; i++) {
            transfer(a, b, 1, freshKey());   // a ends with 51 postings, one opening plus fifty
        }
        HttpResponse<String> r = get("/accounts/" + a + "/statement");
        assertEquals(50, entries(r).size());
        assertNotNull(nextCursor(r));
    }

    @Test
    void nextCursorRoundTrips() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        for (int i = 0; i < 4; i++) {
            transfer(a, b, 10, freshKey());   // a has 5 postings
        }
        Set<Long> seen = new HashSet<>();
        String cursor = null;
        String lastNext = "start";
        while (true) {
            String path = "/accounts/" + a + "/statement?limit=2" + (cursor == null ? "" : "&cursor=" + cursor);
            HttpResponse<String> r = get(path);
            for (JsonValue.JsonObject e : entries(r)) {
                long id = ((JsonValue.JsonNumber) e.members().get("postingId")).value();
                assertFalse(seen.contains(id), "no page overlap");
                seen.add(id);
            }
            lastNext = nextCursor(r);
            if (lastNext == null) {
                break;
            }
            cursor = lastNext;
        }
        assertNull(lastNext);
        assertEquals(5, seen.size());
    }

    @Test
    void invalidCursorReturns400() {
        String a = createAccount("A", 1000);
        HttpResponse<String> r = get("/accounts/" + a + "/statement?cursor=garbage");
        assertEquals(400, r.statusCode());
        assertEquals("INVALID_CURSOR", errorCode(r));
        assertEquals("cursor", errorField(r));
    }

    @Test
    void limitOutOfRangeReturns400() {
        String a = createAccount("A", 1000);
        for (String limit : new String[]{"0", "201", "abc"}) {
            HttpResponse<String> r = get("/accounts/" + a + "/statement?limit=" + limit);
            assertEquals(400, r.statusCode());
            assertEquals("INVALID_LIMIT", errorCode(r));
            assertEquals("limit", errorField(r));
        }
    }

    @Test
    void unknownAccountStatementReturns404() {
        HttpResponse<String> r = get("/accounts/" + UUID.randomUUID() + "/statement");
        assertEquals(404, r.statusCode());
        assertEquals("ACCOUNT_NOT_FOUND", errorCode(r));
    }
}
