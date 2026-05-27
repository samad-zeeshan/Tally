package dev.tally.fraud;

import dev.tally.core.AccountId;
import dev.tally.core.TransferId;
import dev.tally.obs.Metrics;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scorer reads the window, runs the rules, and stores the result once per posting.
 */
class ScorerTest {
    private static final AccountId ME = AccountId.newId();
    private static final AccountId PAYEE = AccountId.newId();
    private static final Instant NOON = Instant.parse("2026-03-10T12:00:00Z");

    private final InMemoryScoreStore store = new InMemoryScoreStore();
    private final Metrics metrics = new Metrics();
    private long nextPosting = 1;

    private PostingEvent event(AccountId to, long amount, Instant at) {
        long debit = nextPosting++;
        return new PostingEvent(debit, nextPosting++, TransferId.newId(), ME, to, amount, at);
    }

    private record Fixed(String name, int points) implements Rule {
        @Override
        public int points(PostingEvent event, Window window) {
            return points;
        }
    }

    @Test
    void aScoredPostingIsStoredWithTheRulesThatFired() {
        Scorer scorer = new Scorer(store, Rules.DEFAULT, metrics);
        Score score = scorer.score(event(PAYEE, 50_000, NOON)).orElseThrow();
        assertEquals(List.of("round_amount"), score.rules());
        assertEquals(15, score.score());
        assertEquals(score, store.outgoingBefore(ME, Long.MAX_VALUE, 1).getFirst());
        assertEquals(1, metrics.fraudPostings.value("scored"));
        assertEquals(1, metrics.fraudRulesFired.value("round_amount"));
    }

    @Test
    void theSamePostingDeliveredTwiceIsScoredOnce() {
        Scorer scorer = new Scorer(store, Rules.DEFAULT, metrics);
        PostingEvent event = event(PAYEE, 1_000, NOON);
        assertTrue(scorer.score(event).isPresent());
        assertEquals(Optional.empty(), scorer.score(event));
        assertEquals(1, store.outgoingBefore(ME, Long.MAX_VALUE, 10).size());
        assertEquals(1, metrics.fraudPostings.value("scored"));
        assertEquals(1, metrics.fraudPostings.value("duplicate"));
    }

    @Test
    void earlierScoresAreTheWindowForLaterOnes() {
        Scorer scorer = new Scorer(store, Rules.DEFAULT, metrics);
        for (int i = 0; i < 3; i++) {
            scorer.score(event(PAYEE, 1_000, NOON.plus(Duration.ofMinutes(i))));
        }
        Score fourth = scorer.score(event(PAYEE, 1_000, NOON.plus(Duration.ofMinutes(3)))).orElseThrow();
        assertEquals(List.of("velocity"), fourth.rules());
    }

    @Test
    void theScoreIsCappedAtOneHundredAndFlaggedAtTheThreshold() {
        Scorer scorer = new Scorer(store, List.of(new Fixed("a", 60), new Fixed("b", 60)), metrics);
        Score score = scorer.score(event(PAYEE, 1_000, NOON)).orElseThrow();
        assertEquals(100, score.score());
        assertTrue(score.flagged());
        assertEquals(1, metrics.fraudFlagged.value());
    }

    @Test
    void aRuleThatAddsNothingIsNotListed() {
        Scorer scorer = new Scorer(store, List.of(new Fixed("silent", 0), new Fixed("loud", 10)), metrics);
        assertEquals(List.of("loud"), scorer.score(event(PAYEE, 1_000, NOON)).orElseThrow().rules());
    }
}
