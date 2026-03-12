package dev.tally.db;

import org.junit.jupiter.api.Assumptions;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Shared bootstrap for the Postgres integration tests: read the env, connect, migrate, reset.
 */
public final class PostgresTestSupport {
    private PostgresTestSupport() {}

    public static final Path MIGRATIONS = Path.of("db", "migrations");
    public static final String WORLD_ID = "00000000-0000-0000-0000-000000000000";

    // The @Tag and this check are two layers: with the profile on but no database, skip with
    // instructions rather than crash on a connection error.
    public static String url() {
        String url = System.getenv("TALLY_TEST_DB_URL");
        Assumptions.assumeTrue(url != null,
                "TALLY_TEST_DB_URL is not set. Start Postgres with `docker compose up -d db` and set "
                        + "TALLY_TEST_DB_URL=jdbc:postgresql://localhost:5432/tally_test (user and password: tally).");
        return url;
    }

    public static String user() {
        return System.getenv().getOrDefault("TALLY_TEST_DB_USER", "tally");
    }

    public static String password() {
        return System.getenv().getOrDefault("TALLY_TEST_DB_PASSWORD", "tally");
    }

    public static Connection connect() {
        try {
            return DriverManager.getConnection(url(), user(), password());
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void migrate() {
        try (Connection c = connect()) {
            new MigrationRunner(c, MIGRATIONS).run();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // TRUNCATE CASCADE removes the world row too, so re-insert it, keeping every test's book at a
    // zero sum with world present. schema_version is never truncated, so migrations stay applied.
    public static void resetSchema() {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE TABLE postings, transfers, accounts RESTART IDENTITY CASCADE");
            s.execute("INSERT INTO accounts (id, name, allow_negative, balance_minor) "
                    + "VALUES ('" + WORLD_ID + "', 'world', true, 0)");
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }
}
