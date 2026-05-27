package dev.tally.obs;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * The service's counters and histograms, rendered by hand in the Prometheus text format 0.0.4.
 *
 * The series are fixed fields, not a registry keyed by name, so a typo is a compile error. See ADR-0023.
 */
public final class Metrics {
    // Request latency on a local JVM is well under a millisecond for most routes, so the low end is dense.
    static final long[] LATENCY_BUCKETS =
            micros(1_000, 5_000, 10_000, 25_000, 50_000, 100_000, 250_000, 500_000, 1_000_000, 2_500_000, 5_000_000);
    // Pool.borrow gives up at five seconds, so nothing is observed above that.
    static final long[] POOL_WAIT_BUCKETS =
            micros(100, 1_000, 5_000, 10_000, 50_000, 100_000, 500_000, 1_000_000, 5_000_000);

    private final List<Family> families = new CopyOnWriteArrayList<>();

    public final Counter httpRequests = add(new Counter("tally_http_requests_total",
            "HTTP requests by method, route template and status.", "method", "route", "status"));
    public final Histogram httpDuration = add(new Histogram("tally_http_request_duration_seconds",
            "HTTP request latency by method and route template.", LATENCY_BUCKETS, "method", "route"));
    public final Counter transfers = add(new Counter("tally_transfers_total",
            "Transfers answered by the store, by outcome: applied, replayed or rejected.", "outcome"));
    public final Histogram reconciliationDuration = add(new Histogram("tally_reconciliation_duration_seconds",
            "Time to recompute every balance from its postings.", LATENCY_BUCKETS));
    public final Histogram poolWait = add(new Histogram("tally_db_pool_wait_seconds",
            "Time a request waited for a database connection.", POOL_WAIT_BUCKETS));
    public final Counter rateLimited = add(new Counter("tally_rate_limited_total",
            "Requests refused with 429 by the per-address throttle."));
    public final Counter fraudPostings = add(new Counter("tally_fraud_postings_total",
            "Applied postings seen by the fraud scorer, by result: scored, duplicate, failed or dropped.", "result"));
    public final Counter fraudRulesFired = add(new Counter("tally_fraud_rules_fired_total",
            "Fraud rules that added points to a score, by rule.", "rule"));
    public final Counter fraudFlagged = add(new Counter("tally_fraud_flagged_total",
            "Scored postings at or above the flag threshold."));

    public void gauge(String name, String help, LongSupplier value) {
        add(new Gauge(name, help, value));
    }

    public String render() {
        StringBuilder out = new StringBuilder();
        for (Family family : families) {
            out.append("# HELP ").append(family.name()).append(' ').append(family.help()).append('\n');
            out.append("# TYPE ").append(family.name()).append(' ').append(family.type()).append('\n');
            family.renderSamples(out);
        }
        return out.toString();
    }

    // Integer division and a zero-padded remainder, so a duration never passes through a double on its
    // way to the text. Trailing zeros go, which is what makes 5_000_000 read as 0.005.
    public static String seconds(long nanos) {
        long whole = nanos / 1_000_000_000L;
        long fraction = nanos % 1_000_000_000L;
        if (fraction == 0) {
            return Long.toString(whole);
        }
        String digits = String.format("%09d", fraction).replaceAll("0+$", "");
        return whole + "." + digits;
    }

    private <F extends Family> F add(F family) {
        families.add(family);
        return family;
    }

    private static long[] micros(long... micros) {
        return Arrays.stream(micros).map(us -> us * 1_000).toArray();
    }

    private sealed interface Family permits Counter, Histogram, Gauge {
        String name();

        String help();

        String type();

        void renderSamples(StringBuilder out);
    }

    public static final class Counter implements Family {
        private final String name;
        private final String help;
        private final String[] labelNames;
        private final Map<List<String>, LongAdder> series = new ConcurrentHashMap<>();

        Counter(String name, String help, String... labelNames) {
            this.name = name;
            this.help = help;
            this.labelNames = labelNames;
            if (labelNames.length == 0) {
                series.put(List.of(), new LongAdder());
            }
        }

        public void inc(String... labelValues) {
            series.computeIfAbsent(key(labelNames, labelValues), k -> new LongAdder()).increment();
        }

