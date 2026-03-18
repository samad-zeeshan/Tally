package dev.tally.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.tally.json.Json;
import dev.tally.json.JsonParseException;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The single HttpHandler at "/": routes, dispatches, and turns any failure into the error envelope.
 * It is the one place an exception becomes a response.
 *
 * The body is read lazily, inside the matched handler, not here: routing and auth run first so an
 * unauthenticated request is rejected before its body (and the 413 cap) is ever touched.
 */
public final class HttpKernel implements HttpHandler {
    static final int MAX_BODY_BYTES = 4_096;

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

    private Response process(HttpExchange exchange) {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        return switch (router.match(method, path)) {
            case Router.RouteResult.Matched(var handler, var params) ->
                    handler.handle(new Request(method, path, params, query,
                            exchange.getRequestHeaders(), bodySupplier(exchange)));
            case Router.RouteResult.MethodMismatch(var allowed) ->
                    Response.error(ErrorCode.METHOD_NOT_ALLOWED, method + " is not allowed on " + path)
                            .withHeader("Allow", String.join(", ", allowed));
            case Router.RouteResult.NoRoute() ->
                    Response.error(ErrorCode.NOT_FOUND, "no route for " + method + " " + path);
        };
    }

    // Read the body at most once, on demand: a handler may read it more than once (validate, then log),
    // and an auth wrapper must be able to skip it entirely.
    private static Supplier<String> bodySupplier(HttpExchange exchange) {
        return new Supplier<>() {
            private String cached;
            private boolean read;

            @Override
            public String get() {
                if (!read) {
                    cached = readBody(exchange);
                    read = true;
                }
                return cached;
            }
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
    private static String readBody(HttpExchange exchange) {
        byte[] bytes;
        try {
            bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            // A broken request stream is not a clean client error we can envelope; let it surface as 500.
            throw new UncheckedIOException(e);
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ApiException(ErrorCode.BODY_TOO_LARGE,
                    "request body must be " + MAX_BODY_BYTES + " bytes or fewer");
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
