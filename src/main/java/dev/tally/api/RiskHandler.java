package dev.tally.api;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.fraud.FraudScoring;
import dev.tally.fraud.Score;
import dev.tally.http.ApiException;
import dev.tally.http.ErrorCode;
import dev.tally.http.Request;
import dev.tally.http.Response;
import dev.tally.json.JsonValue;
import dev.tally.store.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.tally.api.Fields.bool;
import static dev.tally.api.Fields.num;
import static dev.tally.api.Fields.str;

/**
 * GET /accounts/{id}/risk: the account's latest fraud scores, newest first, with the rules that fired.
 */
public final class RiskHandler {
    static final int DEFAULT_LIMIT = 20;
    // Higher than the statement's cap because the offline evaluation reads a whole account in one call.
    static final int MAX_LIMIT = 500;

    private final Store store;
    private final FraudScoring fraud;

    public RiskHandler(Store store, FraudScoring fraud) {
        this.store = store;
        this.fraud = fraud;
    }

    public Response latest(Request request) {
        Account account = resolve(request.pathParams().get("id"));
        int limit = parseLimit(request.queryParams().get("limit"));
        List<JsonValue> scores = new ArrayList<>();
        for (Score score : fraud.recent(account.id(), limit)) {
            scores.add(render(score));
        }
        Map<String, JsonValue> body = new LinkedHashMap<>();
        body.put("accountId", str(account.id().value().toString()));
        body.put("flagThreshold", num(Score.FLAG_THRESHOLD));
        body.put("scores", new JsonValue.JsonArray(scores));
        return Response.json(200, new JsonValue.JsonObject(body));
    }

    private Account resolve(String raw) {
        try {
            return store.findAccount(new AccountId(UUID.fromString(raw)))
                    .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "account not found: " + raw));
        } catch (IllegalArgumentException notAUuid) {
            throw new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "account not found: " + raw);
        }
    }

    private static int parseLimit(String raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(raw);
            if (limit >= 1 && limit <= MAX_LIMIT) {
                return limit;
            }
        } catch (NumberFormatException notAnInteger) {
            // falls through to the same 400 as an out-of-range number
        }
        throw new ApiException(ErrorCode.INVALID_LIMIT, "limit", "limit must be an integer between 1 and " + MAX_LIMIT);
    }

    private static JsonValue render(Score score) {
        Map<String, JsonValue> m = new LinkedHashMap<>();
        m.put("postingId", num(score.postingId()));
        m.put("transferId", str(score.transferId().value().toString()));
        m.put("counterpartyAccountId", str(score.counterparty().value().toString()));
        m.put("amountMinor", num(score.amountMinor()));
        m.put("score", num(score.score()));
        m.put("flagged", bool(score.flagged()));
        List<JsonValue> rules = new ArrayList<>();
        for (String rule : score.rules()) {
            rules.add(str(rule));
        }
        m.put("rules", new JsonValue.JsonArray(rules));
        m.put("eventAt", str(score.eventAt().toString()));
        m.put("scoredAt", str(score.scoredAt().toString()));
        return new JsonValue.JsonObject(m);
    }
}
