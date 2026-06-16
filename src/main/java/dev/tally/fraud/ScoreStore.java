package dev.tally.fraud;

import dev.tally.core.AccountId;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Where scores live, and where the scorer reads each account's window back from. In memory or Postgres.
 */
public interface ScoreStore {
    // False when the posting already has a score. This, not a check before it, is the idempotency line.
    boolean insertIfAbsent(Score score);

    boolean contains(long postingId);

    // The account's own scored payments with a lower posting id, newest first, at most limit.
    List<Score> outgoingBefore(AccountId account, long beforePostingId, int limit);

    // Scored payments to the account at or after since, with a lower posting id, newest first.
    List<Score> incomingSince(AccountId account, Instant since, long beforePostingId);

    // The account's latest scores with their explanations, for the risk endpoint. The scorer's own reads
    // above may leave the explanation out, since no rule reads it.
    default List<Score> recent(AccountId account, int limit) {
        return outgoingBefore(account, Long.MAX_VALUE, limit);
    }

    // Distinct accounts that paid this one below the posting, counting no further than cap.
    default int distinctPayersBefore(AccountId account, long beforePostingId, int cap) {
        Set<AccountId> payers = new HashSet<>();
        for (Score s : incomingSince(account, Instant.EPOCH, beforePostingId)) {
            payers.add(s.account());
        }
        return Math.min(cap, payers.size());
    }

    default int outgoingCountBefore(AccountId account, long beforePostingId, int cap) {
        return outgoingBefore(account, beforePostingId, cap).size();
    }

    // Payments out at or after since, below the posting, newest first, at most limit.
    default List<Score> outgoingSince(AccountId account, Instant since, long beforePostingId, int limit) {
        return outgoingBefore(account, beforePostingId, Integer.MAX_VALUE).stream()
                .filter(s -> !s.eventAt().isBefore(since)).limit(limit).toList();
    }
}
