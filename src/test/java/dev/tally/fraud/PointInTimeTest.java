package dev.tally.fraud;

import dev.tally.core.AccountId;
import dev.tally.core.TransferId;
import dev.tally.obs.Metrics;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A score may only use postings committed before the one it scores. A store that leaks a later one is refused.
 */
class PointInTimeTest {
    private static final Instant NOON = Instant.parse("2026-03-10T12:00:00Z");
    private static final AccountId ME = AccountId.newId();
    private static final AccountId YOU = AccountId.newId();

    // Hands back a posting from the future whatever the scorer asks for, the bug a careless query makes.
    private static final class LeakyStore implements ScoreStore {
        private final InMemoryScoreStore inner = new InMemoryScoreStore();
        final Score future = new Score(99, TransferId.newId(), ME, YOU, 1_000, 0, List.of(), NOON.plusSeconds(60), NOON);

        @Override
        public boolean insertIfAbsent(Score score) {
            return inner.insertIfAbsent(score);
        }

        @Override
        public boolean contains(long postingId) {
            return inner.contains(postingId);
        }

        @Override
        public List<Score> outgoingBefore(AccountId account, long beforePostingId, int limit) {
            return List.of(future);
        }

        @Override
        public List<Score> incomingSince(AccountId account, Instant since, long beforePostingId) {
            return List.of();
        }
    }

    @Test
    void aWindowHoldingALaterPostingIsRefusedAndNothingIsStored() {
        LeakyStore store = new LeakyStore();
        Scorer scorer = new Scorer(store, Rules.V2, new Metrics());
        PostingEvent event = new PostingEvent(10, 11, TransferId.newId(), ME, YOU, 1_000, NOON);
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> scorer.score(event));
        assertTrue(e.getMessage().contains("point-in-time"), e.getMessage());
        assertFalse(store.contains(10));
    }

    @Test
    void theExplanationIsTimedAndInsideItsBudget() {
        Scorer scorer = new Scorer(new InMemoryScoreStore(), Rules.V2, new Metrics());
        Score s = scorer.score(new PostingEvent(1, 2, TransferId.newId(), ME, YOU, 1_000, NOON)).orElseThrow();
        assertTrue(s.explanation().micros() >= 0);
        assertTrue(s.explanation().micros() < Scorer.EXPLAIN_BUDGET_MICROS, "took " + s.explanation().micros() + " us");
        assertEquals(s.explanation().features().get("amount_minor"), 1_000L);
    }
}
