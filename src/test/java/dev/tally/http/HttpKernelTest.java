package dev.tally.http;

import com.sun.net.httpserver.HttpServer;
import dev.tally.json.Json;
import dev.tally.json.JsonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpKernelTest {

    private HttpServer server;
    private URI base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        Router router = new Router();
        router.add("GET", "/echo", request -> Response.json(200, new JsonValue.JsonString("ok")));
        router.add("POST", "/echo", request -> Response.json(200, Json.parse(request.body())));
        router.add("GET", "/boom", request -> {
            throw new RuntimeException("boom-secret-internal-detail");
        });
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new HttpKernel(router));
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpResponse<String> send(HttpRequest request) throws Exception {
        return client.send(request, BodyHandlers.ofString());
    }

    private String code(HttpResponse<String> response) {
        JsonValue.JsonObject body = (JsonValue.JsonObject) Json.parse(response.body());
        JsonValue.JsonObject error = (JsonValue.JsonObject) body.members().get("error");
        return ((JsonValue.JsonString) error.members().get("code")).value();
    }

    @Test
    void unknownPathReturnsNotFoundEnvelope() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(base.resolve("/nope")).GET().build());
        assertEquals(404, r.statusCode());
        assertEquals("NOT_FOUND", code(r));
        assertTrue(r.headers().firstValue("Content-Type").orElseThrow().contains("application/json"));
    }

    @Test
    void wrongMethodReturns405WithAllowHeader() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(base.resolve("/echo"))
                .method("DELETE", BodyPublishers.noBody()).build());
        assertEquals(405, r.statusCode());
        assertEquals("METHOD_NOT_ALLOWED", code(r));
        String allow = r.headers().firstValue("Allow").orElseThrow();
        assertTrue(allow.contains("GET") && allow.contains("POST"), allow);
    }

    @Test
    void unparseableBodyReturnsMalformedJson() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(base.resolve("/echo"))
                .POST(BodyPublishers.ofString("{\"a\":")).build());
        assertEquals(400, r.statusCode());
        assertEquals("MALFORMED_JSON", code(r));
        assertTrue(r.body().contains("line") && r.body().contains("column"));
    }

    @Test
    void invalidUtf8BodyReturnsMalformedJson() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(base.resolve("/echo"))
                .POST(BodyPublishers.ofByteArray(new byte[]{(byte) 0xFF, (byte) 0xFE})).build());
        assertEquals(400, r.statusCode());
        assertEquals("MALFORMED_JSON", code(r));
    }

    @Test
    void oversizedBodyReturns413() throws Exception {
        byte[] big = new byte[HttpKernel.MAX_BODY_BYTES + 1];
        java.util.Arrays.fill(big, (byte) 'a');
        HttpResponse<String> r = send(HttpRequest.newBuilder(base.resolve("/echo"))
                .POST(BodyPublishers.ofByteArray(big)).build());
        assertEquals(413, r.statusCode());
        assertEquals("BODY_TOO_LARGE", code(r));
    }

    @Test
    void handlerCrashReturnsGenericInternal() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(base.resolve("/boom")).GET().build());
        assertEquals(500, r.statusCode());
        assertEquals("INTERNAL", code(r));
        assertFalse(r.body().contains("boom-secret-internal-detail"), "a stack trace must never reach the body");
    }

    @Test
    void servesManyConcurrentRequests() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            futures.add(client.sendAsync(HttpRequest.newBuilder(base.resolve("/echo")).GET().build(),
                    BodyHandlers.ofString()));
        }
        for (CompletableFuture<HttpResponse<String>> f : futures) {
            assertEquals(200, f.get().statusCode());
        }
    }
}
