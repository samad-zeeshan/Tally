package dev.tally.fraud;

import dev.tally.core.AccountId;

import java.time.Instant;
import java.util.List;

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
}