        public long value(String... labelValues) {
            LongAdder adder = series.get(key(labelNames, labelValues));
            return adder == null ? 0 : adder.sum();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String help() {
            return help;
        }

        @Override
        public String type() {
            return "counter";
        }

        @Override
        public void renderSamples(StringBuilder out) {
            for (List<String> labels : sorted(series.keySet())) {
                out.append(name).append(labelBlock(labelNames, labels, null)).append(' ')
                        .append(series.get(labels).sum()).append('\n');
            }
        }
    }

    public static final class Histogram implements Family {
        private final String name;
        private final String help;
        private final long[] bounds;
        private final String[] labelNames;
        private final Map<List<String>, Series> series = new ConcurrentHashMap<>();

        // Per-bucket counts are stored non-cumulative and summed at render, so an observation touches
        // one adder instead of every bucket above it.
        private record Series(LongAdder[] buckets, LongAdder count, LongAdder sumNanos) {
            Series(int size) {
                this(adders(size), new LongAdder(), new LongAdder());
            }
        }

        Histogram(String name, String help, long[] bounds, String... labelNames) {
            this.name = name;
            this.help = help;
            this.bounds = bounds;
            this.labelNames = labelNames;
            if (labelNames.length == 0) {
                series.put(List.of(), new Series(bounds.length));
            }
        }

        public void observeNanos(long nanos, String... labelValues) {
            Series s = series.computeIfAbsent(key(labelNames, labelValues), k -> new Series(bounds.length));
            int i = 0;
            while (i < bounds.length && nanos > bounds[i]) {
                i++;
            }
            if (i < bounds.length) {
                s.buckets()[i].increment();
            }
            s.count().increment();
            s.sumNanos().add(nanos);
        }

        public long count(String... labelValues) {
            Series s = series.get(key(labelNames, labelValues));
            return s == null ? 0 : s.count().sum();
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String help() {
            return help;
        }

        @Override
        public String type() {
            return "histogram";
        }

        @Override
        public void renderSamples(StringBuilder out) {
            for (List<String> labels : sorted(series.keySet())) {
                Series s = series.get(labels);
                long cumulative = 0;
                for (int i = 0; i < bounds.length; i++) {
                    cumulative += s.buckets()[i].sum();
                    out.append(name).append("_bucket").append(labelBlock(labelNames, labels, seconds(bounds[i])))
                            .append(' ').append(cumulative).append('\n');
                }
                long count = s.count().sum();
                out.append(name).append("_bucket").append(labelBlock(labelNames, labels, "+Inf"))
                        .append(' ').append(count).append('\n');
                out.append(name).append("_sum").append(labelBlock(labelNames, labels, null))
                        .append(' ').append(seconds(s.sumNanos().sum())).append('\n');
                out.append(name).append("_count").append(labelBlock(labelNames, labels, null))
                        .append(' ').append(count).append('\n');
            }
        }

        private static LongAdder[] adders(int size) {
            LongAdder[] adders = new LongAdder[size];
            for (int i = 0; i < size; i++) {
                adders[i] = new LongAdder();
            }
            return adders;
        }
    }

    private record Gauge(String name, String help, LongSupplier value) implements Family {
        @Override
        public String type() {
            return "gauge";
        }

        @Override
        public void renderSamples(StringBuilder out) {
            out.append(name).append(' ').append(value.getAsLong()).append('\n');
        }
    }

    private static List<String> key(String[] labelNames, String[] labelValues) {
        if (labelValues.length != labelNames.length) {
            throw new IllegalArgumentException("expected " + labelNames.length + " label values, got " + labelValues.length);
        }
        return List.of(labelValues);
    }

    // Sorted so two scrapes of the same state are byte-identical, which keeps diffs and tests readable.
    private static List<List<String>> sorted(Iterable<List<String>> keys) {
        List<List<String>> out = new ArrayList<>();
        keys.forEach(out::add);
        out.sort(Comparator.comparing(k -> String.join("\u0000", k)));
        return out;
    }

    private static String labelBlock(String[] names, List<String> values, String le) {
        if (names.length == 0 && le == null) {
            return "";
        }
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                b.append(',');
            }
            b.append(names[i]).append("=\"").append(escape(values.get(i))).append('"');
        }
        if (le != null) {
            if (names.length > 0) {
                b.append(',');
            }
            b.append("le=\"").append(le).append('"');
        }
        return b.append('}').toString();
    }

    // The three escapes the text format defines for a label value. Anything else passes through as is.
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
