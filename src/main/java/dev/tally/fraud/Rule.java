package dev.tally.fraud;

/**
 * One deterministic check over a posting and its account's window. Zero points means it did not fire.
 */
public interface Rule {
    String name();

    int points(PostingEvent event, Window window);
}
