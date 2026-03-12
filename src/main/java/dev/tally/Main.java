package dev.tally;

import dev.tally.db.DbConfig;
import dev.tally.db.MigrationRunner;
import dev.tally.db.Pool;
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
        int port = Integer.parseInt(System.getenv().getOrDefault("TALLY_PORT", "8080"));
        ApiServer server = new ApiServer(port, openStore());
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start();
        System.out.println("Tally listening on port " + server.port());
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
