package dev.tally;

import dev.tally.db.DbConfig;
import dev.tally.db.MigrationRunner;
import dev.tally.db.Pool;
import dev.tally.http.Auth;
import dev.tally.obs.Logs;
import dev.tally.store.InMemoryStore;
import dev.tally.store.JdbcStore;
import dev.tally.store.Store;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Optional;

/**
 * The composition root: pick the store from the environment, wire it behind the server, and start.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Logs.init();
        applyServerTuning();
        String token = readTokenOrExit();
        int port = Integer.parseInt(System.getenv().getOrDefault("TALLY_PORT", "8080"));
        // TALLY_STATIC_DIR set (the container) serves the built client at the same origin; unset locally.
        String staticDir = System.getenv("TALLY_STATIC_DIR");
        Path staticPath = staticDir == null ? null : Path.of(staticDir);
        ApiServer server = new ApiServer(port, openStore(), token, staticPath);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("Tally listening on port " + server.port());
    }

    // Fail closed on a bad token with a one-line message and a distinct exit code, not a stack trace:
    // shipping an open write API by accident is the failure this guards against.
    private static String readTokenOrExit() {
        try {
            return Auth.requireToken(System::getenv);
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            System.exit(2);
            throw e;   // unreachable; System.exit does not return
        }
    }

    // com.sun.net.httpserver has no per-exchange deadline, only these process-wide millisecond limits,
    // and they must be set before HttpServer.create first loads the server's config class. Honest floor,
    // not real policy: a reverse proxy owns timeouts in production. See ADR-0015.
    public static void applyServerTuning() {
        System.setProperty("sun.net.httpserver.maxReqTime", "10000");    // receive the request within 10s
        System.setProperty("sun.net.httpserver.maxRspTime", "30000");    // deliver the response within 30s
        System.setProperty("sun.net.httpserver.maxReqHeaderSize", "16384");   // default 384 KiB is absurd here
    }

    // With TALLY_DB_URL set, migrate and serve from Postgres; otherwise fall back to the in-memory store
    // so the demo runs with no database.
    private static Store openStore() throws Exception {
        Optional<DbConfig> config = DbConfig.fromEnv();
        if (config.isEmpty()) {
            System.out.println("TALLY_DB_URL is not set, using the in-memory store");
            return new InMemoryStore();
        }
        Pool pool = Pool.open(config.get());
        Path migrations = Path.of(System.getenv().getOrDefault("TALLY_MIGRATIONS_DIR", "db/migrations"));
        Connection conn = pool.borrow();
        try {
            new MigrationRunner(conn, migrations).run();
        } finally {
            pool.giveBack(conn);
        }
        System.out.println("using the Postgres store at " + config.get().url());
        return new JdbcStore(pool);
    }
}
