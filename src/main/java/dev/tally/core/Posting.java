package dev.tally.core;

import java.util.Objects;

/**
 * One signed amount applied to one account as part of a transfer.
 *
 * amountMinor is signed: positive credits the account, negative debits it. A zero amount is
 * constructible on purpose, so a test can build an odd posting list and watch it be rejected.
 */
public record Posting(AccountId accountId, long amountMinor) {
    public Posting {
        Objects.requireNonNull(accountId, "accountId");
    }
}
