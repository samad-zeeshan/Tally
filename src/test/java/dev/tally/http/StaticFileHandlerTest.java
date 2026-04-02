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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // A decoy file named "health" sits in the static root, but the /health route runs before the
        // fallback, which is the whole reason static serving is a fallback and not a "/" context.
        HttpResponse<String> r = get("/health");
        assertEquals(200, r.statusCode());
        assertEquals("{\"status\":\"ok\"}", r.body());
    }

    @Test
    void pathTraversalIsRejected() {
        // Checked at the resolver: an HTTP client normalizes "/../" out of the path before it is sent,
        // so the guard is exercised directly with a raw escaping path.
        StaticFileHandler handler = new StaticFileHandler(staticRoot);
        Request escape = new Request("GET", "/../pom.xml", Map.of(), Map.of(), new Headers(), () -> "");
        Optional<Response> served = handler.resolve(escape);
        assertTrue(served.isEmpty(), "a traversal escape must not resolve to a file");
    }
}
