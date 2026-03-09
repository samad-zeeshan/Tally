package dev.tally.http;

import com.sun.net.httpserver.Headers;

import java.util.Map;

/**
 * One HTTP request, already routed: the captured path params and the decoded body.
 */
public record Request(String method, String path, Map<String, String> pathParams,
                      Headers headers, String body) {}
