package dev.tally.api;

import dev.tally.core.Drift;
import dev.tally.core.ReconciliationReport;
import dev.tally.http.Request;
import dev.tally.http.Response;
import dev.tally.json.JsonValue;
import dev.tally.store.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.tally.api.Fields.bool;
import static dev.tally.api.Fields.num;
import static dev.tally.api.Fields.str;

/**
 * The reconciliation endpoint: recompute every balance from its postings and report any drift.
 */
public final class ReconciliationHandler {
    private final Store store;

    public ReconciliationHandler(Store store) {
        this.store = store;
    }

    // Always 200: an inconsistent book is a real answer the caller must see, reported in the body as
    // consistent:false with the offending accounts, not signalled by an error status.
    public Response report(Request _request) {
        return Response.json(200, render(store.reconcile()));
    }

    private static JsonValue render(ReconciliationReport report) {
        List<JsonValue> drifts = new ArrayList<>();
        for (Drift drift : report.drifts()) {
            Map<String, JsonValue> d = new LinkedHashMap<>();
            d.put("accountId", str(drift.accountId().value().toString()));
            d.put("storedBalanceMinor", num(drift.storedBalanceMinor()));
            d.put("derivedBalanceMinor", num(drift.derivedBalanceMinor()));
            d.put("driftMinor", num(drift.driftMinor()));
            drifts.add(new JsonValue.JsonObject(d));
        }
        Map<String, JsonValue> m = new LinkedHashMap<>();
        m.put("consistent", bool(report.consistent()));
        m.put("globalSumMinor", num(report.globalSumMinor()));
        m.put("accountsChecked", num(report.accountsChecked()));
        m.put("drifts", new JsonValue.JsonArray(drifts));
        return new JsonValue.JsonObject(m);
    }
}
