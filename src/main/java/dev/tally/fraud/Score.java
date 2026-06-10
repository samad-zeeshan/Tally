package dev.tally.fraud;

import dev.tally.core.AccountId;
import dev.tally.core.TransferId;

import java.time.Instant;
import java.util.List;

/**
 * A scored debit posting: the points from 0 to 100, the names of the rules that moved them, and why.
 */
public record Score(long postingId, TransferId transferId, AccountId account, AccountId counterparty,
                    long amountMinor, int score, List<String> rules, Instant eventAt, Instant scoredAt,
                    Explanation explanation) {
    // Two strong rules or three ordinary ones. The offline evaluation reports precision and recall at
    // this line and AUROC across every line, so it can be moved with the numbers in hand.
    public static final int FLAG_THRESHOLD = 40;

    public Score {
        rules = List.copyOf(rules);
    }

    public Score(long postingId, TransferId transferId, AccountId account, AccountId counterparty,
                 long amountMinor, int score, List<String> rules, Instant eventAt, Instant scoredAt) {
        this(postingId, transferId, account, counterparty, amountMinor, score, rules, eventAt, scoredAt, Explanation.NONE);
    }

    public boolean flagged() {
        return score >= FLAG_THRESHOLD;
    }
}
