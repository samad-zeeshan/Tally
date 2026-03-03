package dev.tally.core;

import java.util.Objects;
import java.util.UUID;

/**
 * The identity of an account, a typed wrapper over a UUID.
 *
 * Generated in process so the store never round trips to learn an id, and ordered by
 * UUID.compareTo, which is the total order the fixed lock ordering relies on later.
 */
public record AccountId(UUID value) {
    public AccountId {
        Objects.requireNonNull(value, "value");
    }

    public static AccountId newId() {
        return new AccountId(UUID.randomUUID());
    }
}
