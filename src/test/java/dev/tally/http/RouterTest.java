package dev.tally.http;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class RouterTest {

    private Router router() {
        Router r = new Router();
        ApiHandler stub = request -> null;
        r.add("POST", "/accounts", stub);
        r.add("GET", "/accounts/{id}", stub);
        r.add("GET", "/accounts/{id}/statement", stub);
        r.add("POST", "/transfers", stub);
        return r;
    }

    @Test
    void matchesExactPath() {
        Router.RouteResult.Matched m = assertInstanceOf(Router.RouteResult.Matched.class,
                router().match("POST", "/accounts"));
        assertEquals(Set.of(), m.pathParams().keySet());
    }

    @Test
    void capturesTemplateParam() {
        Router.RouteResult.Matched m = assertInstanceOf(Router.RouteResult.Matched.class,
                router().match("GET", "/accounts/abc"));
        assertEquals("abc", m.pathParams().get("id"));
    }

    @Test
    void matchesDeeperTemplate() {
        Router.RouteResult.Matched m = assertInstanceOf(Router.RouteResult.Matched.class,
                router().match("GET", "/accounts/abc/statement"));
        assertEquals("abc", m.pathParams().get("id"));
    }

    @Test
    void reportsMethodMismatchWithAllowedSet() {
        Router.RouteResult.MethodMismatch mm = assertInstanceOf(Router.RouteResult.MethodMismatch.class,
                router().match("DELETE", "/accounts/abc"));
        assertEquals(Set.of("GET"), mm.allowed());
    }

    @Test
    void reportsNoRouteForUnknownPath() {
        assertInstanceOf(Router.RouteResult.NoRoute.class, router().match("GET", "/nope"));
    }

    @Test
    void treatsTrailingSlashAsNoRoute() {
        assertInstanceOf(Router.RouteResult.NoRoute.class, router().match("POST", "/accounts/"));
    }

    @Test
    void rejectsEmptyParamSegment() {
        assertInstanceOf(Router.RouteResult.NoRoute.class, router().match("GET", "/accounts//statement"));
    }

    @Test
    void prefersMethodMismatchOverNoRoute() {
        // A path that matches a template with the wrong method is a 405, never a 404.
        assertInstanceOf(Router.RouteResult.MethodMismatch.class, router().match("PUT", "/transfers"));
    }
}
