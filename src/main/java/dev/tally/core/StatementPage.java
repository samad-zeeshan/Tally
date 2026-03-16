package dev.tally.core;

import java.util.List;

/**
 * A page of an account's statement, newest first. hasMore comes from the store's limit+1 probe; the
 * API layer turns it into the opaque wire cursor, so the store never depends on the HTTP layer.
 */
public record StatementPage(AccountId accountId, List<StatementLine> entries, boolean hasMore) {
    public StatementPage {
        entries = List.copyOf(entries);
    }
}
