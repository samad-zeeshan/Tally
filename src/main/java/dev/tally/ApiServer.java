package dev.tally;

import com.sun.net.httpserver.HttpServer;
import dev.tally.api.AccountsHandler;
import dev.tally.api.ReconciliationHandler;
import dev.tally.api.TransfersHandler;
import dev.tally.http.Auth;
import dev.tally.http.HealthHandler;
import dev.tally.http.HttpKernel;
import dev.tally.http.Router;
import dev.tally.http.StaticFileHandler;
import dev.tally.store.Store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires the router and handlers over a Store onto the JDK HTTP server. Port 0 binds an ephemeral port.
 */
public final class ApiServer {
    private final HttpServer server;
    private final ExecutorService executor;

    public ApiServer(int port, Store store, String apiToken) {
        this(port, store, apiToken, null);
    }

    // staticDir non-null serves the built client from that directory as the kernel's fallback, so the
    // whole app lives at one origin. Unset in local dev, where the Vite proxy fronts the client.
    public ApiServer(int port, Store store, String apiToken, Path staticDir) {
        AccountsHandler accounts = new AccountsHandler(store);
        TransfersHandler transfers = new TransfersHandler(store);
        ReconciliationHandler reconciliation = new ReconciliationHandler(store);
        Auth auth = new Auth(apiToken);
        Router router = new Router();
        // Open liveness route, so the container healthcheck needs no token.
        router.add("GET", "/health", new HealthHandler());
        // Writes and reconciliation are wrapped; reads are registered bare, so the protection boundary
        // is visible in one screenful. auth.protect checks the token before the body is ever read.
        router.add("POST", "/accounts", auth.protect(accounts::create));
        router.add("GET", "/accounts", accounts::list);
        router.add("GET", "/accounts/{id}", accounts::get);
        router.add("GET", "/accounts/{id}/statement", accounts::statement);
        router.add("POST", "/transfers", auth.protect(transfers::create));
        router.add("GET", "/reconciliation", auth.protect(reconciliation::report));
        try {
            // Wildcard bind: the container needs to reach it from another host.
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // One virtual thread per exchange, so handlers stay plain blocking code.
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        StaticFileHandler staticFiles = staticDir == null ? null : new StaticFileHandler(staticDir);
        server.createContext("/", new HttpKernel(router, staticFiles));
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    // Stop the server first, then close the executor, which waits for in-flight virtual threads.
    public void stop() {
        server.stop(0);
        executor.close();
    }
}
