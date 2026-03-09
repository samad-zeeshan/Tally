package dev.tally.http;

/**
 * A handler for one matched route: a request in, a response out.
 */
@FunctionalInterface
public interface ApiHandler {
    Response handle(Request request);
}
