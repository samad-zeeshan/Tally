package dev.tally.store;

import dev.tally.db.DbConfig;
import dev.tally.db.Pool;
import dev.tally.db.PostgresTestSupport;
import dev.tally.testsupport.StressHarness;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.SQLException;
import java.time.Duration;

/**
 * The Stage 2 stress harness against the Postgres store: the same instrument, now over real transactions.
 */
@Tag("integration")
class JdbcStressTest {
    private static Pool pool;

    @BeforeAll
    static void open() throws SQLException {
        PostgresTestSupport.migrate();
        pool = Pool.open(new DbConfig(PostgresTestSupport.url(), PostgresTestSupport.user(),
                PostgresTestSupport.password(), 16));
    }

    @AfterAll
    static void close() {
        if (pool != null) {
            pool.close();
        }
    }

    @BeforeEach
    void reset() {
        PostgresTestSupport.resetSchema();
    }

    @Test
    @Timeout(120)
    void concurrentTransfersConserveMoney() throws InterruptedException {
        // Fewer operations than the in-memory run because each is a full Postgres transaction; 1000 opening
        // balances so some transfers are rejected for funds and the no-negative rule takes real fire.
        StressHarness.Config config = new StressHarness.Config(8, 1_000, 8, 2_000, 42, Duration.ofSeconds(90));
        StressHarness.assertClean(StressHarness.run(new JdbcStore(pool), config));
    }
}
