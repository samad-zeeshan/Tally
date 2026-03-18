package dev.tally.api;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.StatementLine;
import dev.tally.core.StatementPage;
import dev.tally.http.ApiException;
import dev.tally.http.Cursor;
import dev.tally.http.ErrorCode;
import dev.tally.http.Request;
import dev.tally.http.Response;
import dev.tally.http.Validation;
import dev.tally.json.Json;
import dev.tally.json.JsonValue;
import dev.tally.store.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static dev.tally.api.Fields.num;
import static dev.tally.api.Fields.str;

/**
 * The account endpoints: create, read, and read a statement.
 */
public final class AccountsHandler {
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    private final Store store;

    public AccountsHandler(Store store) {
        this.store = store;
    }

    public Response create(Request request) {
        Validation.AccountFields fields = Validation.account(Json.parse(request.body()));
        Account account = store.createAccount(fields.name(), fields.openingBalanceMinor());
        return Response.json(201, renderAccount(account))
                .withHeader("Location", "/accounts/" + account.id().value());
    }

    public Response get(Request request) {
        return Response.json(200, renderAccount(resolve(request.pathParams().get("id"))));
    }

    // An opening shows up here as an ordinary transfer whose counterparty is world, not a special
    // entry type. The cursor is decoded to a keyset bound here; the store never sees the cursor string.
    public Response statement(Request request) {
        Account account = resolve(request.pathParams().get("id"));
        int limit = parseLimit(request.queryParams().get("limit"));
        long before = parseCursor(request.queryParams().get("cursor"));
        StatementPage page = store.statement(account.id(), before, limit);
        return Response.json(200, renderStatement(page));
    }

    private static int parseLimit(String raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        int limit;
        try {
            limit = Integer.parseInt(raw);
        } catch (NumberFormatException notAnInteger) {
            throw new ApiException(ErrorCode.INVALID_LIMIT, "limit", "limit must be an integer between 1 and " + MAX_LIMIT);
        }
        // Out of range is a 400, not a silent clamp: a clamp would hide a client bug.
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new ApiException(ErrorCode.INVALID_LIMIT, "limit", "limit must be between 1 and " + MAX_LIMIT);
        }
        return limit;
    }

    private static long parseCursor(String cursor) {
        return cursor == null ? Long.MAX_VALUE : Cursor.decode(cursor);
    }

    // A path id is opaque: a string that is not a UUID, or a UUID with no account, is simply not found.
    private Account resolve(String raw) {
        try {
            AccountId id = new AccountId(UUID.fromString(raw));
            return store.findAccount(id)
                    .orElseThrow(() -> new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "account not found: " + raw));
        } catch (IllegalArgumentException notAUuid) {
            throw new ApiException(ErrorCode.ACCOUNT_NOT_FOUND, "account not found: " + raw);
        }
    }

    private static JsonValue renderAccount(Account account) {
        Map<String, JsonValue> m = new LinkedHashMap<>();
        m.put("id", str(account.id().value().toString()));
        m.put("name", str(account.name()));
        m.put("balanceMinor", num(account.balanceMinor()));
        m.put("createdAt", str(account.createdAt().toString()));
        return new JsonValue.JsonObject(m);
    }

    private static JsonValue renderStatement(StatementPage page) {
        List<JsonValue> entries = new ArrayList<>();
        for (StatementLine line : page.entries()) {
            Map<String, JsonValue> e = new LinkedHashMap<>();
            e.put("postingId", num(line.postingId()));
            e.put("transferId", str(line.transferId().value().toString()));
            e.put("counterpartyAccountId", str(line.counterpartyAccountId().value().toString()));
            e.put("amountMinor", num(line.amountMinor()));
            e.put("balanceAfterMinor", num(line.balanceAfterMinor()));
            e.put("createdAt", str(line.createdAt().toString()));
            entries.add(new JsonValue.JsonObject(e));
        }
        Map<String, JsonValue> m = new LinkedHashMap<>();
        m.put("accountId", str(page.accountId().value().toString()));
        m.put("entries", new JsonValue.JsonArray(entries));
        // The last returned entry is the oldest on this page; the next page fetches ids below it. null
        // exactly when there are no further entries, from the store's limit+1 probe.
        if (page.hasMore() && !page.entries().isEmpty()) {
            m.put("nextCursor", str(Cursor.encode(page.entries().getLast().postingId())));
        } else {
            m.put("nextCursor", new JsonValue.JsonNull());
        }
        return new JsonValue.JsonObject(m);
    }
}
