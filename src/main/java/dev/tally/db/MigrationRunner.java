package dev.tally.db;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Applies .sql files from a directory, once each, in file-name order, recording them in schema_version.
 */
public final class MigrationRunner {
    private final Connection conn;
    private final Path dir;

    public MigrationRunner(Connection conn, Path dir) {
        this.conn = conn;
        this.dir = dir;
    }

    public List<String> run() throws SQLException, IOException {
        boolean priorAutoCommit = conn.getAutoCommit();
        try {
            conn.setAutoCommit(true);
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS schema_version ("
                        + "version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
            }
            List<Path> files = sqlFilesInOrder();
            List<String> applied = new ArrayList<>();
            for (Path file : files) {
                String version = file.getFileName().toString();
                if (alreadyApplied(version)) {
                    continue;
                }
                applyOne(file, version);
                applied.add(version);
            }
            return applied;
        } finally {
            conn.setAutoCommit(priorAutoCommit);
        }
    }

    // Read-only on purpose: every API pod calls this from its init container while the Job migrates, so
    // it must not create schema_version or take any lock the runner needs.
    public List<String> pending() throws SQLException, IOException {
        Set<String> applied = new HashSet<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT to_regclass('schema_version') IS NOT NULL")) {
            rs.next();
            if (rs.getBoolean(1)) {
                try (ResultSet versions = s.executeQuery("SELECT version FROM schema_version")) {
                    while (versions.next()) {
                        applied.add(versions.getString(1));
                    }
                }
            }
        }
        List<String> pending = new ArrayList<>();
        for (Path file : sqlFilesInOrder()) {
            String version = file.getFileName().toString();
            if (!applied.contains(version)) {
                pending.add(version);
            }
        }
        return pending;
    }

    private void applyOne(Path file, String version) throws SQLException, IOException {
        conn.setAutoCommit(false);
        try {
            try (Statement s = conn.createStatement()) {
                // pgJDBC runs a multi-statement string in one execute, so no fragile SQL splitter is needed.
                s.execute(Files.readString(file));
            }
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO schema_version (version) VALUES (?)")) {
                ps.setString(1, version);
                ps.executeUpdate();
            }
            // Postgres DDL is transactional, so applying the file and recording it commit atomically; a
            // failed migration leaves no trace, not a half-created schema.
            conn.commit();
        } catch (SQLException | IOException failed) {
            conn.rollback();
            throw failed;
        } finally {
            conn.setAutoCommit(true);
        }
    }

    private List<Path> sqlFilesInOrder() throws IOException {
        // No advisory lock around the run: one app process by design, and CI applies migrations once.
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> p.getFileName().toString().endsWith(".sql"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    private boolean alreadyApplied(String version) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM schema_version WHERE version = ?")) {
            ps.setString(1, version);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
