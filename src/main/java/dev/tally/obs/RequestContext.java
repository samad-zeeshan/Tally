package dev.tally.obs;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Per-request context, bound for the lifetime of one exchange through a ScopedValue.
 *
 * ScopedValue over ThreadLocal (JEP 506, final in Java 25): one immutable value, bound for exactly one
 * exchange on a virtual thread per exchange, with no mutation and no cleanup to forget.
 */
public record RequestContext(String requestId) {
    public static final ScopedValue<RequestContext> CURRENT = ScopedValue.newInstance();

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9._-]{8,64}");

    public static String currentIdOr(String fallback) {
        return CURRENT.isBound() ? CURRENT.get().requestId() : fallback;
    }

    // Honor a well-formed inbound id so a proxy or the client can correlate across hops; otherwise
    // generate. The charset gate is also what makes log injection through this header impossible.
    public static RequestContext fromHeaderOrNew(String inbound) {
        String id = inbound != null && VALID_ID.matcher(inbound).matches() ? inbound : UUID.randomUUID().toString();
        return new RequestContext(id);
    }
}
