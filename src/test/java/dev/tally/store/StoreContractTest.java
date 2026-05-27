package dev.tally.store;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.ReconciliationReport;
import dev.tally.core.StatementLine;
import dev.tally.core.StatementPage;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.core.WorldAccount;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The behaviour both stores must share, run against whatever newStore returns. Proving the in-memory
 * and Postgres stores answer identically is the point of the seam.
 */
abstract class StoreContractTest {

    protected abstract Store newStore();

    private long balance(Store store, AccountId id) {
        return store.findAccount(id).orElseThrow().balanceMinor();
    }

    private String key() {
        return "contract-" + UUID.randomUUID();
    }

    @Test
    void createAccountFundsFromWorld() {
        Store store = newStore();
        Account alice = store.createAccount("alice", 1000);
        assertEquals(1000, balance(store, alice.id()));
        assertEquals(-1000, balance(store, WorldAccount.ID));

        StatementLine opening = store.statement(alice.id(), Long.MAX_VALUE, 10).entries().getFirst();
        assertEquals(WorldAccount.ID, opening.counterpartyAccountId());
        assertEquals(1000, opening.amountMinor());
        assertEquals(1000, opening.balanceAfterMinor());
    }

    @Test
    void createAccountZeroOpeningMovesNoMoney() {
        Store store = newStore();
        Account bob = store.createAccount("bob", 0);
        assertEquals(0, balance(store, bob.id()));
        assertEquals(0, balance(store, WorldAccount.ID));
        assertTrue(store.statement(bob.id(), Long.MAX_VALUE, 10).entries().isEmpty());
    }

