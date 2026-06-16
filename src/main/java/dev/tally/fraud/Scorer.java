package dev.tally.fraud;

import dev.tally.core.AccountId;
import dev.tally.obs.Metrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Scores one debit posting: read the account's window and its neighbours, run every rule, store the
 * result once with its explanation.
 *
 * Stateless between calls, in the SR-Fraud sense of a frozen scorer: everything a rule needs comes from
 * the store, and only from postings committed before the one being scored.
 */
public final class Scorer {
    // Enough for a month of an ordinary account's payments, and a bound on the read before every score.
    static final int WINDOW_LIMIT = 200;
    static final Duration INCOMING_SPAN = Duration.ofHours(1);
    static final Duration CYCLE_SPAN = Duration.ofHours(24);
    static final int CYCLE_MAX_HOPS = 3;
    // The rules only ask whether a payee has at most one payer or at least three, so counting stops at 50.
    static final int PAYERS_CAP = 50;
    // KONTOGRAPH (arXiv 2608.22389) works to 200 ms for the whole decision. The scorer runs after commit,
    // so its budget only has to keep the queue moving, and a quarter of theirs leaves room for Postgres reads.
    public static final long EXPLAIN_BUDGET_MICROS = 50_000;

    private final ScoreStore store;
    private final List<Rule> rules;
    private final Metrics metrics;

    public Scorer(ScoreStore store, List<Rule> rules, Metrics metrics) {
        this.store = store;
        this.rules = List.copyOf(rules);
        this.metrics = metrics;
    }

    public Optional<Score> score(PostingEvent event) {
        long started = System.nanoTime();
        // A cheap early exit for the common duplicate. The insert below is still what decides.
        if (store.contains(event.debitPostingId())) {
            metrics.fraudPostings.inc("duplicate");
            return Optional.empty();
        }
        List<Score> incoming = store.incomingSince(event.from(), event.at().minus(INCOMING_SPAN), event.debitPostingId());
        Window window = new Window(store.outgoingBefore(event.from(), event.debitPostingId(), WINDOW_LIMIT),
                incoming, graph(event, incoming));
        requirePointInTime(event, window);

        List<String> fired = new ArrayList<>();
        Map<String, Integer> points = new LinkedHashMap<>();
        Set<Long> evidence = new LinkedHashSet<>();
        int total = 0;
        for (Rule rule : rules) {
            int p = rule.points(event, window);
            if (p != 0) {
                fired.add(rule.name());
                points.put(rule.name(), p);
                total += p;
                if (p > 0) {
                    evidence.addAll(rule.evidence(event, window));
                }
            }
        }
        Map<String, Long> features = new LinkedHashMap<>();
        for (FeatureRule.Feature f : FeatureRule.Feature.values()) {
            features.put(f.wireName, f.value(event, window));
        }
        long micros = (System.nanoTime() - started) / 1_000;
        Explanation why = new Explanation(points, features, new ArrayList<>(evidence), micros);
        Score score = new Score(event.debitPostingId(), event.transferId(), event.from(), event.to(),
                event.amountMinor(), Math.clamp(total, 0, 100), fired, event.at(), Instant.now(), why);
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

    // The store queries already filter on posting id. This checks the answer anyway, because a feature
    // that reads a later posting inflates every number the evaluation reports and looks fine in review.
    private static void requirePointInTime(PostingEvent event, Window window) {
        for (long id : window.postingIds()) {
            if (id >= event.debitPostingId()) {
                throw new IllegalStateException("point-in-time violation: posting " + event.debitPostingId()
                        + " would be scored with posting " + id);
            }
        }
    }

    private Window.Graph graph(PostingEvent event, List<Score> incoming) {
        long before = event.debitPostingId();
        int payers = store.distinctPayersBefore(event.to(), before, PAYERS_CAP);
        int payeeOutgoing = store.outgoingCountBefore(event.to(), before, WINDOW_LIMIT);
        // Flagged money that reached one of this account's recent payers in the hour before that payer paid.
        List<Long> seeds = new ArrayList<>();
        for (Score in : incoming) {
            for (Score s : store.incomingSince(in.account(), in.eventAt().minus(INCOMING_SPAN), in.postingId())) {
                if (s.flagged() && !s.eventAt().isAfter(in.eventAt())) {
                    seeds.add(s.postingId());
                }
            }
        }
        return new Window.Graph(payers, payeeOutgoing, seeds, cycle(event));
    }

    // Breadth first from the payee along payments made in the last day, looking for the payer. Returns the
    // posting ids of the first path found, in order, or nothing.
    private List<Long> cycle(PostingEvent event) {
        Instant since = event.at().minus(CYCLE_SPAN);
        Map<AccountId, Score> reachedBy = new HashMap<>();
        ArrayDeque<AccountId> frontier = new ArrayDeque<>(List.of(event.to()));
        Set<AccountId> seen = new HashSet<>(List.of(event.to()));
        for (int hop = 0; hop < CYCLE_MAX_HOPS && !frontier.isEmpty(); hop++) {
            ArrayDeque<AccountId> next = new ArrayDeque<>();
            for (AccountId node : frontier) {
                for (Score s : store.outgoingSince(node, since, event.debitPostingId(), WINDOW_LIMIT)) {
                    if (s.eventAt().isAfter(event.at())) {
                        continue;
                    }
                    if (s.counterparty().equals(event.from())) {
                        List<Long> path = new ArrayList<>(List.of(s.postingId()));
                        for (AccountId at = node; reachedBy.containsKey(at); at = reachedBy.get(at).account()) {
                            path.addFirst(reachedBy.get(at).postingId());
                        }
                        return path;
                    }
                    if (seen.add(s.counterparty())) {
                        reachedBy.put(s.counterparty(), s);
                        next.add(s.counterparty());
                    }
                }
            }
            frontier = next;
        }
        return List.of();
    }
}
