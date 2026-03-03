package dev.tally.core;

import java.util.Objects;
import java.util.UUID;

/**
 * The ledger's own identity for a transfer, a typed wrapper over a UUID.
 *
 * Not the idempotency client key, which is a separate concept added with idempotency.
 */
public record TransferId(UUID value) {
    public TransferId {
        Objects.requireNonNull(value, "value");
    }

    public static TransferId newId() {
        return new TransferId(UUID.randomUUID());
    }
}
