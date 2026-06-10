package dev.tally.fraud;

import dev.tally.fraud.FeatureRule.Feature;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Rules that read the transfer graph around a payment, not only the payer's own history. After RAD
 * (arXiv 2608.23468): the rules stay symbolic, and the graph supplies the context they were missing.
 */
public final class GraphRules {
    private GraphRules() {}

    public static final List<Rule> ALL = List.of(
            new FreshPayeeBurst(), new ForwardsFlagged(), new Cycle(), new EstablishedPayee());

    /** Several payments in minutes to an account that nobody else pays and that has never paid anyone. */
    public record FreshPayeeBurst(int minEarlier10m, int maxPayeePayers, int points) implements Rule {
        public FreshPayeeBurst() {
            this(2, 1, 30);
        }

        @Override
        public String name() {
            return "fresh_payee_burst";
        }

        @Override
        public int points(PostingEvent e, Window w) {
            boolean burst = Feature.OUTGOING_10M.value(e, w) >= minEarlier10m;
            boolean fresh = w.graph().payeePayers() <= maxPayeePayers && w.graph().payeeOutgoing() == 0;
            return burst && fresh ? points : 0;
        }

        @Override
        public List<Long> evidence(PostingEvent e, Window w) {
            return w.outgoingIdsBetween(e.at().minus(Duration.ofMinutes(10)), e.at());
        }
    }

    /** Sends on at least half of flagged money that arrived in the last hour. The shape of a mule hop. */
    public record ForwardsFlagged(int directPoints, int secondHopPoints, int minPct) implements Rule {
        public ForwardsFlagged() {
            this(40, 25, 50);
        }

        @Override
        public String name() {
            return "forwards_flagged";
        }

        @Override
        public int points(PostingEvent e, Window w) {
            long hops = Feature.SEED_HOPS.value(e, w);
            if (hops == 1) {
                long flagged = Feature.FLAGGED_IN_60M_MINOR.value(e, w);
                return e.amountMinor() * 100 >= minPct * flagged ? directPoints : 0;
            }
            // One hop further back the flagged amount is not this account's own inflow, so the check is on
            // how much of everything that came in goes straight out again.
            return hops == 2 && Feature.PASSTHROUGH_PCT.value(e, w) >= minPct ? secondHopPoints : 0;
        }

        @Override
        public List<Long> evidence(PostingEvent e, Window w) {
            List<Long> ids = new ArrayList<>();
            w.flaggedIncomingSince(e.at().minus(Duration.ofHours(1))).forEach(s -> ids.add(s.postingId()));
            if (ids.isEmpty()) {
                ids.addAll(w.graph().secondHopSeeds());
            }
            return ids;
        }
    }

    /** The payee has passed money back to the payer within a day, directly or through one or two others. */
    public record Cycle(int points) implements Rule {
        public Cycle() {
            this(25);
        }

        @Override
        public String name() {
            return "cycle_24h";
        }

        @Override
        public int points(PostingEvent e, Window w) {
            return w.graph().cyclePath().isEmpty() ? 0 : points;
        }

        @Override
        public List<Long> evidence(PostingEvent e, Window w) {
            return w.graph().cyclePath();
        }
    }

    // The one discount. Most false alerts in the v1 evaluation were large round payments to payees that
    // many accounts already pay, such as rent. Three payers is the smallest count that was never a fraud payee.
    public record EstablishedPayee(int minPayers, int points) implements Rule {
        public EstablishedPayee() {
            this(3, -20);
        }

        @Override
        public String name() {
            return "established_payee";
        }

        @Override
        public int points(PostingEvent e, Window w) {
            return w.graph().payeePayers() >= minPayers ? points : 0;
        }
    }
}
