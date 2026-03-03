package dev.tally.core;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class LedgerTest {

    private static final long MAX = Long.MAX_VALUE;

    private static Account acct(long balance, boolean allowNegative) {
        return new Account(AccountId.newId(), "n", balance, allowNegative, Instant.EPOCH);
    }

    private static Transfer transfer(Posting... postings) {
        return new Transfer(TransferId.newId(), List.of(postings));
    }

    private static long balanceOf(Ledger.Result.Posted posted, AccountId id) {
        return posted.updated().stream()
                .filter(a -> a.id().equals(id)).findFirst().orElseThrow().balanceMinor();
    }

    @Test
    void balancedPostingsArePosted() {
        Account a = acct(1000, false);
        Account b = acct(0, false);
        Ledger.Result r = Ledger.post(
                transfer(new Posting(a.id(), -400), new Posting(b.id(), 400)),
                Map.of(a.id(), a, b.id(), b));

        Ledger.Result.Posted posted = assertInstanceOf(Ledger.Result.Posted.class, r);
        assertEquals(600, balanceOf(posted, a.id()));
        assertEquals(400, balanceOf(posted, b.id()));
    }

    @Test
    void unbalancedPostingsAreInvalid() {
        Account a = acct(1000, false);
        Account b = acct(0, false);
        Ledger.Result r = Ledger.post(
                transfer(new Posting(a.id(), -100), new Posting(b.id(), 90)),
                Map.of(a.id(), a, b.id(), b));
        assertEquals(new Ledger.Result.Invalid(RejectReason.UNBALANCED), r);
    }

    @Test
    void sumOverflowIsInvalidInsteadOfPrintingMoney() {
        // A naive + would wrap MAX + MAX + 2 to exactly 0 and accept this as balanced.
        Account a = acct(0, false);
        Account b = acct(0, false);
        Account c = acct(0, false);
        Ledger.Result r = Ledger.post(
                transfer(new Posting(a.id(), MAX), new Posting(b.id(), MAX), new Posting(c.id(), 2)),
                Map.of(a.id(), a, b.id(), b, c.id(), c));
        assertEquals(new Ledger.Result.Invalid(RejectReason.OVERFLOW), r);
    }

    @Test
    void intermediateOverflowOfABalancedTransferIsInvalid() {
        Account a = acct(0, false);
        Account b = acct(0, false);
        Account c = acct(0, false);
        Account d = acct(0, false);
        Ledger.Result r = Ledger.post(
                transfer(new Posting(a.id(), MAX), new Posting(b.id(), MAX),
                        new Posting(c.id(), -MAX), new Posting(d.id(), -MAX)),
                Map.of(a.id(), a, b.id(), b, c.id(), c, d.id(), d));
        assertEquals(new Ledger.Result.Invalid(RejectReason.OVERFLOW), r);
    }

    @Test
    void overdraftOnAnOrdinaryAccountIsInsufficientFunds() {
        Account a = acct(100, false);
        Account b = acct(0, false);
        Ledger.Result r = Ledger.post(
                transfer(new Posting(a.id(), -101), new Posting(b.id(), 101)),
                Map.of(a.id(), a, b.id(), b));
        assertEquals(new Ledger.Result.InsufficientFunds(a.id(), 100, 101), r);
    }

    @Test
    void balanceMayReachExactlyZero() {
        Account a = acct(100, false);
        Account b = acct(0, false);
        Ledger.Result r = Ledger.post(
                transfer(new Posting(a.id(), -100), new Posting(b.id(), 100)),
                Map.of(a.id(), a, b.id(), b));

        Ledger.Result.Posted posted = assertInstanceOf(Ledger.Result.Posted.class, r);
        assertEquals(0, balanceOf(posted, a.id()));
        assertEquals(100, balanceOf(posted, b.id()));
    }

    @Test
    void anAllowNegativeAccountMayGoBelowZero() {
        Account w = acct(0, true);   // world-like
        Account b = acct(0, false);
        Ledger.Result r = Ledger.post(
                transfer(new Posting(w.id(), -500), new Posting(b.id(), 500)),
                Map.of(w.id(), w, b.id(), b));

        Ledger.Result.Posted posted = assertInstanceOf(Ledger.Result.Posted.class, r);
        assertEquals(-500, balanceOf(posted, w.id()));
        assertEquals(500, balanceOf(posted, b.id()));
    }

    @Test
    void balanceOverflowIsInvalidAndComputesNothing() {
        Account a = acct(MAX, false);   // any credit overflows
        Account b = acct(10, false);
        Ledger.Result r = Ledger.post(
                transfer(new Posting(b.id(), -10), new Posting(a.id(), 10)),
                Map.of(a.id(), a, b.id(), b));
        assertEquals(new Ledger.Result.Invalid(RejectReason.OVERFLOW), r);
    }
}
