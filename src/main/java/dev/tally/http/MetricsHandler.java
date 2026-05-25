package dev.tally.http;

import dev.tally.obs.Metrics;

import java.nio.charset.StandardCharsets;

/**
 * GET /metrics in the Prometheus text format. Registered behind the bearer token, see ADR-0023.
 */
public final class MetricsHandler implements ApiHandler {
    private static final String CONTENT_TYPE = "text/plain; version=0.0.4; charset=utf-8";

    private final Metrics metrics;

    public MetricsHandler(Metrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public Response handle(Request request) {
        return Response.raw(200, metrics.render().getBytes(StandardCharsets.UTF_8), CONTENT_TYPE);
    }
}
