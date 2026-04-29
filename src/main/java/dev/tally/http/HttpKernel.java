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
import java.util.Optional;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The single HttpHandler at "/": binds the request id, throttles, routes, dispatches, and turns any
 * failure into the error envelope. It is the one place an exception becomes a response.
 *
 * The body is read lazily, inside the matched handler, not here: routing and auth run first so an
 * unauthenticated request is rejected before its body (and the 413 cap) is ever touched.
 */
public final class HttpKernel implements HttpHandler {
    static final int MAX_BODY_BYTES = 4_096;

    // A hash is what keeps script-src free of 'unsafe-inline' for the sake of one snippet: web/index.html
    // sets the theme before first paint. It covers exact bytes, so editing that snippet without editing
    // this line breaks the theme, and SecurityHeadersTest re-derives it from the file to catch the drift.
    static final String THEME_SCRIPT_HASH = "sha256-HVcZT6+dmTYvPKI/VaotaeNmxttNrYO9NkQqS8Ut5r8=";

    // On every response, because this server also serves the built SPA at the same origin, and a header
    // that covers only some responses is not a control. Vite emits external module scripts, so only
    // style-src has to allow inline, for the style attributes React writes. X-Frame-Options is
    // SAMEORIGIN rather than DENY so the project keeps the option of embedding its own UI.
    //
    // No Strict-Transport-Security: sent over http://localhost it pins that whole origin to https in the
    // developer's browser for max-age, which breaks every other local project and is painful to undo.
    // The proxy that owns the certificate should send it.
    private static final Map<String, String> SECURITY_HEADERS = Map.of(
            "X-Content-Type-Options", "nosniff",
            "X-Frame-Options", "SAMEORIGIN",
            "Referrer-Policy", "no-referrer",
            "Permissions-Policy", "camera=(), microphone=(), geolocation=()",
            "Content-Security-Policy",
            "default-src 'self'; script-src 'self' '" + THEME_SCRIPT_HASH + "'; "
                    + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; "
                    + "connect-src 'self'; object-src 'none'; base-uri 'none'; "
                    + "frame-ancestors 'self'; form-action 'self'");

    private static final Logger LOG = Logs.get(HttpKernel.class);

    private final Router router;
    private final StaticFileHandler staticFiles;   // null unless TALLY_STATIC_DIR is set
    private final RateLimiter rateLimiter;

    public HttpKernel(Router router) {
        this(router, null);
    }

    public HttpKernel(Router router, StaticFileHandler staticFiles) {
        this(router, staticFiles, new RateLimiter());
    }

    public HttpKernel(Router router, StaticFileHandler staticFiles, RateLimiter rateLimiter) {
        this.router = router;
        this.staticFiles = staticFiles;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        RequestContext ctx = RequestContext.fromHeaderOrNew(exchange.getRequestHeaders().getFirst("X-Request-Id"));
        long startNanos = System.nanoTime();
        String method = exchange.getRequestMethod();
        String rawPath = exchange.getRequestURI().getPath();
        Router.RouteResult route = router.match(method, rawPath);
        String client = clientAddress(exchange);

        // The envelope and the access log both read the id from RequestContext.CURRENT, so everything
        // that needs it runs inside the binding. ScopedValue.run gives nothing back, hence the holder.
        Response[] holder = new Response[1];
        ScopedValue.where(RequestContext.CURRENT, ctx).run(() -> {
            Response response = throttled(exchange, route, method, rawPath, client);
            long ms = (System.nanoTime() - startNanos) / 1_000_000;
            logAccess(method, logPath(route, rawPath), response.status(), ms);
            holder[0] = response;
        });
        write(exchange, holder[0].withHeader("X-Request-Id", ctx.requestId()));
    }

    // Before dispatch, so a throttled caller never reaches a handler or the store. A 401 is the only
    // brute-force signal the edge gets, since Auth says nothing about why a token was rejected.
    private Response throttled(HttpExchange exchange, Router.RouteResult route, String method,
                               String rawPath, String client) {
        RateLimiter.Decision decision = rateLimiter.check(client);
        if (!decision.allowed()) {
            return Response.error(ErrorCode.RATE_LIMITED, "too many requests, slow down and retry later")
                    .withHeader("Retry-After", Integer.toString(decision.retryAfterSeconds()));
        }
        Response response = dispatch(exchange, route, method, rawPath);
        if (response.status() == 401) {
            rateLimiter.recordAuthFailure(client);
        }
        return response;
    }

    // The socket peer, never X-Forwarded-For: a client that picks its own key can mint buckets without
    // limit. A missing remote address, possible on a closed exchange, shares one bucket rather than
    // escaping the limiter.
    private static String clientAddress(HttpExchange exchange) {
        var remote = exchange.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
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
                // Static files are the fallback, not a competing context, so /accounts always wins over
                // a file that happens to be named the same.
                case Router.RouteResult.NoRoute() -> {
                    if (staticFiles != null) {
                        Request request = new Request(method, rawPath, Map.of(), Map.of(),
                                exchange.getRequestHeaders(), bodySupplier(exchange));
                        Optional<Response> served = staticFiles.resolve(request);
                        if (served.isPresent()) {
                            yield served.get();
                        }
                    }
                    yield Response.error(ErrorCode.NOT_FOUND, "no route for " + method + " " + rawPath);
                }
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

    // The route template for a match, never the raw path, which carries account ids. An unmatched path is
    // cut down to printable ASCII so it cannot inject newlines into the log.
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

    // One byte past the cap is read, never the whole stream: that extra byte is how an oversized body is
    // recognised without buffering it.
    private static String readBody(HttpExchange exchange) {
        byte[] bytes;
        try {
            bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (bytes.length > MAX_BODY_BYTES) {
            throw new ApiException(ErrorCode.BODY_TOO_LARGE,
                    "request body must be " + MAX_BODY_BYTES + " bytes or fewer");
        }
        // REPORT, not replace: bad bytes are a client error, not a quiet U+FFFD in somebody's name.
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
        byte[] bytes;
        String contentType;
        if (response.rawBody() != null) {
            bytes = response.rawBody();
            contentType = response.contentType();
        } else {
            bytes = Json.write(response.body()).getBytes(StandardCharsets.UTF_8);
            contentType = "application/json; charset=utf-8";
        }
        var headers = exchange.getResponseHeaders();
        headers.set("Content-Type", contentType);
        // Every response leaves through here, so there is no path that skips them. Per-response headers
        // go on after, which lets a handler override one deliberately.
        for (Map.Entry<String, String> h : SECURITY_HEADERS.entrySet()) {
            headers.set(h.getKey(), h.getValue());
        }
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
