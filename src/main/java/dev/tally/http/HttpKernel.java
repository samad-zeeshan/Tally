package dev.tally.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.tally.json.Json;
import dev.tally.json.JsonParseException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The single HttpHandler at "/": caps and decodes the body, routes, and turns any failure
 * into the error envelope. It is the one place an exception becomes a response.
 */
public final class HttpKernel implements HttpHandler {
    static final int MAX_BODY_BYTES = 16_384;

    private final Router router;

    public HttpKernel(Router router) {
        this.router = router;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        Response response;
        try {
            response = process(exchange);
        } catch (ApiException e) {
            response = Response.error(e.code, e.field, e.getMessage());
        } catch (JsonParseException e) {
            response = Response.error(ErrorCode.MALFORMED_JSON, "malformed JSON: " + e.getMessage());
        } catch (Throwable t) {
            // The stack trace goes to stderr only. It must never reach a response body.
            t.printStackTrace();
            response = Response.error(ErrorCode.INTERNAL, "internal error");
        }
        write(exchange, response);
    }

    private Response process(HttpExchange exchange) throws IOException {
        String body = readBody(exchange);
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        return switch (router.match(method, path)) {
            case Router.RouteResult.Matched(var handler, var params) ->
                    handler.handle(new Request(method, path, params, query, exchange.getRequestHeaders(), body));
            case Router.RouteResult.MethodMismatch(var allowed) ->
                    Response.error(ErrorCode.METHOD_NOT_ALLOWED, method + " is not allowed on " + path)
                            .withHeader("Allow", String.join(", ", allowed));
            case Router.RouteResult.NoRoute() ->
                    Response.error(ErrorCode.NOT_FOUND, "no route for " + method + " " + path);
        };
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return Map.of();
        }
        Map<String, String> params = new java.util.LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(decode(key), decode(value));
        }
        return params;
    }

    private static String decode(String s) {
        return java.net.URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    // Cap before trusting the body: read at most MAX+1 bytes, and if that many arrive it is too big.
    private String readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ApiException(ErrorCode.BODY_TOO_LARGE,
                    "request body must be at most " + MAX_BODY_BYTES + " bytes");
        }
        // Strict UTF-8: bad bytes are a client error, not a silent replacement character.
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException badUtf8) {
            throw new ApiException(ErrorCode.MALFORMED_JSON, "request body is not valid UTF-8");
        }
    }

    private void write(HttpExchange exchange, Response response) throws IOException {
        byte[] bytes = Json.write(response.body()).getBytes(StandardCharsets.UTF_8);
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "application/json; charset=utf-8");
        for (Map.Entry<String, String> h : response.extraHeaders().entrySet()) {
            headers.set(h.getKey(), h.getValue());
        }
        try {
            exchange.sendResponseHeaders(response.status(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } finally {
            exchange.close();
        }
    }
}
