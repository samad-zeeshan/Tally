package dev.tally.core;

import java.util.Objects;

/**
 * A transfer as a client asks for it, carrying the idempotency key.
 *
 * The constructor guards structure only. The [A-Za-z0-9_-]{8,64} key format is an edge rule
 * checked once at the HTTP boundary; the record allows any non-blank key so the internal
 * opening path can use the system key open:&lt;accountId&gt;, which carries a colon.
 */
public record TransferRequest(String idempotencyKey, AccountId from, AccountId to, long amountMinor) {
    public TransferRequest {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("idempotency key is required");
        }
    }
}
