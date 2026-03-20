package dev.tally.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import dev.tally.json.Json;
import dev.tally.json.JsonParseException;
import dev.tally.obs.Logs;
import dev.tally.obs.RequestContext;

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
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single HttpHandler at "/": binds the request id, routes, dispatches, and turns any failure into
 * the error envelope. It is the one place an exception becomes a response.
 *
 * The body is read lazily, inside the matched handler, not here: routing and auth run first so an
 * unauthenticated request is rejected before its body (and the 413 cap) is ever touched.
 */
public final class HttpKernel implements HttpHandler {
    static final int MAX_BODY_BYTES = 4_096;

    private static final Logger LOG = Logs.get(HttpKernel.class);

    private final Router router;

    public HttpKernel(Router router) {
        this.router = router;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        RequestContext ctx = RequestContext.fromHeaderOrNew(exchange.getRequestHeaders().getFirst("X-Request-Id"));
        long startNanos = System.nanoTime();
        String method = exchange.getRequestMethod();
        String rawPath = exchange.getRequestURI().getPath();
        Router.RouteResult route = router.match(method, rawPath);

        // Everything that reads the request id happens inside the binding: the error envelope's requestId
        // and the access log both pull it from RequestContext.CURRENT. The write happens after, with the
        // id set explicitly on the header, so a response outside the scope still carries it.
        Response[] holder = new Response[1];
        ScopedValue.where(RequestContext.CURRENT, ctx).run(() -> {
            Response response = dispatch(exchange, route, method, rawPath);
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            logAccess(method, logPath(route, rawPath), response.status(), ms);
            holder[0] = response;
        });
        write(exchange, holder[0].withHeader("X-Request-Id", ctx.requestId()));
    }

    private Response dispatch(HttpExchange exchange, Router.RouteResult route, String method, String rawPath) {
        try {
            return switch (route) {
                case Router.RouteResult.Matched(var handler, var params, var _) ->
                        handler.handle(new Request(method, rawPath, params,
                                parseQuery(exchange.getRequestURI().getRawQuery()),
                                exchange.getRequestHeaders(), bodySupplier(exchange)));
                case Router.RouteResult.MethodMismatch(var allowed) ->
                        Response.error(ErrorCode.METHOD_NOT_ALLOWED, method + " is not allowed on " + rawPath)
                                .withHeader("Allow", String.join(", ", allowed));
                case Router.RouteResult.NoRoute() ->
                        Response.error(ErrorCode.NOT_FOUND, "no route for " + method + " " + rawPath);
            };
        } catch (ApiException e) {
            return Response.error(e.code, e.field, e.getMessage());
        } catch (JsonParseException e) {
            return Response.error(ErrorCode.MALFORMED_JSON, "malformed JSON: " + e.getMessage());
        } catch (Throwable t) {
            // The stack trace goes to the log only. It must never reach a response body.
            LOG.log(Level.SEVERE, "unhandled error", t);
            return Response.error(ErrorCode.INTERNAL, "internal error");
        }
    }

    // Log the route template for a match, never the raw path, which carries account ids. An unmatched
    // path is sanitized (printable ASCII, truncated) so it cannot inject newlines into the log.
    private static String logPath(Router.RouteResult route, String rawPath) {
        return route instanceof Router.RouteResult.Matched(var _, var _, var template) ? template : sanitize(rawPath);
    }

    private static String sanitize(String path) {
        StringBuilder clean = new StringBuilder(Math.min(path.length(), 100));
        for (int i = 0; i < path.length() && i < 100; i++) {
            char c = path.charAt(i);
            clean.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return clean.toString();
    }

    private static void logAccess(String method, String path, int status, long ms) {
        Level level = status >= 500 ? Level.SEVERE : status >= 400 ? Level.WARNING : Level.INFO;
        LOG.log(level, "method=" + method + " path=" + path + " status=" + status + " ms=" + ms);
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
