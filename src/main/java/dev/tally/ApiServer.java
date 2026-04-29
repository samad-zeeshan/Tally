package dev.tally;

import com.sun.net.httpserver.HttpServer;
import dev.tally.api.AccountsHandler;
import dev.tally.api.ReconciliationHandler;
import dev.tally.api.TransfersHandler;
import dev.tally.http.Auth;
import dev.tally.http.Cursor;
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
 *
 * A non-null staticDir serves the built client from that directory as well, so the whole app lives at
 * one origin. It is unset in local dev, where the Vite proxy fronts the client.
 */
public final class ApiServer {
    private final HttpServer server;
    private final ExecutorService executor;

    public ApiServer(int port, Store store, String apiToken) {
        this(port, store, apiToken, null);
    }

    public ApiServer(int port, Store store, String apiToken, Path staticDir) {
        AccountsHandler accounts = new AccountsHandler(store, new Cursor(apiToken));
        TransfersHandler transfers = new TransfersHandler(store);
        ReconciliationHandler reconciliation = new ReconciliationHandler(store);
        Auth auth = new Auth(apiToken);
        Router router = new Router();
        // /health is the one open route: a liveness probe cannot carry a credential, and it answers a
        // fixed string that names no account. Everything else is wrapped, reads included, because a
        // balance and a statement are account data. The whole boundary is meant to fit on one screen.
        router.add("GET", "/health", new HealthHandler());
        router.add("POST", "/accounts", auth.protect(accounts::create));
        router.add("GET", "/accounts", auth.protect(accounts::list));
        router.add("GET", "/accounts/{id}", auth.protect(accounts::get));
        router.add("GET", "/accounts/{id}/statement", auth.protect(accounts::statement));
        router.add("POST", "/transfers", auth.protect(transfers::create));
        router.add("GET", "/reconciliation", auth.protect(reconciliation::report));
        try {
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

    // Server first, then the executor, whose close waits for the in-flight exchanges to finish.
    public void stop() {
        server.stop(0);
        executor.close();
    }
}
