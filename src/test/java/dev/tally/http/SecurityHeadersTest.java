package dev.tally.http;

import com.sun.net.httpserver.HttpServer;
import dev.tally.json.JsonValue;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same origin serves the API and the built client, so the browser controls have to be on every
 * response: a JSON success, a JSON error, and a static file alike.
 */
class SecurityHeadersTest {
    @TempDir
    Path staticRoot;

    private HttpServer server;
    private URI base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        Files.writeString(staticRoot.resolve("index.html"), "<h1>Tally</h1>");
        Router router = new Router();
        router.add("GET", "/echo", request -> Response.json(200, new JsonValue.JsonString("ok")));
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

    private String header(HttpResponse<String> r, String name) {
        return r.headers().firstValue(name).orElseThrow(() -> new AssertionError("missing header " + name));
    }

    private void assertHardened(HttpResponse<String> r) {
        assertEquals("nosniff", header(r, "X-Content-Type-Options"));
        // SAMEORIGIN, not DENY: the project keeps the option of embedding its own UI.
        assertEquals("SAMEORIGIN", header(r, "X-Frame-Options"));
        assertEquals("no-referrer", header(r, "Referrer-Policy"));
        String permissions = header(r, "Permissions-Policy");
        assertTrue(permissions.contains("camera=()"), permissions);
        assertTrue(permissions.contains("microphone=()"), permissions);
        assertTrue(permissions.contains("geolocation=()"), permissions);
    }

    @Test
    void jsonSuccessCarriesTheHeaders() throws Exception {
        assertHardened(get("/echo"));
    }

    @Test
    void staticFilesCarryTheHeaders() throws Exception {
        HttpResponse<String> r = get("/");
        assertEquals(200, r.statusCode());
        assertHardened(r);
    }

    @Test
    void errorResponsesCarryTheHeaders() throws Exception {
        HttpResponse<String> r = get("/nope");
        assertEquals(404, r.statusCode());
        assertHardened(r);
    }

    @Test
    void theCspFitsAViteBuiltClient() throws Exception {
        String csp = header(get("/"), "Content-Security-Policy");
        assertTrue(csp.contains("default-src 'self'"), csp);
        // Vite emits external module scripts, so scripts never need to be inlined. React writes inline
        // style attributes, so styles do; the loosening is confined to exactly one directive.
        assertTrue(csp.contains("script-src 'self'"), csp);
        assertFalse(csp.contains("script-src 'self' 'unsafe-inline'"), csp);
        assertFalse(csp.contains("unsafe-eval"), csp);
        assertTrue(csp.contains("style-src 'self' 'unsafe-inline'"), csp);
        // The favicon is a data: URI in index.html, so img-src has to allow data: and nothing more.
        assertTrue(csp.contains("img-src 'self' data:"), csp);
        assertTrue(csp.contains("object-src 'none'"), csp);
        assertTrue(csp.contains("base-uri 'none'"), csp);
        assertTrue(csp.contains("frame-ancestors 'self'"), csp);
    }

    @Test
    void theCspHashMatchesTheClientsInlineThemeScript() throws Exception {
        // The one inline script the client keeps. Its hash is what lets script-src stay free of
        // 'unsafe-inline', and a hash covers exact bytes, so this re-derives it from the file rather than
        // trusting that whoever edits the snippet remembers to edit the header too. Vite copies the tag
        // through untouched, so hashing the source is hashing what ships.
        Path clientHtml = Path.of("web", "index.html");
        assertTrue(Files.isRegularFile(clientHtml), "expected the client's index.html at " + clientHtml.toAbsolutePath());
        Matcher inline = Pattern.compile("<script>(.*?)</script>", Pattern.DOTALL)
                .matcher(Files.readString(clientHtml, StandardCharsets.UTF_8));
        assertTrue(inline.find(), "the client no longer has an inline script; drop the hash from the CSP");
        String expected = "sha256-" + Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-256").digest(inline.group(1).getBytes(StandardCharsets.UTF_8)));
        assertEquals(expected, HttpKernel.THEME_SCRIPT_HASH,
                "the inline theme script changed, so the CSP hash has to be regenerated or the theme breaks");
        assertTrue(header(get("/"), "Content-Security-Policy").contains(expected));
    }

    @Test
    void noHstsOverPlainHttp() throws Exception {
        // An HSTS header served over http://localhost pins the whole localhost origin to https in the
        // developer's browser for max-age. TLS termination belongs to a proxy, and so does this header.
        assertTrue(get("/echo").headers().firstValue("Strict-Transport-Security").isEmpty());
    }
}
