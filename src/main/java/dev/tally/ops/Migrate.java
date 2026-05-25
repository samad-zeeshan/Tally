package dev.tally.ops;

import dev.tally.db.DbConfig;
import dev.tally.db.MigrationRunner;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;

/**
 * Runs the migrations and exits, or with --await waits until someone else has. The Kubernetes Job runs
 * the first, and each API pod's init container runs the second, see ADR-0022.
 */
public final class Migrate {
    private Migrate() {}

    public static void main(String[] args) throws InterruptedException {
        boolean await = args.length > 0 && args[0].equals("--await");
        Optional<DbConfig> config = DbConfig.fromEnv();
        if (config.isEmpty()) {
            System.err.println("TALLY_DB_URL is not set, there is nothing to migrate");
            System.exit(2);
        }
        Path dir = Path.of(System.getenv().getOrDefault("TALLY_MIGRATIONS_DIR", "db/migrations"));
        long timeoutSeconds = Long.parseLong(System.getenv().getOrDefault("TALLY_MIGRATE_TIMEOUT_SECONDS", "300"));
        long deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L;

        // Postgres and this Job start together, so the first few connection attempts are expected to fail.
        // Retrying here keeps the Job from burning its backoffLimit on a database that is still booting.
        while (System.nanoTime() < deadline) {
            try (Connection conn = DriverManager.getConnection(
                    config.get().url(), config.get().user(), config.get().password())) {
                MigrationRunner runner = new MigrationRunner(conn, dir);
                if (!await) {
                    List<String> applied = runner.run();
                    System.out.println("migrations applied: " + (applied.isEmpty() ? "none, schema is current" : applied));
                    System.exit(0);
                }
                List<String> pending = runner.pending();
                if (pending.isEmpty()) {
                    System.out.println("schema is current");
                    System.exit(0);
                }
                System.out.println("waiting for migrations: " + pending);
            } catch (Exception notYet) {
                System.out.println("database not ready: " + notYet.getMessage());
            }
            Thread.sleep(2_000);
        }
        System.err.println("gave up after " + timeoutSeconds + " seconds");
        System.exit(1);
    }
}
