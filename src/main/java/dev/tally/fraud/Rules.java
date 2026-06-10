package dev.tally.fraud;

import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The five baseline rules. Each is a record so its thresholds are visible in one line and a test can
 * build a variant. The numbers are starting points, and the offline evaluation is how they get judged.
 */
public final class Rules {
    private Rules() {}

    public static final List<Rule> DEFAULT = List.of(
            new Velocity(), new AmountDeviation(), new NewCounterparty(), new RoundAmount(), new TimeOfDay());

    // The v1 rules plus the graph rules. The service runs this set; DEFAULT stays so v1 can be re-measured.
    public static final List<Rule> V2 = concat(DEFAULT, GraphRules.ALL);

    public static List<Rule> named(String ruleset) {
        return switch (ruleset == null || ruleset.isBlank() ? "v2" : ruleset) {
            case "v1" -> DEFAULT;
            case "v2" -> V2;
            default -> throw new IllegalArgumentException("TALLY_FRAUD_RULESET must be v1 or v2: " + ruleset);
        };
    }

    private static List<Rule> concat(List<Rule> a, List<Rule> b) {
        List<Rule> all = new ArrayList<>(a);
        all.addAll(b);
        return List.copyOf(all);
    }

    /** Many payments out in a short span: the shape of a drained account or a scripted burst. */
    public record Velocity(Duration span, int minEarlier) implements Rule {
        public Velocity() {
            this(Duration.ofMinutes(10), 3);
        }

        @Override
        public String name() {
            return "velocity";
        }

        @Override
        public List<Long> evidence(PostingEvent event, Window window) {
            return window.outgoingIdsBetween(event.at().minus(span), event.at());
        }

        @Override
        public int points(PostingEvent event, Window window) {
            int earlier = window.outgoingBetween(event.at().minus(span), event.at());
            if (earlier < minEarlier) {
                return 0;
            }
            // Graded rather than flat so a ten-payment burst outranks a four-payment one in the ranking
            // that AUROC measures, while the cap keeps velocity alone below the flag line.
            return Math.min(35, 15 + 5 * (earlier - minEarlier));
        }
    }

    /** A payment far above what this account usually sends, judged against its own history only. */
    public record AmountDeviation(int minHistory, long multiple) implements Rule {
        public AmountDeviation() {
            this(5, 5);
        }

        @Override
        public String name() {
            return "amount_deviation";
        }

        @Override
        public int points(PostingEvent event, Window window) {
            if (window.size() < minHistory) {
                return 0;
            }
            long median = Math.max(1, window.medianAmount());
            if (event.amountMinor() < Math.multiplyExact(multiple, median)) {
                return 0;
            }
            long ratio = event.amountMinor() / median;
            return ratio >= 20 ? 35 : ratio >= 10 ? 30 : 25;
        }
    }

    /** A payee the account has not paid in its window. Silent until there is a window to compare with. */
    public record NewCounterparty(int minHistory) implements Rule {
        public NewCounterparty() {
            this(3);
        }

        @Override
        public String name() {
            return "new_counterparty";
        }

        @Override
        public int points(PostingEvent event, Window window) {
            return window.size() >= minHistory && !window.hasPaid(event.to()) ? 20 : 0;
        }
    }

    /** Whole hundreds from 500.00 up. People paying bills rarely land on them; people moving cash often do. */
    public record RoundAmount(long unitMinor, long floorMinor) implements Rule {
        public RoundAmount() {
            this(10_000, 50_000);
        }

        @Override
        public String name() {
            return "round_amount";
        }

        @Override
        public int points(PostingEvent event, Window window) {
            long amount = event.amountMinor();
            return amount >= floorMinor && amount % unitMinor == 0 ? 15 : 0;
        }
    }

    /** An hour of day the account has never paid near, once it has enough history to have habits. */
    public record TimeOfDay(int minHistory, int radiusHours) implements Rule {
        public TimeOfDay() {
            this(10, 1);
        }

        @Override
        public String name() {
            return "time_of_day";
        }

        @Override
        public int points(PostingEvent event, Window window) {
            if (window.size() < minHistory) {
                return 0;
            }
            int hour = event.at().atOffset(ZoneOffset.UTC).getHour();
            return window.outgoingNearHour(hour, radiusHours) == 0 ? 20 : 0;
        }
    }
}
