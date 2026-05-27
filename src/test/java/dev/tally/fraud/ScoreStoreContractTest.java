package dev.tally.fraud;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.store.Store;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What both score stores must do, run against whatever the subclass returns. Scores point at real
 * postings made through a ledger store, because the Postgres table has foreign keys to them.
 */
abstract class ScoreStoreContractTest {
    private static final Instant T0 = Instant.parse("2026-03-10T12:00:00Z");

    protected abstract Store ledger();

    protected abstract ScoreStore scores();

    private Store ledger;
    private ScoreStore store;
    private Account alice;
    private Account bob;
    private Account carol;

    @BeforeEach
    void accounts() {
        ledger = ledger();
        store = scores();
        alice = ledger.createAccount("alice", 1_000_000);
        bob = ledger.createAccount("bob", 1_000_000);
        carol = ledger.createAccount("carol", 0);
    }

    private Score pay(Account from, Account to, long amount, Instant at, int points, List<String> rules) {
        TransferRequest request = new TransferRequest("score-" + UUID.randomUUID(), from.id(), to.id(), amount);
        TransferOutcome.Applied applied = assertInstanceOf(TransferOutcome.Applied.class, ledger.apply(request));
        return new Score(applied.debitPostingId(), applied.id(), from.id(), to.id(), amount, points, rules, at, at);
    }

    @Test
    void aPostingIsStoredOnceWhateverHowOftenItArrives() {
        Score score = pay(alice, bob, 500, T0, 35, List.of("velocity", "round_amount"));
        assertFalse(store.contains(score.postingId()));
        assertTrue(store.insertIfAbsent(score));
        assertTrue(store.contains(score.postingId()));
        assertFalse(store.insertIfAbsent(score), "the second insert of the same posting is refused");
        assertEquals(1, store.outgoingBefore(alice.id(), Long.MAX_VALUE, 10).size());
    }

    @Test
    void aStoredScoreReadsBackFieldForField() {
        Score score = pay(alice, bob, 12_345, T0, 55, List.of("amount_deviation", "new_counterparty"));
        store.insertIfAbsent(score);
        Score back = store.outgoingBefore(alice.id(), Long.MAX_VALUE, 1).getFirst();
        assertEquals(score, back);
    }

    @Test
    void outgoingIsNewestFirstBelowThePostingAndLimited() {
        Score first = pay(alice, bob, 100, T0, 0, List.of());
        Score second = pay(alice, carol, 200, T0.plusSeconds(60), 0, List.of());
        Score third = pay(alice, bob, 300, T0.plusSeconds(120), 0, List.of());
        Score someoneElse = pay(bob, alice, 400, T0.plusSeconds(180), 0, List.of());
        for (Score s : List.of(first, second, third, someoneElse)) {
            store.insertIfAbsent(s);
        }
        List<Long> ids = store.outgoingBefore(alice.id(), third.postingId(), 10).stream().map(Score::postingId).toList();
        assertEquals(List.of(second.postingId(), first.postingId()), ids);
        assertEquals(1, store.outgoingBefore(alice.id(), Long.MAX_VALUE, 1).size());
        assertEquals(third.postingId(), store.outgoingBefore(alice.id(), Long.MAX_VALUE, 1).getFirst().postingId());
    }

    @Test
    void incomingIsMoneyPaidToTheAccountSinceATime() {
        Score early = pay(alice, carol, 100, T0, 0, List.of());
        Score recent = pay(bob, carol, 200, T0.plus(Duration.ofMinutes(50)), 0, List.of());
        Score notToCarol = pay(alice, bob, 300, T0.plus(Duration.ofMinutes(55)), 0, List.of());
        for (Score s : List.of(early, recent, notToCarol)) {
            store.insertIfAbsent(s);
        }
        List<Score> incoming = store.incomingSince(carol.id(), T0.plus(Duration.ofMinutes(30)), Long.MAX_VALUE);
        assertEquals(List.of(recent.postingId()), incoming.stream().map(Score::postingId).toList());
    }

    @Test
    void anAccountWithNoScoresHasAnEmptyWindow() {
        AccountId nobody = carol.id();
        assertTrue(store.outgoingBefore(nobody, Long.MAX_VALUE, 10).isEmpty());
        assertTrue(store.incomingSince(nobody, T0, Long.MAX_VALUE).isEmpty());
    }
}
