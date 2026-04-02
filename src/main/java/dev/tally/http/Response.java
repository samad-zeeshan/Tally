package dev.tally.http;

import dev.tally.json.JsonValue;
import dev.tally.obs.RequestContext;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A response to write back. It is either a JSON body (the API surface) or raw bytes with an explicit
 * content type (a static file); the kernel picks the path from which one is set.
 */
public record Response(int status, JsonValue body, byte[] rawBody, String contentType, Map<String, String> extraHeaders) {
    public Response {
        extraHeaders = Map.copyOf(extraHeaders);
    }

    public static Response json(int status, JsonValue body) {
        return new Response(status, body, null, null, Map.of());
    }

    // Raw bytes with a content type, for static assets the JSON writer cannot render.
    public static Response raw(int status, byte[] rawBody, String contentType) {
        return new Response(status, null, rawBody, contentType, Map.of());
    }

    public static Response error(ErrorCode code, String message) {
        return new Response(code.status, errorBody(code, null, message), null, null, Map.of());
    }

    public static Response error(ErrorCode code, String field, String message) {
        return new Response(code.status, errorBody(code, field, message), null, null, Map.of());
    }

    public Response withHeader(String name, String value) {
        Map<String, String> headers = new LinkedHashMap<>(extraHeaders);
        headers.put(name, value);
        return new Response(status, body, rawBody, contentType, headers);
    }

    // The one place the error envelope shape lives, so the kernel's exception path and the
    // handlers' outcome path render identical bodies. requestId is read from the bound context and
    // equals the X-Request-Id response header, so a caller can quote it against the logs.
    private static JsonValue errorBody(ErrorCode code, String field, String message) {
        Map<String, JsonValue> error = new LinkedHashMap<>();
        error.put("code", new JsonValue.JsonString(code.name()));
        error.put("message", new JsonValue.JsonString(message));
        if (field != null) {
            error.put("field", new JsonValue.JsonString(field));
        }
        error.put("requestId", new JsonValue.JsonString(RequestContext.currentIdOr("-")));
        Map<String, JsonValue> envelope = new LinkedHashMap<>();
        envelope.put("error", new JsonValue.JsonObject(error));
        return new JsonValue.JsonObject(envelope);
    }
}
