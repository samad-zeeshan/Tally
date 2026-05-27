package dev.tally.fraud;

/**
 * Where the transfer path hands off an applied posting. Implementations must return at once and never
 * throw; a durable broker would plug in here (ADR-0024).
 */
public interface PostingSink {
    PostingSink NONE = event -> {};

    void publish(PostingEvent event);
}
