package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.core.WorldAccount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryStoreTest {

    private long balance(Store store, AccountId id) {
        return store.findAccount(id).orElseThrow().balanceMinor();
    }

    @Test
    void worldAccountExistsFromConstruction() {
        InMemoryStore store = new InMemoryStore();
        Account world = store.findAccount(WorldAccount.ID).orElseThrow();
        assertEquals("world", world.name());
        assertEquals(0L, world.balanceMinor());
        assertTrue(world.allowNegative());
    }

    @Test
    void openingIsFundedFromWorld() {
        InMemoryStore store = new InMemoryStore();
        Account alice = store.createAccount("alice", 1000);
        assertEquals(1000L, balance(store, alice.id()));
        assertEquals(-1000L, balance(store, WorldAccount.ID));
    }

    @Test
    void zeroOpeningLeavesWorldUnchanged() {
        InMemoryStore store = new InMemoryStore();
        Account bob = store.createAccount("bob", 0);
        assertEquals(0L, balance(store, bob.id()));
        assertEquals(0L, balance(store, WorldAccount.ID));
    }

    @Test
    void negativeOpeningIsRejected() {
        InMemoryStore store = new InMemoryStore();
        assertThrows(IllegalArgumentException.class, () -> store.createAccount("x", -1));
    }

    @Test
    void theBookSumsToZeroAfterOpenings() {
        InMemoryStore store = new InMemoryStore();
        List<Account> opened = List.of(
                store.createAccount("a", 3_000),
                store.createAccount("b", 500),
                store.createAccount("c", 12_000));

        long userTotal = 0;
        for (Account a : opened) {
            userTotal += balance(store, a.id());
        }
        long world = balance(store, WorldAccount.ID);

        assertEquals(15_500L, userTotal);
        assertEquals(-userTotal, world);
        assertEquals(0L, userTotal + world);
    }

    @Test
    void applyMovesMoneyBetweenTwoAccounts() {
        InMemoryStore store = new InMemoryStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 0);

        TransferOutcome.Applied applied = assertInstanceOf(TransferOutcome.Applied.class,
                store.apply(new TransferRequest("move-key-01", a.id(), b.id(), 400)));

        assertNotNull(applied.id());
        assertEquals(600, applied.fromBalanceAfter());
        assertEquals(400, applied.toBalanceAfter());
        assertNotNull(applied.at());
        assertEquals(600, balance(store, a.id()));
        assertEquals(400, balance(store, b.id()));
    }

    @Test
    void applyRejectsOverdraftWithInsufficientFunds() {
        InMemoryStore store = new InMemoryStore();
        Account a = store.createAccount("a", 100);
        Account b = store.createAccount("b", 0);

        TransferOutcome outcome = store.apply(new TransferRequest("over-key-01", a.id(), b.id(), 101));

        assertEquals(new TransferOutcome.InsufficientFunds(a.id(), 100, 101), outcome);
        assertEquals(100, balance(store, a.id()));
        assertEquals(0, balance(store, b.id()));
    }

    @Test
    void applyRejectsUnknownAccount() {
        InMemoryStore store = new InMemoryStore();
        Account a = store.createAccount("a", 100);
        AccountId unknown = AccountId.newId();

        TransferOutcome outcome = store.apply(new TransferRequest("unk-key-01", a.id(), unknown, 10));

        assertEquals(new TransferOutcome.UnknownAccount(unknown), outcome);
        assertEquals(100, balance(store, a.id()));
    }

    @Test
    void applyRejectsATransferNamingWorld() {
        InMemoryStore store = new InMemoryStore();
        Account a = store.createAccount("a", 100);

        assertEquals(new TransferOutcome.ReservedAccount(WorldAccount.ID),
                store.apply(new TransferRequest("world-key-01", a.id(), WorldAccount.ID, 10)));
        assertEquals(new TransferOutcome.ReservedAccount(WorldAccount.ID),
                store.apply(new TransferRequest("world-key-02", WorldAccount.ID, a.id(), 10)));

        assertEquals(100, balance(store, a.id()));
        assertEquals(-100, balance(store, WorldAccount.ID));
    }

    @Test
    void worldGoesNegativeAsAccountsAreFunded() {
        InMemoryStore store = new InMemoryStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 500);
        store.apply(new TransferRequest("neg-key-01", a.id(), b.id(), 200));

        long world = balance(store, WorldAccount.ID);
        assertTrue(world < 0);
        assertEquals(-(balance(store, a.id()) + balance(store, b.id())), world);
    }

    @Test
    void aRejectedTransferSavesNothing() {
        InMemoryStore store = new InMemoryStore();
        Account a = store.createAccount("a", 100);
        Account b = store.createAccount("b", 0);

        store.apply(new TransferRequest("save-key-01", a.id(), b.id(), 200));   // overdraft, rejected

        assertEquals(100, balance(store, a.id()));
        assertEquals(0, balance(store, b.id()));
    }
}
