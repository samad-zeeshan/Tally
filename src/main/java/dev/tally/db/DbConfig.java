package dev.tally.db;

import java.util.Optional;

/**
 * Database connection settings, read from the environment. Empty when TALLY_DB_URL is unset.
 */
public record DbConfig(String url, String user, String password, int poolSize) {
    public static Optional<DbConfig> fromEnv() {
        String url = System.getenv("TALLY_DB_URL");
        if (url == null) {
            return Optional.empty();
        }
        String user = System.getenv().getOrDefault("TALLY_DB_USER", "tally");
        String password = System.getenv().getOrDefault("TALLY_DB_PASSWORD", "tally");
        int poolSize = Integer.parseInt(System.getenv().getOrDefault("TALLY_DB_POOL_SIZE", "10"));
        return Optional.of(new DbConfig(url, user, password, poolSize));
    }
}
