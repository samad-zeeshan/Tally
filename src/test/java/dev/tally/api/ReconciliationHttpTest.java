package dev.tally.api;

import dev.tally.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReconciliationHttpTest extends ApiTestHarness {

    private boolean consistent(HttpResponse<String> r) {
        return ((JsonValue.JsonBool) body(r).members().get("consistent")).value();
    }

    private JsonValue.JsonArray drifts(HttpResponse<String> r) {
        return (JsonValue.JsonArray) body(r).members().get("drifts");
    }

    @Test
    void getReconciliationReturnsReportShape() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 500);
        transfer(a, b, 300, freshKey());

        HttpResponse<String> r = get("/reconciliation");
        assertEquals(200, r.statusCode());
        assertTrue(consistent(r));
        assertEquals(0, longField(r, "globalSumMinor"));
        assertEquals(3, longField(r, "accountsChecked"));   // world, A, B
        assertTrue(drifts(r).items().isEmpty());
    }

    @Test
    void freshBookHasOnlyWorldAndIsConsistent() {
        HttpResponse<String> r = get("/reconciliation");
        assertEquals(200, r.statusCode());
        assertTrue(consistent(r));
        assertEquals(0, longField(r, "globalSumMinor"));
        assertEquals(1, longField(r, "accountsChecked"));   // world alone
    }
}
