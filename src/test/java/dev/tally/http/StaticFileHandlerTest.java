package dev.tally.http;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Serving the built client from a directory, and the two ways a request can try to leave it: a "/../"
 * path, and a link that resolves somewhere else.
 */
class StaticFileHandlerTest {
    @TempDir
    Path staticRoot;

    private HttpServer server;
    private URI base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        Files.writeString(staticRoot.resolve("index.html"), "<h1>Tally</h1>");
        Files.writeString(staticRoot.resolve("app.js"), "console.log('tally');");
        Files.writeString(staticRoot.resolve("health"), "DECOY");   // a decoy the /health route must beat
        Router router = new Router();
        router.add("GET", "/health", new HealthHandler());
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpKernel(router, new StaticFileHandler(staticRoot)));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(base.resolve(path)).GET().build(), BodyHandlers.ofString());
    }

    @Test
    void rootServesIndexHtml() throws Exception {
        HttpResponse<String> r = get("/");
        assertEquals(200, r.statusCode());
        assertEquals("<h1>Tally</h1>", r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElseThrow().contains("text/html"));
    }

    @Test
    void jsGetsJavascriptContentType() throws Exception {
        HttpResponse<String> r = get("/app.js");
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElseThrow().contains("text/javascript"));
    }

    @Test
    void missingFileReturns404() throws Exception {
        HttpResponse<String> r = get("/nope.css");
        assertEquals(404, r.statusCode());
        assertTrue(r.body().contains("NOT_FOUND"), r.body());
    }

    @Test
    void routesWinOverStaticFallback() throws Exception {
        // The decoy file named "health" in the static root loses, which is the whole reason static
        // serving is a fallback and not a "/" context of its own.
        HttpResponse<String> r = get("/health");
        assertEquals(200, r.statusCode());
        assertEquals("{\"status\":\"ok\"}", r.body());
    }

    @Test
    void pathTraversalIsRejected() {
        // Straight at the resolver, because an HTTP client normalizes "/../" out before it sends.
        StaticFileHandler handler = new StaticFileHandler(staticRoot);
        Request escape = new Request("GET", "/../pom.xml", Map.of(), Map.of(), new Headers(), () -> "");
        Optional<Response> served = handler.resolve(escape);
        assertTrue(served.isEmpty(), "a traversal escape must not resolve to a file");
    }

    @Test
    void aLinkInsideTheRootCannotReachOutsideIt(@TempDir Path elsewhere) throws Exception {
        // The case a startsWith plus isRegularFile pair lets through: the path reads as being under the
        // root the whole way, and the link points somewhere else entirely.
        Path secret = elsewhere.resolve("secret.txt");
        Files.writeString(secret, "TALLY_API_TOKEN=leaked");
        assumeTrue(link(staticRoot.resolve("escape"), elsewhere), "this platform will not create links unprivileged");

        StaticFileHandler handler = new StaticFileHandler(staticRoot);
        Request through = new Request("GET", "/escape/secret.txt", Map.of(), Map.of(), new Headers(), () -> "");
        assertTrue(handler.resolve(through).isEmpty(), "a link must not hand out a file outside the root");
        Request ordinary = new Request("GET", "/app.js", Map.of(), Map.of(), new Headers(), () -> "");
        assertFalse(handler.resolve(ordinary).isEmpty());
    }

    @Test
    void aLinkToAFileInsideTheRootStillServes() throws Exception {
        // The rule is "resolves inside the root", not "is not a link", which is what NOFOLLOW_LINKS on
        // the final component would have made it.
        assumeTrue(link(staticRoot.resolve("alias.js"), staticRoot.resolve("app.js")),
                "this platform will not create links unprivileged");
        StaticFileHandler handler = new StaticFileHandler(staticRoot);
        Request request = new Request("GET", "/alias.js", Map.of(), Map.of(), new Headers(), () -> "");
        assertFalse(handler.resolve(request).isEmpty());
    }

    // Windows will not create a symlink without elevation, so fall back to a directory junction, which is
    // unprivileged. Both are reparse points that toRealPath resolves, which is what the guard catches.
    private static boolean link(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException unprivileged) {
            if (!Files.isDirectory(target)) {
                return false;   // a junction only links directories
            }
            return new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).start().waitFor() == 0 && Files.exists(link);
        }
    }
}
