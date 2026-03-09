package dev.tally;

import dev.tally.store.InMemoryStore;

/**
 * The composition root: read the port, wire an in-memory store behind the server, and start.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("TALLY_PORT", "8080"));
        ApiServer server = new ApiServer(port, new InMemoryStore());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("Tally listening on port " + server.port());
    }
}
