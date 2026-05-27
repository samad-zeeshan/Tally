package dev.tally.core;

import java.time.Instant;

/**
 * The store's answer to a transfer, a sealed value so the switch that maps it is exhaustive.
 *
 * KeyConflict and Replayed carry the idempotency semantics: a repeat of a recorded key replays
 * its first outcome, and the same key with a different request collides. Only Applied and
 * InsufficientFunds are ever recorded; the other four are never stored.
 */
public sealed interface TransferOutcome {
    // The two posting ids let a later reader, the fraud scorer, key on the exact rows this wrote.
    record Applied(TransferId id, long fromBalanceAfter, long toBalanceAfter, Instant at,
                   long debitPostingId, long creditPostingId) implements TransferOutcome {}

    record InsufficientFunds(AccountId account, long balanceMinor, long requestedMinor)
            implements TransferOutcome {}

    record UnknownAccount(AccountId account) implements TransferOutcome {}

    record ReservedAccount(AccountId account) implements TransferOutcome {}

    record KeyConflict(String idempotencyKey) implements TransferOutcome {}

    // Wraps the first recorded outcome, an Applied or InsufficientFunds, never itself.
    record Replayed(TransferOutcome first) implements TransferOutcome {}
}
