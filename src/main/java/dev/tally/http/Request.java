package dev.tally.http;

import com.sun.net.httpserver.Headers;

import java.util.Map;

/**
 * One HTTP request, already routed: the captured path params, the query params, and the decoded body.
 */
public record Request(String method, String path, Map<String, String> pathParams,
                      Map<String, String> queryParams, Headers headers, String body) {}
