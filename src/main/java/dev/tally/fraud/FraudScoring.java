package dev.tally.fraud;

import dev.tally.core.AccountId;
import dev.tally.obs.Metrics;

import java.time.Duration;
import java.util.List;

/**
 * The fraud scoring as the rest of the service sees it: a sink for applied postings, and a reader for the
 * risk endpoint. Wires the queue, the scorer and the store together. See ADR-0024.
 */
public final class FraudScoring implements PostingSink, AutoCloseable {
    public static final int DEFAULT_CAPACITY = 10_000;

    private final ScoreStore store;
    private final ScoringQueue queue;
    private final boolean replayClock;

    public FraudScoring(ScoreStore store, List<Rule> rules, int capacity, boolean replayClock, Metrics metrics) {
        this.store = store;
        this.replayClock = replayClock;
        Scorer scorer = new Scorer(store, rules, metrics);
        this.queue = new ScoringQueue(capacity, scorer::score, metrics);
        metrics.gauge("tally_fraud_queue_depth", "Applied postings waiting to be scored.", queue::depth);
    }

    public static FraudScoring inMemory(Metrics metrics) {
        return new FraudScoring(new InMemoryScoreStore(), Rules.DEFAULT, DEFAULT_CAPACITY, false, metrics);
    }

    @Override
    public void publish(PostingEvent event) {
        queue.offer(event);
    }

    public List<Score> recent(AccountId account, int limit) {
        return store.outgoingBefore(account, Long.MAX_VALUE, limit);
    }

    // True only when TALLY_FRAUD_REPLAY_CLOCK=true, for the offline evaluation. Never set in a manifest.
    public boolean replayClock() {
        return replayClock;
    }

    public boolean awaitIdle(Duration timeout) {
        return queue.awaitIdle(timeout);
    }

    @Override
    public void close() {
        queue.close();
    }
}
