package dev.tally.fraud;

import dev.tally.core.AccountId;
import dev.tally.core.TransferId;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Each rule on both sides of its line: the history it needs, the threshold it fires at, and the points
 * it adds. A rule that returns 0 has not fired.
 */
class RulesTest {
    private static final AccountId ME = AccountId.newId();
    private static final AccountId USUAL = AccountId.newId();
    private static final AccountId STRANGER = AccountId.newId();
    private static final Instant NOON = Instant.parse("2026-03-10T12:00:00Z");

    private long nextPosting = 1;

    // Builds an account's earlier outgoing postings, newest first, the order the store hands them over.
    private final class History {
        private final List<Score> scores = new ArrayList<>();

        History pay(AccountId to, long amount, Instant at) {
            scores.addFirst(new Score(nextPosting++, TransferId.newId(), ME, to, amount, 0, List.of(), at, at));
            return this;
        }

        History payMany(int n, long amount, Instant start, Duration step) {
            for (int i = 0; i < n; i++) {
                pay(USUAL, amount, start.plus(step.multipliedBy(i)));
            }
            return this;
        }

        Window window() {
            return new Window(scores, List.of());
        }
    }

    private PostingEvent event(AccountId to, long amount, Instant at) {
        return new PostingEvent(nextPosting++, nextPosting++, TransferId.newId(), ME, to, amount, at);
    }

    @Test
    void velocityNeedsThreeEarlierPaymentsInTenMinutes() {
        Rule velocity = new Rules.Velocity();
        Window two = new History().payMany(2, 1_000, NOON.minusSeconds(120), Duration.ofSeconds(30)).window();
        Window three = new History().payMany(3, 1_000, NOON.minusSeconds(120), Duration.ofSeconds(30)).window();
        assertEquals(0, velocity.points(event(USUAL, 1_000, NOON), two));
        assertEquals(15, velocity.points(event(USUAL, 1_000, NOON), three));
    }

    @Test
    void velocityGrowsWithTheBurstAndStopsAtThirtyFive() {
        Rule velocity = new Rules.Velocity();
        Window five = new History().payMany(5, 1_000, NOON.minusSeconds(300), Duration.ofSeconds(30)).window();
        Window twelve = new History().payMany(12, 1_000, NOON.minusSeconds(360), Duration.ofSeconds(30)).window();
        assertEquals(25, velocity.points(event(USUAL, 1_000, NOON), five));
        assertEquals(35, velocity.points(event(USUAL, 1_000, NOON), twelve));
    }

    @Test
    void velocityIgnoresPaymentsOlderThanTheSpan() {
        Window old = new History().payMany(6, 1_000, NOON.minus(Duration.ofMinutes(30)), Duration.ofMinutes(3)).window();
        // The six payments sit at 30, 27, ... 15 minutes before noon, all outside ten minutes.
        assertEquals(0, new Rules.Velocity().points(event(USUAL, 1_000, NOON), old));
    }

    @Test
    void amountDeviationWaitsForFivePaymentsOfHistory() {
        Window four = new History().payMany(4, 1_000, NOON.minus(Duration.ofDays(4)), Duration.ofDays(1)).window();
        assertEquals(0, new Rules.AmountDeviation().points(event(USUAL, 1_000_000, NOON), four));
    }

    @Test
    void amountDeviationFiresAtFiveTimesTheMedianAndScalesWithTheRatio() {
        Rule deviation = new Rules.AmountDeviation();
        Window window = new History().payMany(5, 1_000, NOON.minus(Duration.ofDays(5)), Duration.ofDays(1)).window();
        assertEquals(0, deviation.points(event(USUAL, 4_999, NOON), window));
        assertEquals(25, deviation.points(event(USUAL, 5_000, NOON), window));
        assertEquals(30, deviation.points(event(USUAL, 10_000, NOON), window));
        assertEquals(35, deviation.points(event(USUAL, 20_000, NOON), window));
    }

    @Test
    void theMedianIsTheLowerMiddleSoOneHugePaymentCannotDragItUp() {
        History h = new History();
        for (long amount : new long[] {100, 200, 300, 400, 500, 1_000_000}) {
            h.pay(USUAL, amount, NOON.minus(Duration.ofDays(1)));
        }
        assertEquals(300, h.window().medianAmount());
    }

    @Test
    void newCounterpartyNeedsThreePaymentsOfHistory() {
        Rule rule = new Rules.NewCounterparty();
        Window two = new History().payMany(2, 1_000, NOON.minus(Duration.ofDays(2)), Duration.ofDays(1)).window();
        Window three = new History().payMany(3, 1_000, NOON.minus(Duration.ofDays(3)), Duration.ofDays(1)).window();
        assertEquals(0, rule.points(event(STRANGER, 1_000, NOON), two));
        assertEquals(20, rule.points(event(STRANGER, 1_000, NOON), three));
        assertEquals(0, rule.points(event(USUAL, 1_000, NOON), three), "a payee already in the window is not new");
    }

    @Test
    void roundAmountFiresOnWholeHundredsFromFiveHundredUp() {
        Rule rule = new Rules.RoundAmount();
        Window empty = new History().window();
        assertEquals(15, rule.points(event(USUAL, 50_000, NOON), empty));
        assertEquals(15, rule.points(event(USUAL, 950_000, NOON), empty));
        assertEquals(0, rule.points(event(USUAL, 40_000, NOON), empty), "below 500.00");
        assertEquals(0, rule.points(event(USUAL, 50_001, NOON), empty), "not a whole hundred");
        assertEquals(0, rule.points(event(USUAL, 95_050, NOON), empty));
    }

    @Test
    void timeOfDayFiresOnAnHourTheAccountNeverUses() {
        Rule rule = new Rules.TimeOfDay();
        Window afternoons = new History().payMany(10, 1_000, Instant.parse("2026-03-01T14:10:00Z"), Duration.ofDays(1)).window();
        assertEquals(20, rule.points(event(USUAL, 1_000, Instant.parse("2026-03-12T03:00:00Z")), afternoons));
        assertEquals(0, rule.points(event(USUAL, 1_000, Instant.parse("2026-03-12T15:40:00Z")), afternoons),
                "an hour either side of a known hour is normal");
    }

    @Test
    void timeOfDayNeedsTenPaymentsOfHistory() {
        Window nine = new History().payMany(9, 1_000, Instant.parse("2026-03-01T14:10:00Z"), Duration.ofDays(1)).window();
        assertEquals(0, new Rules.TimeOfDay().points(event(USUAL, 1_000, Instant.parse("2026-03-12T03:00:00Z")), nine));
    }

    @Test
    void timeOfDayWrapsAroundMidnight() {
        Window lateNights = new History().payMany(10, 1_000, Instant.parse("2026-03-01T23:20:00Z"), Duration.ofDays(1)).window();
        assertEquals(0, new Rules.TimeOfDay().points(event(USUAL, 1_000, Instant.parse("2026-03-12T00:30:00Z")), lateNights));
    }

    @Test
    void theDefaultRulesHaveDistinctNames() {
        List<String> names = Rules.DEFAULT.stream().map(Rule::name).toList();
        assertEquals(List.of("velocity", "amount_deviation", "new_counterparty", "round_amount", "time_of_day"), names);
        assertEquals(names.size(), new HashSet<>(names).size());
    }
}
