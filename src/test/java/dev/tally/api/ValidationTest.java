package dev.tally.api;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The edge validation rules, each with its exact code, field, and message. Every rule fails closed at
 * the boundary before the store is ever called.
 */
class ValidationTest extends ApiTestHarness {

    private HttpResponse<String> account(String body) {
        return post("/accounts", body);
    }

    private HttpResponse<String> transferBody(String body) {
        return post("/transfers", body, "Idempotency-Key", freshKey());
    }

    private String transfer(String from, String to, String amount) {
        return "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to + "\",\"amountMinor\":" + amount + "}";
    }

    private void assertBadField(HttpResponse<String> r, String code, String field) {
        assertEquals(400, r.statusCode());
        assertEquals(code, errorCode(r));
        assertEquals(field, errorField(r));
    }

    @Test
    void accountNameRejections() {
        assertBadField(account("{}"), "NAME_REQUIRED", "name");
        assertEquals("name is required and must be a string", errorMessage(account("{}")));
        assertBadField(account("{\"name\":42}"), "NAME_REQUIRED", "name");
        assertBadField(account("{\"name\":\"\"}"), "NAME_LENGTH", "name");
        assertBadField(account("{\"name\":\"   \"}"), "NAME_LENGTH", "name");   // whitespace trims to empty
        assertBadField(account("{\"name\":\"" + "a".repeat(81) + "\"}"), "NAME_LENGTH", "name");
        assertEquals("name must be 1 to 80 characters", errorMessage(account("{\"name\":\"" + "a".repeat(81) + "\"}")));
        assertBadField(account("{\"name\":\"a<script>\"}"), "NAME_CHARSET", "name");
        assertBadField(account("{\"name\":\"bad\\nname\"}"), "NAME_CHARSET", "name");   // internal newline
    }

    @Test
    void accountNameAccepted() {
        assertEquals(201, account("{\"name\":\"Ada Lovelace\"}").statusCode());
        assertEquals(201, account("{\"name\":\"O'Brien & Sons\"}").statusCode());
        assertEquals(201, account("{\"name\":\"" + "a".repeat(80) + "\"}").statusCode());   // exactly the cap
        assertEquals(201, account("{\"name\":\"Zoë\"}").statusCode());            // non-ASCII letter
        // The stored name is the trimmed value.
        assertEquals("Ada", stringField(account("{\"name\":\"  Ada  \"}"), "name"));
    }

    @Test
    void openingBalanceRejections() {
        assertBadField(account("{\"name\":\"x\",\"openingBalanceMinor\":\"100\"}"), "OPENING_BALANCE_NOT_INTEGER", "openingBalanceMinor");
        assertBadField(account("{\"name\":\"x\",\"openingBalanceMinor\":-5}"), "OPENING_BALANCE_NEGATIVE", "openingBalanceMinor");
        assertBadField(account("{\"name\":\"x\",\"openingBalanceMinor\":1000000000001}"), "OPENING_BALANCE_TOO_LARGE", "openingBalanceMinor");
    }

    @Test
    void openingBalanceAccepted() {
        HttpResponse<String> atCap = account("{\"name\":\"x\",\"openingBalanceMinor\":1000000000000}");
        assertEquals(201, atCap.statusCode());
        assertEquals(1000000000000L, longField(atCap, "balanceMinor"));   // funded through a world opening
        assertEquals(0, longField(account("{\"name\":\"y\"}"), "balanceMinor"));   // absent means zero
    }

    @Test
    void transferRejections() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        String someUuid = UUID.randomUUID().toString();
        assertBadField(transferBody("{\"toAccountId\":\"" + b + "\",\"amountMinor\":100}"), "FROM_ACCOUNT_ID_REQUIRED", "fromAccountId");
        assertBadField(transferBody("{\"fromAccountId\":42,\"toAccountId\":\"" + b + "\",\"amountMinor\":100}"), "FROM_ACCOUNT_ID_REQUIRED", "fromAccountId");
        assertBadField(transferBody(transfer("not-a-uuid", b, "100")), "FROM_ACCOUNT_ID_INVALID", "fromAccountId");
        assertBadField(transferBody("{\"fromAccountId\":\"" + a + "\",\"amountMinor\":100}"), "TO_ACCOUNT_ID_REQUIRED", "toAccountId");
        assertBadField(transferBody(transfer(someUuid, someUuid, "100")), "SAME_ACCOUNT", "toAccountId");
        assertBadField(transferBody("{\"fromAccountId\":\"" + a + "\",\"toAccountId\":\"" + b + "\"}"), "AMOUNT_REQUIRED", "amountMinor");
        assertBadField(transferBody(transfer(a, b, "\"100\"")), "AMOUNT_NOT_INTEGER", "amountMinor");
        assertBadField(transferBody(transfer(a, b, "0")), "AMOUNT_NOT_POSITIVE", "amountMinor");
        assertBadField(transferBody(transfer(a, b, "-5")), "AMOUNT_NOT_POSITIVE", "amountMinor");
        assertBadField(transferBody(transfer(a, b, "1000000000001")), "AMOUNT_TOO_LARGE", "amountMinor");
    }

    @Test
    void fractionalAmountIsMalformedJson() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        // A float never reaches validation: the parser rejects it, so money never touches a decimal.
        assertEquals("MALFORMED_JSON", errorCode(transferBody(transfer(a, b, "12.5"))));
        assertEquals("MALFORMED_JSON", errorCode(transferBody(transfer(a, b, "1e3"))));
    }

    @Test
    void unknownFieldRejected() {
        HttpResponse<String> acct = account("{\"name\":\"a\",\"nickname\":\"b\"}");
        assertBadField(acct, "UNKNOWN_FIELD", "nickname");
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        HttpResponse<String> tx = transferBody("{\"fromAccountId\":\"" + a + "\",\"toAccountId\":\"" + b + "\",\"amountMinor\":10,\"amount\":10}");
        assertBadField(tx, "UNKNOWN_FIELD", "amount");
    }

    @Test
    void idempotencyKeyFormat() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        String body = transfer(a, b, "10");
        for (String badKey : new String[]{"short12", "a".repeat(65), "has space", "has!bang"}) {
            HttpResponse<String> r = post("/transfers", body, "Idempotency-Key", badKey);
            assertEquals("IDEMPOTENCY_KEY_INVALID", errorCode(r));
        }
        assertEquals("IDEMPOTENCY_KEY_MISSING", errorCode(post("/transfers", body)));
        assertEquals(201, post("/transfers", body, "Idempotency-Key", UUID.randomUUID().toString()).statusCode());
    }

    @Test
    void bodyOverCapIs413() {
        String tooBig = "{\"name\":\"" + "a".repeat(5000) + "\"}";   // well over 4096 bytes
        assertEquals(413, account(tooBig).statusCode());
        assertEquals("BODY_TOO_LARGE", errorCode(account(tooBig)));
        // A body right at the cap is not rejected for size (it fails later on charset, never 413).
        String atCap = "{\"name\":\"" + "a".repeat(4000) + "\"}";
        assertNotEquals(413, account(atCap).statusCode());
    }

    @Test
    void validationRunsAfterParseBeforeStore() {
        // A well-formed transfer between two valid-but-nonexistent ids passes the edge and reaches the
        // store, which answers UNKNOWN_ACCOUNT (422), not a 400. Proves order: parse < validate < store.
        String body = transfer(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "100");
        HttpResponse<String> r = transferBody(body);
        assertEquals(422, r.statusCode());
        assertEquals("UNKNOWN_ACCOUNT", errorCode(r));
    }
}
