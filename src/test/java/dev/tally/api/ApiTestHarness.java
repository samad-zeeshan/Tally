package dev.tally.api;

import dev.tally.ApiServer;
import dev.tally.json.Json;
import dev.tally.json.JsonValue;
import dev.tally.store.InMemoryStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.UUID;

/**
 * A fresh server over a fresh in-memory store per test. The error helpers read error.code and
 * message only, never whole bodies, so a later requestId or field addition does not churn a test.
 *
 * Every request goes out authenticated by default so the account and transfer tests stay about their own
 * behaviour; the auth tests build raw requests through send to exercise the missing/wrong-token paths.
 */
abstract class ApiTestHarness {
    protected static final String TOKEN = "test-token-0123456789";   // >= 16 chars, the fail-closed floor

    protected ApiServer server;
    protected URI base;
    protected final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void startServer() {
        server = new ApiServer(0, new InMemoryStore(), TOKEN);
        server.start();
        base = URI.create("http://127.0.0.1:" + server.port());
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    // Reads carry the token too: every route but /health is protected.
    protected HttpResponse<String> get(String path) {
        return send(HttpRequest.newBuilder(base.resolve(path)).header("Authorization", "Bearer " + TOKEN).GET().build());
    }

    protected HttpResponse<String> post(String path, String body, String... headerPairs) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .header("Authorization", "Bearer " + TOKEN)
                .POST(BodyPublishers.ofString(body));
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            builder.header(headerPairs[i], headerPairs[i + 1]);
        }
        return send(builder.build());
    }

    protected HttpResponse<String> method(String method, String path) {
        return send(HttpRequest.newBuilder(base.resolve(path)).method(method, BodyPublishers.noBody()).build());
    }

    protected HttpResponse<String> transfer(String from, String to, long amount, String key) {
        String body = "{\"fromAccountId\":\"" + from + "\",\"toAccountId\":\"" + to + "\",\"amountMinor\":" + amount + "}";
        return post("/transfers", body, "Idempotency-Key", key);
    }

    protected String freshKey() {
        return "key-" + UUID.randomUUID();
    }

    protected HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, BodyHandlers.ofString());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    protected JsonValue.JsonObject body(HttpResponse<String> r) {
        return (JsonValue.JsonObject) Json.parse(r.body());
    }

    protected String errorCode(HttpResponse<String> r) {
        JsonValue.JsonObject error = (JsonValue.JsonObject) body(r).members().get("error");
        return ((JsonValue.JsonString) error.members().get("code")).value();
    }

    protected String errorField(HttpResponse<String> r) {
        JsonValue.JsonObject error = (JsonValue.JsonObject) body(r).members().get("error");
        JsonValue field = error.members().get("field");
        return field == null ? null : ((JsonValue.JsonString) field).value();
    }

    protected String errorMessage(HttpResponse<String> r) {
        JsonValue.JsonObject error = (JsonValue.JsonObject) body(r).members().get("error");
        return ((JsonValue.JsonString) error.members().get("message")).value();
    }

    protected String stringField(HttpResponse<String> r, String name) {
        return ((JsonValue.JsonString) body(r).members().get(name)).value();
    }

    protected long longField(HttpResponse<String> r, String name) {
        return ((JsonValue.JsonNumber) body(r).members().get(name)).value();
    }

    protected String createAccount(String name, long opening) {
        HttpResponse<String> r = post("/accounts", "{\"name\":\"" + name + "\",\"openingBalanceMinor\":" + opening + "}");
        return stringField(r, "id");
    }

    protected long balanceOf(String id) {
        return longField(get("/accounts/" + id), "balanceMinor");
    }
}
