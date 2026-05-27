package dev.tally.fraud;

import dev.tally.db.DbConfig;
import dev.tally.db.Pool;
import dev.tally.db.PostgresTestSupport;
import dev.tally.store.JdbcStore;
import dev.tally.store.Store;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;

import java.sql.SQLException;

/**
 * The same contract on posting_scores, where the primary key and ON CONFLICT make the insert idempotent.
 */
@Tag("integration")
class JdbcScoreStoreTest extends ScoreStoreContractTest {
    private static Pool pool;

    @BeforeAll
    static void open() throws SQLException {
        PostgresTestSupport.migrate();
        pool = Pool.open(new DbConfig(PostgresTestSupport.url(), PostgresTestSupport.user(),
                PostgresTestSupport.password(), 4));
    }

    @AfterAll
    static void close() {
        if (pool != null) {
            pool.close();
        }
    }

    // The contract calls ledger() from its own @BeforeEach, so resetting here gives each test an empty book.
    @Override
    protected Store ledger() {
        PostgresTestSupport.resetSchema();
        return new JdbcStore(pool);
    }

    @Override
    protected ScoreStore scores() {
        return new JdbcScoreStore(pool);
    }
}
