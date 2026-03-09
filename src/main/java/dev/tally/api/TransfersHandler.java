package dev.tally.api;

import dev.tally.core.AccountId;
import dev.tally.core.TransferOutcome;
import dev.tally.core.TransferRequest;
import dev.tally.http.ApiException;
import dev.tally.http.ErrorCode;
import dev.tally.http.Request;
import dev.tally.http.Response;
import dev.tally.json.Json;
import dev.tally.json.JsonValue;
import dev.tally.store.Store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static dev.tally.api.Fields.num;
import static dev.tally.api.Fields.str;

/**
 * The transfer endpoint. The key is validated here; the store owns the actual dedupe.
 */
public final class TransfersHandler {
    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final Store store;

    public TransfersHandler(Store store) {
        this.store = store;
    }

    public Response create(Request request) {
        String key = request.headers().getFirst("Idempotency-Key");
        if (key == null) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_MISSING, "the Idempotency-Key header is required");
        }
        if (!KEY_FORMAT.matcher(key).matches()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_INVALID,
                    "Idempotency-Key must be 8 to 64 characters of letters, digits, _ or -");
        }
        JsonValue.JsonObject obj = Fields.object(Json.parse(request.body()));
        Fields.rejectUnknownFields(obj, Set.of("fromAccountId", "toAccountId", "amountMinor"));
        String fromRaw = Fields.requiredString(obj, "fromAccountId", ErrorCode.FROM_ACCOUNT_ID_REQUIRED);
        String toRaw = Fields.requiredString(obj, "toAccountId", ErrorCode.TO_ACCOUNT_ID_REQUIRED);
        long amount = Fields.requiredLong(obj, "amountMinor", ErrorCode.AMOUNT_REQUIRED, ErrorCode.AMOUNT_NOT_INTEGER);
        if (amount <= 0) {
            throw new ApiException(ErrorCode.AMOUNT_NOT_POSITIVE, "amountMinor", "amountMinor must be greater than zero");
        }
        if (fromRaw.equals(toRaw)) {
            throw new ApiException(ErrorCode.SAME_ACCOUNT, "toAccountId", "fromAccountId and toAccountId must be different");
        }
        AccountId from = parseId(fromRaw, ErrorCode.FROM_ACCOUNT_ID_INVALID, "fromAccountId");
        AccountId to = parseId(toRaw, ErrorCode.TO_ACCOUNT_ID_INVALID, "toAccountId");

        TransferRequest transferRequest = new TransferRequest(key, from, to, amount);
        return render(store.apply(transferRequest), transferRequest);
    }

    // A non-UUID id is a 400 here, decided before the store: a string that is not a UUID could never
    // name an account in any ledger state. A well-formed UUID with no account is the store's 422.
    private static AccountId parseId(String raw, ErrorCode invalid, String field) {
        try {
            return new AccountId(UUID.fromString(raw));
        } catch (IllegalArgumentException notAUuid) {
            throw new ApiException(invalid, field, field + " must be an account id");
        }
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
