package dev.tally.core;

import java.util.List;
import java.util.Objects;

/**
 * A requested movement, a set of postings that must sum to zero.
 */
public record Transfer(TransferId id, List<Posting> postings) {
    public Transfer {
        Objects.requireNonNull(id, "id");
        // Defensive copy, which also null-checks the elements and makes the list unmodifiable.
        postings = List.copyOf(postings);
        if (postings.isEmpty()) {
            throw new IllegalArgumentException("transfer has no postings");
        }
    }

    // between is the only place non-positive amounts are rejected: a negative amount here would
    // silently reverse direction, which no downstream math can see from raw postings. The guard
    // excludes Long.MIN_VALUE, so -amountMinor needs no negateExact.
    public static Transfer between(AccountId from, AccountId to, long amountMinor) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amountMinor);
        }
        return new Transfer(TransferId.newId(),
                List.of(new Posting(from, -amountMinor), new Posting(to, amountMinor)));
    }
}
