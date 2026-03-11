package dev.tally.db;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The schema's constraints, exercised with raw SQL: the world row, the balance CHECK, the unique key.
 */
@Tag("integration")
class SchemaTest {

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.migrate();
    }

    @BeforeEach
    void reset() {
        PostgresTestSupport.resetSchema();
    }

    @Test
    void worldRowExistsAndAllowsNegative() throws Exception {
        try (Connection c = PostgresTestSupport.connect(); Statement s = c.createStatement()) {
            try (ResultSet rs = s.executeQuery(
                    "SELECT name, allow_negative FROM accounts WHERE id = '" + PostgresTestSupport.WORLD_ID + "'")) {
                assertTrue(rs.next());
                assertEquals("world", rs.getString("name"));
                assertTrue(rs.getBoolean("allow_negative"));
            }
            // World may go below zero, so the CHECK does not fire.
            s.executeUpdate("UPDATE accounts SET balance_minor = -1 WHERE id = '" + PostgresTestSupport.WORLD_ID + "'");
        }
    }

    @Test
    void checkRejectsNegativeBalanceOnNormalAccount() throws Exception {
        String id = UUID.randomUUID().toString();
        try (Connection c = PostgresTestSupport.connect(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO accounts (id, name) VALUES ('" + id + "', 'normal')");
            SQLException e = assertThrows(SQLException.class,
                    () -> s.executeUpdate("UPDATE accounts SET balance_minor = -1 WHERE id = '" + id + "'"));
            assertEquals("23514", e.getSQLState());   // check_violation
        }
    }

    @Test
    void uniqueConstraintRejectsDuplicateKey() throws Exception {
        String id1 = UUID.randomUUID().toString();
        String id2 = UUID.randomUUID().toString();
        String world = PostgresTestSupport.WORLD_ID;
        try (Connection c = PostgresTestSupport.connect(); Statement s = c.createStatement()) {
            String insert = "INSERT INTO transfers (id, idempotency_key, request_fingerprint, "
                    + "from_account_id, to_account_id, amount_minor, status) VALUES ('%s', 'dupe-key-01', 'fp', '"
                    + world + "', '" + world + "', 10, 'applied')";
            s.executeUpdate(insert.formatted(id1));
            SQLException e = assertThrows(SQLException.class, () -> s.executeUpdate(insert.formatted(id2)));
            assertEquals("23505", e.getSQLState());   // unique_violation
        }
    }
}
