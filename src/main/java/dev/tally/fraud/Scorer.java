package dev.tally.fraud;

import dev.tally.obs.Metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Scores one debit posting: read the account's window, run every rule, store the result once.
 *
 * Stateless between calls, in the SR-Fraud sense of a frozen scorer: the rules are fixed at startup and
 * everything a rule needs comes from the store, so the same event always gets the same score.
 */
public final class Scorer {
    // Enough for a month of an ordinary account's payments, and a bound on the read before every score.
    static final int WINDOW_LIMIT = 200;
    static final Duration INCOMING_SPAN = Duration.ofHours(1);

    private final ScoreStore store;
    private final List<Rule> rules;
    private final Metrics metrics;

    public Scorer(ScoreStore store, List<Rule> rules, Metrics metrics) {
        this.store = store;
        this.rules = List.copyOf(rules);
        this.metrics = metrics;
    }

    public Optional<Score> score(PostingEvent event) {
        // A cheap early exit for the common duplicate. The insert below is still what decides.
        if (store.contains(event.debitPostingId())) {
            metrics.fraudPostings.inc("duplicate");
            return Optional.empty();
        }
        Window window = new Window(
                store.outgoingBefore(event.from(), event.debitPostingId(), WINDOW_LIMIT),
                store.incomingSince(event.from(), event.at().minus(INCOMING_SPAN), event.debitPostingId()));
        List<String> fired = new ArrayList<>();
        int total = 0;
        for (Rule rule : rules) {
            int points = rule.points(event, window);
            if (points > 0) {
                fired.add(rule.name());
                total += points;
            }
        }
        Score score = new Score(event.debitPostingId(), event.transferId(), event.from(), event.to(),
                event.amountMinor(), Math.min(100, total), fired, event.at(), Instant.now());
        if (!store.insertIfAbsent(score)) {
            metrics.fraudPostings.inc("duplicate");
            return Optional.empty();
        }
        metrics.fraudPostings.inc("scored");
        for (String name : fired) {
            metrics.fraudRulesFired.inc(name);
        }
        if (score.flagged()) {
            metrics.fraudFlagged.inc();
        }
        return Optional.of(score);
    }
}
