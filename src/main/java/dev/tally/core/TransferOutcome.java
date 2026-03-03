package dev.tally.core;

import java.time.Instant;

/**
 * The store's answer to a transfer, a sealed value so the switch that maps it is exhaustive.
 *
 * The two idempotency cases, KeyConflict and Replayed, arrive with the client key. This stage
 * produces exactly the four cases below.
 */
public sealed interface TransferOutcome {
    record Applied(TransferId id, long fromBalanceAfter, long toBalanceAfter, Instant at)
            implements TransferOutcome {}

    record InsufficientFunds(AccountId account, long balanceMinor, long requestedMinor)
            implements TransferOutcome {}

    record UnknownAccount(AccountId account) implements TransferOutcome {}

    record ReservedAccount(AccountId account) implements TransferOutcome {}
}
