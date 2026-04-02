package dev.tally.http;

import com.sun.net.httpserver.HttpServer;
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
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HealthEndpointTest {
    private HttpServer server;
    private URI base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        Router router = new Router();
        router.add("GET", "/health", new HealthHandler());
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

    @Test
    void healthReturnsOkWithoutAuth() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(base.resolve("/health")).GET().build());
        assertEquals(200, r.statusCode());
        assertEquals("{\"status\":\"ok\"}", r.body());
    }

    @Test
    void healthRejectsNonGet() throws Exception {
        HttpResponse<String> r = send(HttpRequest.newBuilder(base.resolve("/health")).POST(BodyPublishers.noBody()).build());
        assertEquals(405, r.statusCode());
        assertEquals("GET", r.headers().firstValue("Allow").orElseThrow());
    }
}
