package dev.tally.fraud;

import dev.tally.core.AccountId;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * An account's recent scored postings, read back from the score store just before a new one is scored.
 *
 * The scorer holds no state between events, so a restart or a second replica sees the same window.
 */
public final class Window {
    private final List<Score> outgoing;   // newest first, all earlier than the posting being scored
    private final List<Score> incoming;

    public Window(List<Score> outgoing, List<Score> incoming) {
        this.outgoing = List.copyOf(outgoing);
        this.incoming = List.copyOf(incoming);
    }

    public int size() {
        return outgoing.size();
    }

    public int outgoingBetween(Instant fromInclusive, Instant toInclusive) {
        int n = 0;
        for (Score s : outgoing) {
            if (!s.eventAt().isBefore(fromInclusive) && !s.eventAt().isAfter(toInclusive)) {
                n++;
            }
        }
        return n;
    }

    // The lower middle value, not the mean of the two middles, so the answer stays an amount the account
    // actually sent and no division is needed. Zero for an empty window.
    public long medianAmount() {
        if (outgoing.isEmpty()) {
            return 0;
        }
        long[] amounts = outgoing.stream().mapToLong(Score::amountMinor).sorted().toArray();
        return amounts[(amounts.length - 1) / 2];
    }

    public int distinctPayeesBetween(Instant fromInclusive, Instant toInclusive) {
        Set<AccountId> payees = new HashSet<>();
        for (Score s : outgoing) {
            if (!s.eventAt().isBefore(fromInclusive) && !s.eventAt().isAfter(toInclusive)) {
                payees.add(s.counterparty());
            }
        }
        return payees.size();
    }

    public boolean hasPaid(AccountId counterparty) {
        for (Score s : outgoing) {
            if (s.counterparty().equals(counterparty)) {
                return true;
            }
        }
        return false;
    }

    // Hours are UTC and compared around the clock, so 23:00 and 00:00 are one hour apart, not 23.
    public int outgoingNearHour(int hourOfDay, int radiusHours) {
        int n = 0;
        for (Score s : outgoing) {
            int hour = s.eventAt().atOffset(ZoneOffset.UTC).getHour();
            int distance = Math.abs(hour - hourOfDay);
            if (Math.min(distance, 24 - distance) <= radiusHours) {
                n++;
            }
        }
        return n;
    }

    public long incomingSince(Instant since) {
        long total = 0;
        for (Score s : incoming) {
            if (!s.eventAt().isBefore(since)) {
                total = Math.addExact(total, s.amountMinor());
            }
        }
        return total;
    }
}
