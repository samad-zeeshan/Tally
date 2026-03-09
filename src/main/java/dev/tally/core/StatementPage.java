package dev.tally.core;

import java.util.List;

/**
 * A page of an account's statement, newest first. Gains a nextCursor field when pagination lands.
 */
public record StatementPage(AccountId accountId, List<StatementLine> entries) {
    public StatementPage {
        entries = List.copyOf(entries);
    }
}
