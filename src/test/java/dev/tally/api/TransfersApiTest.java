package dev.tally.api;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransfersApiTest extends ApiTestHarness {

    private static final String WORLD = new UUID(0L, 0L).toString();

    private HttpResponse<String> postTransfer(String body) {
        return post("/transfers", body, "Idempotency-Key", freshKey());
    }

    private String transferBody(String from, String to, String amount) {
        return "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to + "\",\"amountMinor\":" + amount + "}";
    }

    @Test
    void transfersBetweenAccounts() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        HttpResponse<String> r = transfer(a, b, 400, freshKey());
        assertEquals(201, r.statusCode());
        assertEquals(a, stringField(r, "fromAccountId"));
        assertEquals(b, stringField(r, "toAccountId"));
        assertEquals(400, longField(r, "amountMinor"));
        assertFalse(stringField(r, "id").isBlank());
        assertEquals(600, balanceOf(a));
        assertEquals(400, balanceOf(b));
    }

    @Test
    void responseCarriesParseableCreatedAt() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        HttpResponse<String> r = transfer(a, b, 100, freshKey());
        assertDoesNotThrow(() -> Instant.parse(stringField(r, "createdAt")));
    }

    @Test
    void rejectsUnknownFromAccount() {
        String b = createAccount("B", 0);
        HttpResponse<String> r = transfer(UUID.randomUUID().toString(), b, 100, freshKey());
        assertEquals(422, r.statusCode());
        assertEquals("UNKNOWN_ACCOUNT", errorCode(r));
        assertEquals(0, balanceOf(b));
    }

    @Test
    void rejectsUnknownToAccount() {
        String a = createAccount("A", 1000);
        HttpResponse<String> r = transfer(a, UUID.randomUUID().toString(), 100, freshKey());
        assertEquals(422, r.statusCode());
        assertEquals("UNKNOWN_ACCOUNT", errorCode(r));
        assertEquals(1000, balanceOf(a));
    }

    @Test
    void rejectsInsufficientFundsAndMovesNothing() {
        String a = createAccount("A", 100);
        String b = createAccount("B", 0);
        HttpResponse<String> r = transfer(a, b, 101, freshKey());
        assertEquals(422, r.statusCode());
        assertEquals("INSUFFICIENT_FUNDS", errorCode(r));
        assertEquals(100, balanceOf(a));
        assertEquals(0, balanceOf(b));
    }

    @Test
    void rejectsWorldAsFrom() {
        String b = createAccount("B", 0);
        HttpResponse<String> r = transfer(WORLD, b, 100, freshKey());
        assertEquals(422, r.statusCode());
        assertEquals("RESERVED_ACCOUNT", errorCode(r));
        assertEquals(0, balanceOf(b));
    }

    @Test
    void rejectsWorldAsTo() {
        String a = createAccount("A", 1000);
        HttpResponse<String> r = transfer(a, WORLD, 100, freshKey());
        assertEquals(422, r.statusCode());
        assertEquals("RESERVED_ACCOUNT", errorCode(r));
        assertEquals(1000, balanceOf(a));
    }

    @Test
    void rejectsZeroAndNegativeAmount() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        assertEquals("AMOUNT_NOT_POSITIVE", errorCode(postTransfer(transferBody(a, b, "0"))));
        assertEquals("AMOUNT_NOT_POSITIVE", errorCode(postTransfer(transferBody(a, b, "-1"))));
    }

    @Test
    void rejectsSelfTransfer() {
        String a = createAccount("A", 1000);
        HttpResponse<String> r = postTransfer(transferBody(a, a, "100"));
        assertEquals(400, r.statusCode());
        assertEquals("SAME_ACCOUNT", errorCode(r));
    }

    @Test
    void rejectsMissingFromField() {
        String b = createAccount("B", 0);
        HttpResponse<String> r = postTransfer("{\"toAccountId\":\"" + b + "\",\"amountMinor\":100}");
        assertEquals(400, r.statusCode());
        assertEquals("FROM_ACCOUNT_ID_REQUIRED", errorCode(r));
        assertEquals("fromAccountId", errorField(r));
    }

    @Test
    void rejectsMalformedFromId() {
        String b = createAccount("B", 0);
        HttpResponse<String> r = postTransfer(transferBody("not-a-uuid", b, "100"));
        assertEquals(400, r.statusCode());
        assertEquals("FROM_ACCOUNT_ID_INVALID", errorCode(r));
        assertEquals("fromAccountId", errorField(r));
    }

    @Test
    void rejectsMissingAmountField() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        HttpResponse<String> r = postTransfer("{\"fromAccountId\":\"" + a + "\",\"toAccountId\":\"" + b + "\"}");
        assertEquals(400, r.statusCode());
        assertEquals("AMOUNT_REQUIRED", errorCode(r));
        assertEquals("amountMinor", errorField(r));
    }

    @Test
    void rejectsStringAmount() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        HttpResponse<String> r = postTransfer(transferBody(a, b, "\"100\""));
        assertEquals(400, r.statusCode());
        assertEquals("AMOUNT_NOT_INTEGER", errorCode(r));
    }

    @Test
    void rejectsFractionalAmountAtParseLayer() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        HttpResponse<String> r = postTransfer(transferBody(a, b, "1.5"));
        assertEquals(400, r.statusCode());
        assertEquals("MALFORMED_JSON", errorCode(r));
    }

    @Test
    void requiresIdempotencyKeyHeader() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        HttpResponse<String> r = post("/transfers", transferBody(a, b, "100"));   // no Idempotency-Key
        assertEquals(400, r.statusCode());
        assertEquals("IDEMPOTENCY_KEY_MISSING", errorCode(r));
        assertEquals(1000, balanceOf(a));
    }

    @Test
    void getOnTransfersReturns405() {
        HttpResponse<String> r = method("GET", "/transfers");
        assertEquals(405, r.statusCode());
        assertTrue(r.headers().firstValue("Allow").orElseThrow().contains("POST"));
    }
}
