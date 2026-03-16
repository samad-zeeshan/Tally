package dev.tally.core;

/**
 * One account where the stored balance and the balance derived from its postings disagree.
 */
public record Drift(AccountId accountId, long storedBalanceMinor, long derivedBalanceMinor) {
    public long driftMinor() {
        return storedBalanceMinor - derivedBalanceMinor;
    }
}
