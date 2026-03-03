package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.WorldAccount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryStoreTest {

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
        assertEquals(1000L, store.findAccount(alice.id()).orElseThrow().balanceMinor());
        assertEquals(-1000L, store.findAccount(WorldAccount.ID).orElseThrow().balanceMinor());
    }

    @Test
    void zeroOpeningLeavesWorldUnchanged() {
        InMemoryStore store = new InMemoryStore();
        Account bob = store.createAccount("bob", 0);
        assertEquals(0L, store.findAccount(bob.id()).orElseThrow().balanceMinor());
        assertEquals(0L, store.findAccount(WorldAccount.ID).orElseThrow().balanceMinor());
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
            userTotal += store.findAccount(a.id()).orElseThrow().balanceMinor();
        }
        long world = store.findAccount(WorldAccount.ID).orElseThrow().balanceMinor();

        assertEquals(15_500L, userTotal);
        assertEquals(-userTotal, world);
        assertEquals(0L, userTotal + world);
    }
}
