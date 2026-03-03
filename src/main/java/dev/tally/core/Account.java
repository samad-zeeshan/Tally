package dev.tally.core;

import java.time.Instant;
import java.util.Objects;

/**
 * An account and its current balance in integer minor units.
 *
 * allowNegative marks the single account, world, permitted below zero by rule. There is
 * deliberately no non-negative guard here: the math enforces it for ordinary accounts, and
 * baking it into the type would make world unrepresentable.
 */
public record Account(AccountId id, String name, long balanceMinor, boolean allowNegative,
                      Instant createdAt) {
    public Account {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
