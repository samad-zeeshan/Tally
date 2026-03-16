package dev.tally.core;

import java.util.List;

/**
 * The result of a reconciliation: the whole-book sum, the accounts checked, and any drift found.
 */
public record ReconciliationReport(long globalSumMinor, int accountsChecked, List<Drift> drifts) {
    public ReconciliationReport {
        drifts = List.copyOf(drifts);
    }

    public boolean consistent() {
        return globalSumMinor == 0 && drifts.isEmpty();
    }
}
