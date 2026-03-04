package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.core.WorldAccount;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InMemoryStoreIdempotencyTest {

    private final InMemoryStore store = new InMemoryStore();
    private final Account a = store.createAccount("A", 10_000);
    private final Account b = store.createAccount("B", 0);

    private long balance(AccountId id) {
        return store.findAccount(id).orElseThrow().balanceMinor();
    }

    private TransferRequest req(String key, AccountId from, AccountId to, long amount) {
        return new TransferRequest(key, from, to, amount);
    }

    @Test
    void transferMovesMoneyOnceAndReportsBalances() {
        TransferOutcome.Applied applied = assertInstanceOf(TransferOutcome.Applied.class,
                store.apply(req("key-0001", a.id(), b.id(), 2500)));
        assertEquals(7500, applied.fromBalanceAfter());
        assertEquals(2500, applied.toBalanceAfter());
        assertEquals(7500, balance(a.id()));
        assertEquals(2500, balance(b.id()));
    }

    @Test
    void aRepeatedKeyReplaysTheFirstOutcome() {
        TransferOutcome first = store.apply(req("key-0002", a.id(), b.id(), 2500));
        TransferOutcome again = store.apply(req("key-0002", a.id(), b.id(), 2500));

        assertEquals(new TransferOutcome.Replayed(first), again);
        assertEquals(7500, balance(a.id()));   // money moved exactly once
        assertEquals(2500, balance(b.id()));
    }

    @Test
    void replayIsTheReplayedCaseWrappingTheOriginal() {
        store.apply(req("key-0003", a.id(), b.id(), 2500));
        TransferOutcome second = store.apply(req("key-0003", a.id(), b.id(), 2500));
        TransferOutcome third = store.apply(req("key-0003", a.id(), b.id(), 2500));

        TransferOutcome.Replayed r2 = assertInstanceOf(TransferOutcome.Replayed.class, second);
        assertInstanceOf(TransferOutcome.Applied.class, r2.first());
        // A third retry still wraps the original Applied, never Replayed(Replayed(...)).
        TransferOutcome.Replayed r3 = assertInstanceOf(TransferOutcome.Replayed.class, third);
        assertInstanceOf(TransferOutcome.Applied.class, r3.first());
    }

    @Test
    void insufficientFundsConsumesTheKeyAndReplaysTheRejection() {
        Account poor = store.createAccount("poor", 100);
        TransferOutcome rejected = store.apply(req("key-0004", poor.id(), b.id(), 101));
        assertEquals(new TransferOutcome.InsufficientFunds(poor.id(), 100, 101), rejected);

        // Fund the source through a different account and key, then retry the original key.
        Account funder = store.createAccount("funder", 500);
        store.apply(req("fund-0004", funder.id(), poor.id(), 200));   // poor now has 300

        TransferOutcome retry = store.apply(req("key-0004", poor.id(), b.id(), 101));
        TransferOutcome.Replayed replayed = assertInstanceOf(TransferOutcome.Replayed.class, retry);
        assertInstanceOf(TransferOutcome.InsufficientFunds.class, replayed.first());
        assertEquals(0, balance(b.id()));   // the rejected key never moved money
    }

    @Test
    void distinctKeysWithSameTupleBothApply() {
        assertInstanceOf(TransferOutcome.Applied.class, store.apply(req("key-0005a", a.id(), b.id(), 300)));
        assertInstanceOf(TransferOutcome.Applied.class, store.apply(req("key-0005b", a.id(), b.id(), 300)));
        assertEquals(600, balance(b.id()));
    }

    @Test
    void sameKeyDifferentTupleIsKeyConflict() {
        TransferOutcome first = store.apply(req("key-0006", a.id(), b.id(), 2500));
        assertEquals(new TransferOutcome.KeyConflict("key-0006"),
                store.apply(req("key-0006", a.id(), b.id(), 3000)));
        assertEquals(2500, balance(b.id()));   // the conflicting request moved nothing

        // The original key still replays its first outcome.
        assertEquals(new TransferOutcome.Replayed(first), store.apply(req("key-0006", a.id(), b.id(), 2500)));
    }

    @Test
    void unknownAccountDoesNotConsumeTheKey() {
        AccountId unknown = AccountId.newId();
        assertEquals(new TransferOutcome.UnknownAccount(unknown),
                store.apply(req("key-0007", a.id(), unknown, 100)));
        // The key was released, so the same key now applies a real transfer.
        assertInstanceOf(TransferOutcome.Applied.class, store.apply(req("key-0007", a.id(), b.id(), 2500)));
    }

    @Test
    void reservedAccountDoesNotConsumeTheKey() {
        assertEquals(new TransferOutcome.ReservedAccount(WorldAccount.ID),
                store.apply(req("key-0008", a.id(), WorldAccount.ID, 100)));
        assertInstanceOf(TransferOutcome.Applied.class, store.apply(req("key-0008", a.id(), b.id(), 2500)));
    }

    @Test
    void sameAccountThrowsBeforeClaimingTheKey() {
        assertThrows(IllegalArgumentException.class, () -> store.apply(req("key-0009", a.id(), a.id(), 100)));
        assertInstanceOf(TransferOutcome.Applied.class, store.apply(req("key-0009", a.id(), b.id(), 100)));
    }

    @Test
    void nonPositiveAmountThrowsBeforeClaimingTheKey() {
        assertThrows(IllegalArgumentException.class, () -> store.apply(req("key-0010", a.id(), b.id(), 0)));
        assertThrows(IllegalArgumentException.class, () -> store.apply(req("key-0010", a.id(), b.id(), -5)));
        assertInstanceOf(TransferOutcome.Applied.class, store.apply(req("key-0010", a.id(), b.id(), 100)));
    }

    @Test
    void transferRequestRejectsNullFieldsAndBlankKey() {
        assertThrows(NullPointerException.class, () -> new TransferRequest("k1valid8", null, b.id(), 1));
        assertThrows(NullPointerException.class, () -> new TransferRequest("k1valid8", a.id(), null, 1));
        assertThrows(IllegalArgumentException.class, () -> new TransferRequest("   ", a.id(), b.id(), 1));
        assertThrows(IllegalArgumentException.class, () -> new TransferRequest(null, a.id(), b.id(), 1));
    }
}
