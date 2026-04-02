package dev.tally.http;

import dev.tally.json.JsonValue;

import java.util.Map;

/**
 * Liveness route for the container healthcheck. It answers only that the HTTP layer is up; it touches
 * no database, so a database blip does not turn into a container restart loop. The deep check is
 * reconciliation.
 */
public final class HealthHandler implements ApiHandler {
    private static final JsonValue OK = new JsonValue.JsonObject(Map.of("status", new JsonValue.JsonString("ok")));

    @Override
    public Response handle(Request request) {
        return Response.json(200, OK);
    }
}
