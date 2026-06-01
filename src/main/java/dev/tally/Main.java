package dev.tally;

import dev.tally.db.DbConfig;
import dev.tally.db.MigrationRunner;
import dev.tally.db.Pool;
import dev.tally.fraud.FeatureRule;
import dev.tally.fraud.FraudScoring;
import dev.tally.fraud.InMemoryScoreStore;
import dev.tally.fraud.JdbcScoreStore;
import dev.tally.fraud.Rule;
import dev.tally.fraud.Rules;
import dev.tally.fraud.ScoreStore;
import dev.tally.http.Auth;
import dev.tally.http.RateLimiter;
import dev.tally.obs.Logs;
import dev.tally.obs.Metrics;
import dev.tally.store.InMemoryStore;
import dev.tally.store.JdbcStore;
import dev.tally.store.Store;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

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
        Metrics metrics = new Metrics();
        Storage storage = openStorage(metrics);
        boolean replayClock = "true".equalsIgnoreCase(System.getenv("TALLY_FRAUD_REPLAY_CLOCK"));
        if (replayClock) {
            System.out.println("TALLY_FRAUD_REPLAY_CLOCK is on: the scorer trusts X-Tally-Event-Time. Evaluation only.");
        }
        FraudScoring fraud = new FraudScoring(storage.scores(), fraudRules(), FraudScoring.DEFAULT_CAPACITY,
                replayClock, metrics);
        ApiServer server = new ApiServer(port, storage.ledger(), token, staticPath, metrics, fraud,
                RateLimiter.fromEnv(System::getenv));
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

    // Under Kubernetes a Job owns migrations and the pods only wait for them (ADR-0022), because two
    // replicas running the lock-free runner at once would race on CREATE TABLE.
    static boolean migrateOnStart(UnaryOperator<String> getenv) {
        return !"false".equalsIgnoreCase(getenv.apply("TALLY_MIGRATE_ON_START"));
    }

    // Rules accepted by the offline reflection gate are data, loaded only when an operator names the
    // file (ADR-0025). A malformed file stops startup rather than scoring with half the rules.
    private static List<Rule> fraudRules() {
        List<Rule> rules = new ArrayList<>(Rules.DEFAULT);
        String extra = System.getenv("TALLY_FRAUD_EXTRA_RULES");
        if (extra != null && !extra.isBlank()) {
            List<Rule> loaded = FeatureRule.load(Path.of(extra));
            rules.addAll(loaded);
            System.out.println("fraud rules added from " + extra + ": " + loaded.stream().map(Rule::name).toList());
        }
        return rules;
    }

    private record Storage(Store ledger, ScoreStore scores) {}

    // With TALLY_DB_URL set, migrate and serve from Postgres; otherwise fall back to the in-memory store
    // so the demo runs with no database. The fraud scores live wherever the ledger does.
    private static Storage openStorage(Metrics metrics) throws Exception {
        Optional<DbConfig> config = DbConfig.fromEnv();
        if (config.isEmpty()) {
            System.out.println("TALLY_DB_URL is not set, using the in-memory store");
            return new Storage(new InMemoryStore(), new InMemoryScoreStore());
        }
        Pool pool = Pool.open(config.get(), metrics.poolWait::observeNanos);
        Path migrations = Path.of(System.getenv().getOrDefault("TALLY_MIGRATIONS_DIR", "db/migrations"));
        if (migrateOnStart(System::getenv)) {
            Connection conn = pool.borrow();
            try {
                new MigrationRunner(conn, migrations).run();
            } finally {
                pool.giveBack(conn);
            }
        }
        System.out.println("using the Postgres store at " + config.get().url());
        return new Storage(new JdbcStore(pool), new JdbcScoreStore(pool));
    }
}
