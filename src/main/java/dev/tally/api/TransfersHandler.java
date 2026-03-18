package dev.tally.api;

import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.http.ErrorCode;
import dev.tally.http.Request;
import dev.tally.http.Response;
import dev.tally.http.Validation;
import dev.tally.json.Json;
import dev.tally.json.JsonValue;
import dev.tally.store.Store;

import java.util.LinkedHashMap;
import java.util.Map;

import static dev.tally.api.Fields.num;
import static dev.tally.api.Fields.str;

/**
 * The transfer endpoint. Validation owns the edge checks; the store owns the actual dedupe.
 */
public final class TransfersHandler {
    private final Store store;

    public TransfersHandler(Store store) {
        this.store = store;
    }

    // The key is read before the body so a missing or malformed key is caught without a parse; then
    // Validation checks the body and hands back parsed account ids.
    public Response create(Request request) {
        String key = Validation.idempotencyKey(request.headers().getFirst("Idempotency-Key"));
        Validation.TransferFields fields = Validation.transfer(Json.parse(request.body()));
        TransferRequest transferRequest = new TransferRequest(key, fields.from(), fields.to(), fields.amountMinor());
        return render(store.apply(transferRequest), transferRequest);
    }

    // Replay reproduces the original status on purpose (see ADR-0010): it recurses once into the same
    // renderer and adds the header, so a replayed 201 stays a 201 and a replayed 422 stays a 422.
    private Response render(TransferOutcome outcome, TransferRequest req) {
        return switch (outcome) {
            case TransferOutcome.Applied a -> Response.json(201, renderTransfer(a, req));
            case TransferOutcome.InsufficientFunds f -> Response.error(ErrorCode.INSUFFICIENT_FUNDS,
                    "account " + f.account().value() + " holds " + f.balanceMinor()
                            + ", the transfer needs " + f.requestedMinor());
            case TransferOutcome.UnknownAccount u -> Response.error(ErrorCode.UNKNOWN_ACCOUNT,
                    "account not found: " + u.account().value());
            case TransferOutcome.ReservedAccount _ -> Response.error(ErrorCode.RESERVED_ACCOUNT,
                    "the world account may not be named in a transfer");
            case TransferOutcome.KeyConflict _ -> Response.error(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                    "the idempotency key was reused with a different transfer");
            case TransferOutcome.Replayed(var first) -> render(first, req).withHeader("Idempotency-Replayed", "true");
        };
    }

    private static JsonValue renderTransfer(TransferOutcome.Applied applied, TransferRequest req) {
        Map<String, JsonValue> m = new LinkedHashMap<>();
        m.put("id", str(applied.id().value().toString()));
        m.put("fromAccountId", str(req.from().value().toString()));
        m.put("toAccountId", str(req.to().value().toString()));
        m.put("amountMinor", num(req.amountMinor()));
        m.put("createdAt", str(applied.at().toString()));
        return new JsonValue.JsonObject(m);
    }
}
