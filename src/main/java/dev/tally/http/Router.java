package dev.tally.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * A method-plus-template route table that captures {id} segments.
 */
public final class Router {
    private record Route(String method, String template, String[] segments, ApiHandler handler) {}

    private final List<Route> routes = new ArrayList<>();

    public void add(String method, String template, ApiHandler handler) {
        routes.add(new Route(method, template, split(template), handler));
    }

    public RouteResult match(String method, String path) {
        String[] pathSegments = split(path);
        // Collect the methods any template allows for this path. Checking the path first, then the
        // method, is what makes a 405 distinguishable from a 404.
        Set<String> allowed = new TreeSet<>();
        for (Route route : routes) {
            Map<String, String> params = captured(route.segments(), pathSegments);
            if (params == null) {
                continue;
            }
            if (route.method().equals(method)) {
                return new RouteResult.Matched(route.handler(), params, route.template());
            }
            allowed.add(route.method());
        }
        if (!allowed.isEmpty()) {
            return new RouteResult.MethodMismatch(allowed);
        }
        return new RouteResult.NoRoute();
    }

    private static Map<String, String> captured(String[] template, String[] path) {
        if (template.length != path.length) {
            return null;
        }
        Map<String, String> params = new LinkedHashMap<>();
        for (int i = 0; i < template.length; i++) {
            String t = template[i];
            if (t.length() >= 2 && t.charAt(0) == '{' && t.charAt(t.length() - 1) == '}') {
                if (path[i].isEmpty()) {
                    return null;   // {id} captures a non-empty segment, so // never matches
                }
                params.put(t.substring(1, t.length() - 1), path[i]);
            } else if (!t.equals(path[i])) {
                return null;
            }
        }
        return params;
    }

    // split with limit -1 keeps trailing empty strings, so /accounts/ does not equal /accounts.
    private static String[] split(String pathOrTemplate) {
        return pathOrTemplate.split("/", -1);
    }

    public sealed interface RouteResult {
        // template is the matched route's path template, e.g. /accounts/{id}, so the access log can
        // record it instead of the raw path, which carries account ids.
        record Matched(ApiHandler handler, Map<String, String> pathParams, String template) implements RouteResult {}
        record MethodMismatch(Set<String> allowed) implements RouteResult {}
        record NoRoute() implements RouteResult {}
    }
}