    @Test
    void bookSumsToZeroAfterCreation() {
        Store store = newStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 500);
        long sum = balance(store, a.id()) + balance(store, b.id()) + balance(store, WorldAccount.ID);
        assertEquals(0, sum);
    }

    @Test
    void findAccountUnknownIdIsEmpty() {
        assertTrue(newStore().findAccount(AccountId.newId()).isEmpty());
    }

    @Test
    void listAccountsReturnsCreatedAccountsInOrder() {
        Store store = newStore();
        Account a = store.createAccount("alice", 1000);
        Account b = store.createAccount("bob", 0);
        List<Account> list = store.listAccounts();
        assertEquals(2, list.size());
        assertEquals(a.id(), list.get(0).id());
        assertEquals("alice", list.get(0).name());
        assertEquals(1000, list.get(0).balanceMinor());
        assertEquals(b.id(), list.get(1).id());
    }

    @Test
    void listAccountsExcludesWorld() {
        Store store = newStore();
        store.createAccount("only", 500);
        assertTrue(store.listAccounts().stream().noneMatch(account -> WorldAccount.isWorld(account.id())));
        assertEquals(1, store.listAccounts().size());
    }

    @Test
    void emptyLedgerListsNoAccounts() {
        assertTrue(newStore().listAccounts().isEmpty());   // world exists but is filtered out
    }

    @Test
    void appliedTransferMovesMoneyOnce() {
        Store store = newStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 0);
        TransferOutcome.Applied applied = assertInstanceOf(TransferOutcome.Applied.class,
                store.apply(new TransferRequest(key(), a.id(), b.id(), 400)));
        assertEquals(600, applied.fromBalanceAfter());
        assertEquals(400, applied.toBalanceAfter());
        assertEquals(600, balance(store, a.id()));
        assertEquals(400, balance(store, b.id()));
    }

    @Test
    void insufficientFundsLeavesBalancesUntouched() {
        Store store = newStore();
        Account a = store.createAccount("a", 100);
        Account b = store.createAccount("b", 0);
        assertEquals(new TransferOutcome.InsufficientFunds(a.id(), 100, 101),
                store.apply(new TransferRequest(key(), a.id(), b.id(), 101)));
        assertEquals(100, balance(store, a.id()));
        assertEquals(0, balance(store, b.id()));
    }

    // The fraud scorer keys its scores on the debit posting id (ADR-0024), so an Applied outcome has to
    // name its two postings, and a replay has to name the same two.
    @Test
    void appliedNamesItsDebitAndCreditPostings() {
        Store store = newStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 0);
        TransferRequest request = new TransferRequest(key(), a.id(), b.id(), 300);
        TransferOutcome.Applied applied = assertInstanceOf(TransferOutcome.Applied.class, store.apply(request));

        StatementLine debit = store.statement(a.id(), Long.MAX_VALUE, 1).entries().getFirst();
        StatementLine credit = store.statement(b.id(), Long.MAX_VALUE, 1).entries().getFirst();
        assertEquals(debit.postingId(), applied.debitPostingId());
        assertEquals(-300, debit.amountMinor());
        assertEquals(credit.postingId(), applied.creditPostingId());
        assertEquals(300, credit.amountMinor());

        TransferOutcome.Replayed replayed = assertInstanceOf(TransferOutcome.Replayed.class, store.apply(request));
        TransferOutcome.Applied first = assertInstanceOf(TransferOutcome.Applied.class, replayed.first());
        assertEquals(applied.debitPostingId(), first.debitPostingId());
        assertEquals(applied.creditPostingId(), first.creditPostingId());
    }

    @Test
    void replaySameKeyReturnsFirstOutcomeAndMovesNothing() {
        Store store = newStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 0);
        String k = key();
        store.apply(new TransferRequest(k, a.id(), b.id(), 300));
        assertInstanceOf(TransferOutcome.Replayed.class, store.apply(new TransferRequest(k, a.id(), b.id(), 300)));
        assertEquals(700, balance(store, a.id()));   // moved once
        assertEquals(300, balance(store, b.id()));
    }

    @Test
    void replayOfRejectionReturnsSameRejection() {
        Store store = newStore();
        Account a = store.createAccount("a", 100);
        Account b = store.createAccount("b", 0);
        Account funder = store.createAccount("funder", 1000);
        String k = key();
        assertInstanceOf(TransferOutcome.InsufficientFunds.class, store.apply(new TransferRequest(k, a.id(), b.id(), 200)));
        store.apply(new TransferRequest(key(), funder.id(), a.id(), 500));   // a could now afford it

        TransferOutcome retry = store.apply(new TransferRequest(k, a.id(), b.id(), 200));
        TransferOutcome.Replayed replayed = assertInstanceOf(TransferOutcome.Replayed.class, retry);
        assertInstanceOf(TransferOutcome.InsufficientFunds.class, replayed.first());
        assertEquals(0, balance(store, b.id()));
    }

    @Test
    void sameKeyDifferentRequestIsKeyConflict() {
        Store store = newStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 0);
        String k = key();
        store.apply(new TransferRequest(k, a.id(), b.id(), 300));
        assertEquals(new TransferOutcome.KeyConflict(k), store.apply(new TransferRequest(k, a.id(), b.id(), 999)));
        assertEquals(300, balance(store, b.id()));
    }

    @Test
    void unknownAccountDoesNotConsumeKey() {
        Store store = newStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 0);
        String k = key();
        assertInstanceOf(TransferOutcome.UnknownAccount.class, store.apply(new TransferRequest(k, a.id(), AccountId.newId(), 100)));
        // The key was free, so it applies a real transfer now.
        assertInstanceOf(TransferOutcome.Applied.class, store.apply(new TransferRequest(k, a.id(), b.id(), 100)));
    }

    @Test
    void clientNamingWorldIsReservedAccount() {
        Store store = newStore();
        Account a = store.createAccount("a", 1000);
        assertEquals(new TransferOutcome.ReservedAccount(WorldAccount.ID),
                store.apply(new TransferRequest(key(), a.id(), WorldAccount.ID, 100)));
        assertEquals(new TransferOutcome.ReservedAccount(WorldAccount.ID),
                store.apply(new TransferRequest(key(), WorldAccount.ID, a.id(), 100)));
    }

    @Test
    void reservedAccountDoesNotConsumeKey() {
        Store store = newStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 0);
        String k = key();
        assertInstanceOf(TransferOutcome.ReservedAccount.class, store.apply(new TransferRequest(k, a.id(), WorldAccount.ID, 100)));
        assertInstanceOf(TransferOutcome.Applied.class, store.apply(new TransferRequest(k, a.id(), b.id(), 100)));
    }

    @Test
    void statementReturnsPostingsNewestFirst() {
        Store store = newStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 0);
        store.apply(new TransferRequest(key(), a.id(), b.id(), 100));
        store.apply(new TransferRequest(key(), a.id(), b.id(), 200));

        List<StatementLine> entries = store.statement(a.id(), Long.MAX_VALUE, 10).entries();
        assertEquals(-200, entries.get(0).amountMinor());   // newest first
        assertEquals(-100, entries.get(1).amountMinor());
        assertEquals(1000, entries.get(2).amountMinor());    // the opening from world
        assertTrue(entries.get(0).postingId() > entries.get(1).postingId());
    }

    @Test
    void statementPaginatesByBeforeId() {
        Store store = newStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 0);
        for (int i = 0; i < 4; i++) {
            store.apply(new TransferRequest(key(), a.id(), b.id(), 10));
        }
        List<StatementLine> all = store.statement(a.id(), Long.MAX_VALUE, 100).entries();
        long cursor = all.get(1).postingId();   // page past the two newest
        List<StatementLine> older = store.statement(a.id(), cursor, 100).entries();
        assertTrue(older.stream().allMatch(line -> line.postingId() < cursor));
        assertEquals(all.size() - 2, older.size());
    }

    @Test
    void openingEntryHasWorldCounterparty() {
        Store store = newStore();
        Account a = store.createAccount("a", 5000);
        StatementPage page = store.statement(a.id(), Long.MAX_VALUE, 10);
        assertEquals(WorldAccount.ID, page.entries().getFirst().counterpartyAccountId());
    }

    @Test
    void emptyAccountStatementIsAnEmptyPage() {
        Store store = newStore();
        Account bob = store.createAccount("bob", 0);
        StatementPage page = store.statement(bob.id(), Long.MAX_VALUE, 50);
        assertTrue(page.entries().isEmpty());
        assertFalse(page.hasMore());
    }

    @Test
    void runningBalanceMatchesReplay() {
        Store store = newStore();
        Account a = store.createAccount("a", 10_000);
        Account b = store.createAccount("b", 10_000);
        Random random = new Random(7);
        for (int i = 0; i < 10; i++) {
            boolean aToB = random.nextBoolean();
            long amount = random.nextLong(1, 500);
            store.apply(new TransferRequest(key(), aToB ? a.id() : b.id(), aToB ? b.id() : a.id(), amount));
        }
        List<StatementLine> entries = store.statement(a.id(), Long.MAX_VALUE, 100).entries();
        // Walk oldest to newest, recomputing the balance; every balanceAfterMinor must match.
        long running = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            running += entries.get(i).amountMinor();
            assertEquals(running, entries.get(i).balanceAfterMinor());
        }
        assertEquals(balance(store, a.id()), entries.getFirst().balanceAfterMinor());   // newest equals stored
    }

    @Test
    void limitTruncatesAndSetsHasMore() {
        Store store = newStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 0);
        for (int i = 0; i < 4; i++) {
            store.apply(new TransferRequest(key(), a.id(), b.id(), 10));
        }
        StatementPage page = store.statement(a.id(), Long.MAX_VALUE, 2);   // a has 5 postings, page 2
        assertEquals(2, page.entries().size());
        assertTrue(page.hasMore());
    }

    @Test
    void exactlyLimitPostingsHasNoMore() {
        Store store = newStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 0);
        store.apply(new TransferRequest(key(), a.id(), b.id(), 10));   // a has exactly 2 postings
        StatementPage page = store.statement(a.id(), Long.MAX_VALUE, 2);
        assertEquals(2, page.entries().size());
        assertFalse(page.hasMore());   // the limit+1 probe found no extra row
    }

    @Test
    void cleanLedgerReportsConsistent() {
        Store store = newStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 500);
        store.apply(new TransferRequest(key(), a.id(), b.id(), 300));

        ReconciliationReport report = store.reconcile();
        assertTrue(report.consistent());
        assertEquals(0, report.globalSumMinor());
        assertTrue(report.drifts().isEmpty());
        assertEquals(3, report.accountsChecked());   // world, a, b
    }

    @Test
    void worldBalanceOffsetsAllOpenings() {
        Store store = newStore();
        store.createAccount("a", 1000);
        store.createAccount("b", 2500);
        store.createAccount("c", 0);

        ReconciliationReport report = store.reconcile();
        // World is deeply negative, every account matches its postings, and the whole book still nets zero.
        assertEquals(-3500, balance(store, WorldAccount.ID));
        assertTrue(report.consistent());
        assertEquals(0, report.globalSumMinor());
    }

    @Test
    void pagesChainWithoutOverlapOrGap() {
        Store store = newStore();
        Account a = store.createAccount("a", 1000);
        Account b = store.createAccount("b", 0);
        for (int i = 0; i < 6; i++) {
            store.apply(new TransferRequest(key(), a.id(), b.id(), 10));   // a has 7 postings
        }
        Set<Long> seen = new HashSet<>();
        long before = Long.MAX_VALUE;
        while (true) {
            StatementPage page = store.statement(a.id(), before, 3);
            for (StatementLine line : page.entries()) {
                assertTrue(seen.add(line.postingId()), "no page overlap");
            }
            if (!page.hasMore()) {
                break;
            }
            before = page.entries().getLast().postingId();
        }
        assertEquals(7, seen.size());   // every posting seen exactly once, no gap
    }
}
