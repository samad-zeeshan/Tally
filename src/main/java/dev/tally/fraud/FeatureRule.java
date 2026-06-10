package dev.tally.fraud;

import dev.tally.json.Json;
import dev.tally.json.JsonValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A rule written as data: one feature, one comparison, one threshold, some points. This is the whole
 * language a reflection step may propose in (ADR-0025), so a proposal can be checked, never executed.
 */
public record FeatureRule(String name, Feature feature, String op, long threshold, int points) implements Rule {
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]{2,39}");
    private static final Set<String> FIELDS = Set.of("name", "feature", "op", "threshold", "points");
    private static final Set<String> OPS = Set.of(">=", "<=", "==");
    private static final int MAX_POINTS = 40;

    /** Integer features over the event and the account's window. Names are the wire spelling. */
    public enum Feature {
        OUTGOING_10M("outgoing_10m"),
        OUTGOING_60M("outgoing_60m"),
        AMOUNT_MINOR("amount_minor"),
        AMOUNT_TO_MEDIAN_X100("amount_to_median_x100"),
        HISTORY_SIZE("history_size"),
        COUNTERPARTY_KNOWN("counterparty_known"),
        DISTINCT_PAYEES_60M("distinct_payees_60m"),
        HOUR_SEEN_COUNT("hour_seen_count"),
        INCOMING_60M_MINOR("incoming_60m_minor"),
        PASSTHROUGH_PCT("passthrough_pct"),
        FAN_IN_60M("fan_in_60m"),
        PAYEE_PAYERS("payee_payers"),
        PAYEE_OUTGOING("payee_outgoing"),
        FLAGGED_IN_60M_MINOR("flagged_in_60m_minor"),
        SEED_HOPS("seed_hops"),
        CYCLE_24H("cycle_24h");

        public final String wireName;

        Feature(String wireName) {
            this.wireName = wireName;
        }

        public static Feature named(String name) {
            for (Feature f : values()) {
                if (f.wireName.equals(name)) {
                    return f;
                }
            }
            throw new IllegalArgumentException("unknown feature: " + name);
        }

        public long value(PostingEvent e, Window w) {
            Duration tenMinutes = Duration.ofMinutes(10);
            Duration hour = Duration.ofHours(1);
            return switch (this) {
                case OUTGOING_10M -> w.outgoingBetween(e.at().minus(tenMinutes), e.at());
                case OUTGOING_60M -> w.outgoingBetween(e.at().minus(hour), e.at());
                case AMOUNT_MINOR -> e.amountMinor();
                // The same five-payment floor the amount_deviation rule uses, so a thin account reads 0.
                case AMOUNT_TO_MEDIAN_X100 -> w.size() < 5 ? 0 : Math.multiplyExact(e.amountMinor(), 100) / Math.max(1, w.medianAmount());
                case HISTORY_SIZE -> w.size();
                case COUNTERPARTY_KNOWN -> w.hasPaid(e.to()) ? 1 : 0;
                case DISTINCT_PAYEES_60M -> w.distinctPayeesBetween(e.at().minus(hour), e.at());
                case HOUR_SEEN_COUNT -> w.outgoingNearHour(e.at().atOffset(ZoneOffset.UTC).getHour(), 1);
                case INCOMING_60M_MINOR -> w.incomingSince(e.at().minus(hour));
                // How much of the last hour's inflow this payment sends on. A mule forwards most of it.
                case PASSTHROUGH_PCT -> {
                    long inflow = w.incomingSince(e.at().minus(hour));
                    yield inflow == 0 ? 0 : Math.multiplyExact(e.amountMinor(), 100) / inflow;
                }
                case FAN_IN_60M -> w.fanInSince(e.at().minus(hour));
                case PAYEE_PAYERS -> w.graph().payeePayers();
                case PAYEE_OUTGOING -> w.graph().payeeOutgoing();
                case FLAGGED_IN_60M_MINOR -> w.flaggedIncomingSince(e.at().minus(hour)).stream().mapToLong(Score::amountMinor).sum();
                // Hops from flagged money to this payer: 1 if it came in directly, 2 through one account.
                case SEED_HOPS -> !w.flaggedIncomingSince(e.at().minus(hour)).isEmpty() ? 1
                        : w.graph().secondHopSeeds().isEmpty() ? 0 : 2;
                case CYCLE_24H -> w.graph().cyclePath().isEmpty() ? 0 : 1;
            };
        }
    }

    @Override
    public int points(PostingEvent event, Window window) {
        long v = feature.value(event, window);
        boolean fires = switch (op) {
            case ">=" -> v >= threshold;
            case "<=" -> v <= threshold;
            default -> v == threshold;
        };
        return fires ? points : 0;
    }

    // Every field is checked and anything extra is refused, the same stance Validation takes at the HTTP
    // edge: a model that adds a field is told no rather than half obeyed.
    public static FeatureRule parse(JsonValue json) {
        if (!(json instanceof JsonValue.JsonObject(Map<String, JsonValue> m))) {
            throw new IllegalArgumentException("a rule must be a JSON object");
        }
        for (String key : m.keySet()) {
            if (!FIELDS.contains(key)) {
                throw new IllegalArgumentException("unknown rule field: " + key);
            }
        }
        String name = string(m, "name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("rule name must be 3 to 40 of a-z, 0-9 and _: " + name);
        }
        for (Rule builtIn : Rules.V2) {
            if (builtIn.name().equals(name)) {
                throw new IllegalArgumentException("rule name is taken by a baseline rule: " + name);
            }
        }
        String op = string(m, "op");
        if (!OPS.contains(op)) {
            throw new IllegalArgumentException("op must be one of >=, <=, ==: " + op);
        }
        long threshold = number(m, "threshold");
        if (threshold < 0) {
            throw new IllegalArgumentException("threshold must not be negative: " + threshold);
        }
        long points = number(m, "points");
        if (points < 1 || points > MAX_POINTS) {
            throw new IllegalArgumentException("points must be 1 to " + MAX_POINTS + ": " + points);
        }
        return new FeatureRule(name, Feature.named(string(m, "feature")), op, threshold, (int) points);
    }

    public static List<Rule> load(Path file) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read fraud rules from " + file, e);
        }
        if (!(Json.parse(text) instanceof JsonValue.JsonArray(List<JsonValue> items))) {
            throw new IllegalArgumentException("a rules file must hold a JSON array");
        }
        List<Rule> rules = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (JsonValue item : items) {
            FeatureRule rule = parse(item);
            if (!names.add(rule.name())) {
                throw new IllegalArgumentException("duplicate rule name: " + rule.name());
            }
            rules.add(rule);
        }
        return rules;
    }

    private static String string(Map<String, JsonValue> m, String key) {
        if (!(m.get(key) instanceof JsonValue.JsonString(String s))) {
            throw new IllegalArgumentException(key + " is required and must be a string");
        }
        return s;
    }

    private static long number(Map<String, JsonValue> m, String key) {
        if (!(m.get(key) instanceof JsonValue.JsonNumber(long n))) {
            throw new IllegalArgumentException(key + " is required and must be a whole number");
        }
        return n;
    }
}
