package dev.tally;

import com.sun.net.httpserver.HttpServer;
import dev.tally.api.AccountsHandler;
import dev.tally.api.TransfersHandler;
import dev.tally.http.HttpKernel;
import dev.tally.http.Router;
import dev.tally.store.Store;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wires the router and handlers over a Store onto the JDK HTTP server. Port 0 binds an ephemeral port.
 */
public final class ApiServer {
    private final HttpServer server;
    private final ExecutorService executor;

    public ApiServer(int port, Store store) {
        AccountsHandler accounts = new AccountsHandler(store);
        TransfersHandler transfers = new TransfersHandler(store);
        Router router = new Router();
        router.add("POST", "/accounts", accounts::create);
        router.add("GET", "/accounts/{id}", accounts::get);
        router.add("GET", "/accounts/{id}/statement", accounts::statement);
        router.add("POST", "/transfers", transfers::create);
        try {
            // Wildcard bind: the container needs to reach it from another host in Stage 8.
            server = HttpServer.create(new InetSocketAddress(port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        // One virtual thread per exchange, so handlers stay plain blocking code.
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", new HttpKernel(router));
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
