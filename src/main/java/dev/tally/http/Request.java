package dev.tally.http;

import com.sun.net.httpserver.Headers;

import java.util.Map;
import java.util.function.Supplier;

/**
 * One routed HTTP request: captured path params, query params, headers, and a lazy body.
 *
 * The body is a supplier, not a pre-read string, so auth can reject a request from its headers
 * alone before the body is ever touched; that is what keeps a 401 from consuming an idempotency key.
 */
public record Request(String method, String path, Map<String, String> pathParams,
                      Map<String, String> queryParams, Headers headers, Supplier<String> bodySupplier) {
    public String body() {
        return bodySupplier.get();
    }
}
