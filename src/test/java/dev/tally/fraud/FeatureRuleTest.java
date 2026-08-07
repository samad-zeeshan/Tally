package dev.tally.fraud;

import dev.tally.core.AccountId;
import dev.tally.core.TransferId;
import dev.tally.json.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The constrained rule a reflection step may propose: each feature's value, the comparison,
 * and every way a proposal can be malformed.
 */
class FeatureRuleTest {
    private static final AccountId ME = AccountId.newId();
    private static final AccountId USUAL = AccountId.newId();
    private static final AccountId OTHER = AccountId.newId();
    private static final Instant NOON = Instant.parse("2026-03-10T12:00:00Z");

    @TempDir
    Path dir;

    private long nextPosting = 1;

    private Score past(AccountId from, AccountId to, long amount, Instant at) {
        return new Score(nextPosting++, TransferId.newId(), from, to, amount, 0, List.of(), at, at);
    }

    private PostingEvent event(AccountId to, long amount) {
        long debit = nextPosting++;
        return new PostingEvent(debit, nextPosting++, TransferId.newId(), ME, to, amount, NOON);
    }

    // Six earlier payments: four in the last ten minutes to two payees, two a day ago. And 10,000.00
    // paid in to this account 30 minutes ago.
    private Window window() {
        List<Score> out = new ArrayList<>();
        out.add(past(ME, USUAL, 1_000, NOON.minus(Duration.ofDays(1)).minusSeconds(60)));
        out.add(past(ME, USUAL, 3_000, NOON.minus(Duration.ofDays(1))));
        out.add(past(ME, USUAL, 2_000, NOON.minusSeconds(500)));
        out.add(past(ME, OTHER, 2_000, NOON.minusSeconds(400)));
        out.add(past(ME, OTHER, 2_000, NOON.minusSeconds(300)));
        out.add(past(ME, USUAL, 2_000, NOON.minusSeconds(200)));
        List<Score> newestFirst = new ArrayList<>(out.reversed());
        List<Score> in = List.of(past(OTHER, ME, 1_000_000, NOON.minus(Duration.ofMinutes(30))));
        return new Window(newestFirst, in);
    }

    private long value(String feature, PostingEvent event) {
        return FeatureRule.Feature.named(feature).value(event, window());
    }

    @Test
    void everyFeatureReadsTheWindowAsDocumented() {
        PostingEvent toStranger = event(AccountId.newId(), 950_000);
        assertEquals(4, value("outgoing_10m", toStranger));
        assertEquals(4, value("outgoing_60m", toStranger));
        assertEquals(950_000, value("amount_minor", toStranger));
        assertEquals(950_000 * 100 / 2_000, value("amount_to_median_x100", toStranger));
        assertEquals(6, value("history_size", toStranger));
        assertEquals(0, value("counterparty_known", toStranger));
        assertEquals(1, value("counterparty_known", event(USUAL, 100)));
        assertEquals(2, value("distinct_payees_60m", toStranger));
        assertEquals(6, value("hour_seen_count", toStranger));
        assertEquals(1_000_000, value("incoming_60m_minor", toStranger));
        assertEquals(95, value("passthrough_pct", toStranger));
    }

    @Test
    void theRatioAndPassThroughAreZeroWithoutABasis() {
        PostingEvent e = event(USUAL, 5_000);
        Window thin = new Window(List.of(past(ME, USUAL, 1_000, NOON.minusSeconds(60))), List.of());
        assertEquals(0, FeatureRule.Feature.named("amount_to_median_x100").value(e, thin), "fewer than five payments");
        assertEquals(0, FeatureRule.Feature.named("passthrough_pct").value(e, thin), "nothing came in");
    }

    @Test
    void aParsedRuleFiresOnItsComparison() {
        Rule rule = FeatureRule.parse(Json.parse(
                "{\"name\":\"many_payees\",\"feature\":\"distinct_payees_60m\",\"op\":\">=\",\"threshold\":2,\"points\":30}"));
        assertEquals("many_payees", rule.name());
        assertEquals(30, rule.points(event(USUAL, 100), window()));
        Rule strict = FeatureRule.parse(Json.parse(
                "{\"name\":\"many_payees\",\"feature\":\"distinct_payees_60m\",\"op\":\">=\",\"threshold\":3,\"points\":30}"));
        assertEquals(0, strict.points(event(USUAL, 100), window()));
        Rule exact = FeatureRule.parse(Json.parse(
                "{\"name\":\"stranger\",\"feature\":\"counterparty_known\",\"op\":\"==\",\"threshold\":0,\"points\":5}"));
        assertEquals(5, exact.points(event(AccountId.newId(), 100), window()));
        Rule atMost = FeatureRule.parse(Json.parse(
                "{\"name\":\"thin_history\",\"feature\":\"history_size\",\"op\":\"<=\",\"threshold\":6,\"points\":5}"));
        assertEquals(5, atMost.points(event(USUAL, 100), window()));
    }

    @Test
    void malformedProposalsAreRefused() {
        String ok = "\"name\":\"r_one\",\"feature\":\"outgoing_10m\",\"op\":\">=\",\"threshold\":5,\"points\":20";
        FeatureRule.parse(Json.parse("{" + ok + "}"));
        for (String bad : List.of(
                "{\"name\":\"r_one\",\"feature\":\"shoe_size\",\"op\":\">=\",\"threshold\":5,\"points\":20}",
                "{\"name\":\"r_one\",\"feature\":\"outgoing_10m\",\"op\":\">\",\"threshold\":5,\"points\":20}",
                "{\"name\":\"r_one\",\"feature\":\"outgoing_10m\",\"op\":\">=\",\"threshold\":5,\"points\":0}",
                "{\"name\":\"r_one\",\"feature\":\"outgoing_10m\",\"op\":\">=\",\"threshold\":5,\"points\":41}",
                "{\"name\":\"r_one\",\"feature\":\"outgoing_10m\",\"op\":\">=\",\"threshold\":-1,\"points\":20}",
                "{\"name\":\"R One\",\"feature\":\"outgoing_10m\",\"op\":\">=\",\"threshold\":5,\"points\":20}",
                "{\"name\":\"velocity\",\"feature\":\"outgoing_10m\",\"op\":\">=\",\"threshold\":5,\"points\":20}",
                "{" + ok + ",\"code\":\"rm -rf /\"}",
                "{\"name\":\"r_one\",\"feature\":\"outgoing_10m\",\"op\":\">=\",\"points\":20}",
                "[]")) {
            assertThrows(IllegalArgumentException.class, () -> FeatureRule.parse(Json.parse(bad)), bad);
        }
    }

    @Test
    void aRulesFileIsAnArrayWithUniqueNames() throws IOException {
        Path file = dir.resolve("rules.json");
        Files.writeString(file, "[{\"name\":\"burst_six\",\"feature\":\"outgoing_10m\",\"op\":\">=\",\"threshold\":6,\"points\":25},"
                + "{\"name\":\"pass_through\",\"feature\":\"passthrough_pct\",\"op\":\">=\",\"threshold\":80,\"points\":30}]");
        assertEquals(List.of("burst_six", "pass_through"), FeatureRule.load(file).stream().map(Rule::name).toList());

        Files.writeString(file, "[{\"name\":\"twice\",\"feature\":\"outgoing_10m\",\"op\":\">=\",\"threshold\":6,\"points\":25},"
                + "{\"name\":\"twice\",\"feature\":\"outgoing_60m\",\"op\":\">=\",\"threshold\":9,\"points\":25}]");
        assertThrows(IllegalArgumentException.class, () -> FeatureRule.load(file));
    }
}
