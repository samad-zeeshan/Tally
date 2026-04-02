package dev.tally.ops;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Entry point for the Docker HEALTHCHECK: exit 0 if GET /health returns 200, 1 otherwise. Its real
 * verification is `docker inspect` showing the container healthy, not a unit test; a mock-heavy test of
 * a dozen lines of stdlib glue would prove nothing.
 */
public final class HealthProbe {
    private HealthProbe() {}

    public static void main(String[] args) {
        String port = System.getenv().getOrDefault("TALLY_PORT", "8080");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            System.exit(response.statusCode() == 200 ? 0 : 1);
        } catch (Exception unreachable) {
            System.exit(1);
        }
    }
}
