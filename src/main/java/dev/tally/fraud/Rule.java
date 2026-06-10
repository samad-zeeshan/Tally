package dev.tally.fraud;

import java.util.List;

/**
 * One deterministic check over a posting and its account's window. Zero points means it did not fire,
 * and negative points are a discount.
 */
public interface Rule {
    String name();

    int points(PostingEvent event, Window window);

    // The earlier postings a firing rests on, for the alert's explanation. Most rules read a count or the
    // payment itself and have none to name.
    default List<Long> evidence(PostingEvent event, Window window) {
        return List.of();
    }
}
