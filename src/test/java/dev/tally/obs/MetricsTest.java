package dev.tally.obs;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Prometheus text rendering: HELP and TYPE lines, labels, cumulative buckets, seconds from nanos.
 */
class MetricsTest {
    private final Metrics metrics = new Metrics();

    private static String sample(String text, String series) {
        return Arrays.stream(text.split("\n"))
                .filter(l -> l.startsWith(series + " "))
                .map(l -> l.substring(series.length() + 1))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no sample " + series + " in\n" + text));
    }

    @Test
    void aLabelledCounterRendersHelpTypeAndItsSamples() {
        metrics.transfers.inc("applied");
        metrics.transfers.inc("applied");
        metrics.transfers.inc("rejected");
        String text = metrics.render();
        assertTrue(text.contains("# HELP tally_transfers_total "), text);
        assertTrue(text.contains("# TYPE tally_transfers_total counter\n"), text);
        assertEquals("2", sample(text, "tally_transfers_total{outcome=\"applied\"}"));
        assertEquals("1", sample(text, "tally_transfers_total{outcome=\"rejected\"}"));
        assertEquals(2, metrics.transfers.value("applied"));
    }

    @Test
    void anUnlabelledCounterRendersZeroBeforeItsFirstIncrement() {
        // A series that only appears after the first event makes a dashboard panel read "no data".
        assertEquals("0", sample(metrics.render(), "tally_rate_limited_total"));
        metrics.rateLimited.inc();
        assertEquals("1", sample(metrics.render(), "tally_rate_limited_total"));
    }

    @Test
    void histogramBucketsAreCumulativeAndTheSumIsInSeconds() {
        metrics.reconciliationDuration.observeNanos(3_000_000);
        metrics.reconciliationDuration.observeNanos(30_000_000);
        metrics.reconciliationDuration.observeNanos(2_000_000_000);
        String text = metrics.render();
        assertTrue(text.contains("# TYPE tally_reconciliation_duration_seconds histogram\n"), text);
        assertEquals("1", sample(text, "tally_reconciliation_duration_seconds_bucket{le=\"0.005\"}"));
        assertEquals("2", sample(text, "tally_reconciliation_duration_seconds_bucket{le=\"0.05\"}"));
        assertEquals("3", sample(text, "tally_reconciliation_duration_seconds_bucket{le=\"+Inf\"}"));
        assertEquals("3", sample(text, "tally_reconciliation_duration_seconds_count"));
        assertEquals("2.033", sample(text, "tally_reconciliation_duration_seconds_sum"));
    }

    @Test
    void labelledHistogramPutsLeAfterTheOtherLabels() {
        metrics.httpDuration.observeNanos(1_000, "GET", "/health");
        String text = metrics.render();
        assertEquals("1", sample(text,
                "tally_http_request_duration_seconds_bucket{method=\"GET\",route=\"/health\",le=\"0.001\"}"));
        assertEquals("1", sample(text, "tally_http_request_duration_seconds_count{method=\"GET\",route=\"/health\"}"));
    }

    @Test
    void secondsAreWrittenFromNanosWithoutFloatingPoint() {
        assertEquals("0", Metrics.seconds(0));
        assertEquals("0.000000001", Metrics.seconds(1));
        assertEquals("0.005", Metrics.seconds(5_000_000));
        assertEquals("1.5", Metrics.seconds(1_500_000_000));
        assertEquals("2", Metrics.seconds(2_000_000_000));
    }

    @Test
    void labelValuesAreEscaped() {
        metrics.transfers.inc("a\"b\\c\nd");
        String text = metrics.render();
        assertTrue(text.contains("tally_transfers_total{outcome=\"a\\\"b\\\\c\\nd\"} 1"), text);
    }

    @Test
    void theWrongNumberOfLabelValuesIsAProgrammerError() {
        assertThrows(IllegalArgumentException.class, () -> metrics.transfers.inc());
        assertThrows(IllegalArgumentException.class, () -> metrics.httpDuration.observeNanos(1, "GET"));
    }

    @Test
    void aGaugeReadsItsSupplierAtRenderTime() {
        long[] depth = {3};
        metrics.gauge("tally_test_depth", "A test gauge.", () -> depth[0]);
        assertEquals("3", sample(metrics.render(), "tally_test_depth"));
        depth[0] = 7;
        String text = metrics.render();
        assertTrue(text.contains("# TYPE tally_test_depth gauge\n"), text);
        assertEquals("7", sample(text, "tally_test_depth"));
    }
}
