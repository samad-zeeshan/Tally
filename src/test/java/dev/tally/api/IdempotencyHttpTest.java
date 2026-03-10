package dev.tally.api;

import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdempotencyHttpTest extends ApiTestHarness {

    private boolean replayed(HttpResponse<String> r) {
        return r.headers().firstValue("Idempotency-Replayed").map("true"::equals).orElse(false);
    }

    @Test
    void replayReturnsOriginalResultOnce() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        String key = freshKey();
        HttpResponse<String> first = transfer(a, b, 300, key);
        HttpResponse<String> second = transfer(a, b, 300, key);

        assertEquals(201, first.statusCode());
        assertEquals(201, second.statusCode());
        assertEquals(stringField(first, "id"), stringField(second, "id"));
        assertEquals(700, balanceOf(a));   // money moved exactly once
        assertEquals(300, balanceOf(b));
    }

    @Test
    void replayCarriesReplayedHeaderFirstDoesNot() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        String key = freshKey();
        assertTrue(!replayed(transfer(a, b, 300, key)), "the first response is not a replay");
        assertTrue(replayed(transfer(a, b, 300, key)), "the second response is a replay");
    }

    @Test
    void conflictOnSameKeyDifferentAmount() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        String key = freshKey();
        transfer(a, b, 300, key);
        HttpResponse<String> conflict = transfer(a, b, 999, key);
        assertEquals(409, conflict.statusCode());
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", errorCode(conflict));
        assertEquals(300, balanceOf(b));   // the conflicting request moved nothing
    }

    @Test
    void conflictOnSameKeyDifferentAccounts() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        String c = createAccount("C", 0);
        String key = freshKey();
        transfer(a, b, 300, key);
        HttpResponse<String> conflict = transfer(a, c, 300, key);
        assertEquals(409, conflict.statusCode());
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", errorCode(conflict));
        assertEquals(0, balanceOf(c));
    }

    @Test
    void fieldOrderDoesNotCauseConflict() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        String key = freshKey();
        post("/transfers", "{\"fromAccountId\":\"" + a + "\",\"toAccountId\":\"" + b + "\",\"amountMinor\":300}",
                "Idempotency-Key", key);
        // Same tuple, reordered fields: the store compares the parsed tuple, not the bytes.
        HttpResponse<String> reordered = post("/transfers",
                "{\"amountMinor\":300,\"toAccountId\":\"" + b + "\",\"fromAccountId\":\"" + a + "\"}",
                "Idempotency-Key", key);
        assertEquals(201, reordered.statusCode());
        assertTrue(replayed(reordered));
        assertEquals(300, balanceOf(b));
    }

    @Test
    void unknownAccountDoesNotConsumeKey() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        String key = freshKey();
        // An unknown account writes nothing, so the key stays free. Server-generated ids mean the
        // missing account cannot be recreated, so a free key is proven by reusing it for a valid
        // transfer: a consumed key with the changed tuple would be a 409, but this gets 201.
        HttpResponse<String> missing = transfer(a, UUID.randomUUID().toString(), 100, key);
        assertEquals(422, missing.statusCode());
        assertEquals("UNKNOWN_ACCOUNT", errorCode(missing));

        HttpResponse<String> reused = transfer(a, b, 100, key);
        assertEquals(201, reused.statusCode());
        assertTrue(!replayed(reused), "the key was free, so this is a fresh apply not a replay");
    }

    @Test
    void insufficientFundsKeyReplaysRejectionAfterFunding() {
        String alice = createAccount("alice", 100);
        String bob = createAccount("bob", 0);
        String carol = createAccount("carol", 1000);
        String key = freshKey();

        HttpResponse<String> rejected = transfer(alice, bob, 200, key);   // alice holds only 100
        assertEquals(422, rejected.statusCode());
        assertEquals("INSUFFICIENT_FUNDS", errorCode(rejected));

        transfer(carol, alice, 500, freshKey());   // alice now holds 600, could afford it
        HttpResponse<String> retry = transfer(alice, bob, 200, key);
        assertEquals(422, retry.statusCode());      // but the recorded rejection replays
        assertEquals("INSUFFICIENT_FUNDS", errorCode(retry));
        assertTrue(replayed(retry));
        assertEquals(0, balanceOf(bob));
    }

    @Test
    void rejectsShortKey() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        HttpResponse<String> r = transfer(a, b, 100, "short12");   // 7 chars
        assertEquals(400, r.statusCode());
        assertEquals("IDEMPOTENCY_KEY_INVALID", errorCode(r));
    }

    @Test
    void rejectsOverlongKey() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        HttpResponse<String> r = transfer(a, b, 100, "k".repeat(65));
        assertEquals(400, r.statusCode());
        assertEquals("IDEMPOTENCY_KEY_INVALID", errorCode(r));
    }

    @Test
    void rejectsIllegalCharactersInKey() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        assertEquals("IDEMPOTENCY_KEY_INVALID", errorCode(transfer(a, b, 100, "has a space")));
        assertEquals("IDEMPOTENCY_KEY_INVALID", errorCode(transfer(a, b, 100, "has:a:colon")));
    }

    @Test
    void keysAreCaseSensitive() {
        String a = createAccount("A", 1000);
        String b = createAccount("B", 0);
        assertEquals(201, transfer(a, b, 100, "abcdefgh").statusCode());
        assertEquals(201, transfer(a, b, 100, "ABCDEFGH").statusCode());
        assertEquals(200, balanceOf(b));   // two distinct keys, two transfers
    }

    @Test
    void concurrentSameKeyAppliesOnce() throws Exception {
        String a = createAccount("A", 10_000);
        String b = createAccount("B", 0);
        String key = freshKey();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return transfer(a, b, 500, key);
            }));
        }
        start.countDown();
        for (Future<HttpResponse<String>> f : futures) {
            assertEquals(201, f.get().statusCode());   // winner Applied and losers Replayed both render 201
        }
        pool.shutdownNow();
        assertEquals(500, balanceOf(b));   // exactly one application
        assertEquals(9_500, balanceOf(a));
    }
}
