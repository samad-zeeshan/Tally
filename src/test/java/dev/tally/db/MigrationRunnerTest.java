package dev.tally.db;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs each test on its own throwaway schema, so the runner is exercised on virgin ground.
 */
@Tag("integration")
class MigrationRunnerTest {

    @TempDir
    Path dir;
    private Connection conn;

    @BeforeEach
    void setup() throws SQLException {
        conn = PostgresTestSupport.connect();
        exec("CREATE SCHEMA mig_test");
        exec("SET search_path TO mig_test");
    }

    @AfterEach
    void teardown() throws SQLException {
        exec("DROP SCHEMA IF EXISTS mig_test CASCADE");
        conn.close();
    }

    private void exec(String sql) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        }
    }

    private void write(String name, String sql) throws IOException {
        Files.writeString(dir.resolve(name), sql);
    }

    private long queryLong(String sql) throws SQLException {
        try (Statement s = conn.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void createsSchemaVersionTableOnFirstRun() throws Exception {
        List<String> applied = new MigrationRunner(conn, dir).run();
        assertTrue(applied.isEmpty());
        assertEquals(0, queryLong("SELECT count(*) FROM schema_version"));
    }

    @Test
    void appliesFilesInLexicographicOrder() throws Exception {
        write("001_a.sql", "CREATE TABLE a (x int);");
        write("002_b.sql", "INSERT INTO a (x) VALUES (1);");
        List<String> applied = new MigrationRunner(conn, dir).run();
        assertEquals(List.of("001_a.sql", "002_b.sql"), applied);
        assertEquals(1, queryLong("SELECT x FROM a"));   // the insert ran, so the order held
    }

    @Test
    void secondRunAppliesNothing() throws Exception {
        write("001_a.sql", "CREATE TABLE a (x int);");
        write("002_b.sql", "INSERT INTO a (x) VALUES (1);");
        new MigrationRunner(conn, dir).run();
        assertTrue(new MigrationRunner(conn, dir).run().isEmpty());
        assertEquals(2, queryLong("SELECT count(*) FROM schema_version"));
    }

    @Test
    void failedMigrationLeavesNoTrace() throws Exception {
        write("001_bad.sql", "CREATE TABLE ok (x int); NOT VALID SQL;");
        assertThrows(SQLException.class, () -> new MigrationRunner(conn, dir).run());
        // Transactional DDL: the CREATE rolled back with the failure, and nothing was recorded.
        assertFalse(regclassExists("mig_test.ok"));
        assertEquals(0, queryLong("SELECT count(*) FROM schema_version"));
    }

    @Test
    void pendingListsEveryFileBeforeAnyRunAndCreatesNothing() throws Exception {
        write("001_a.sql", "CREATE TABLE a (x int);");
        write("002_b.sql", "INSERT INTO a (x) VALUES (1);");
        assertEquals(List.of("001_a.sql", "002_b.sql"), new MigrationRunner(conn, dir).pending());
        // The await mode runs this in every API pod, so it must stay read-only.
        assertFalse(regclassExists("mig_test.schema_version"));
    }

    @Test
    void pendingNamesOnlyTheFilesNotYetRecorded() throws Exception {
        write("001_a.sql", "CREATE TABLE a (x int);");
        new MigrationRunner(conn, dir).run();
        assertTrue(new MigrationRunner(conn, dir).pending().isEmpty());
        write("002_b.sql", "INSERT INTO a (x) VALUES (1);");
        assertEquals(List.of("002_b.sql"), new MigrationRunner(conn, dir).pending());
    }

    private boolean regclassExists(String qualifiedName) throws SQLException {
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT to_regclass('" + qualifiedName + "') IS NOT NULL")) {
            rs.next();
            return rs.getBoolean(1);
        }
    }
}
