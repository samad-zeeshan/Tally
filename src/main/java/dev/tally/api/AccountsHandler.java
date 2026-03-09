package dev.tally.api;

import dev.tally.core.Account;
import dev.tally.core.AccountId;
import dev.tally.core.StatementLine;
import dev.tally.core.StatementPage;
import dev.tally.http.ApiException;
import dev.tally.http.ErrorCode;
import dev.tally.http.Request;
import dev.tally.http.Response;
import dev.tally.json.Json;
import dev.tally.json.JsonValue;
import dev.tally.store.Store;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.tally.api.Fields.num;
import static dev.tally.api.Fields.str;

/**
 * The account endpoints: create, read, and read a statement.
 */
public final class AccountsHandler {
    static final int STATEMENT_LIMIT = 100;

    private final Store store;

    public AccountsHandler(Store store) {
        this.store = store;
    }

    public Response create(Request request) {
        JsonValue.JsonObject obj = Fields.object(Json.parse(request.body()));
        Fields.rejectUnknownFields(obj, Set.of("name", "openingBalanceMinor"));
        String name = Fields.requiredString(obj, "name", ErrorCode.NAME_REQUIRED);
        long opening = Fields.optionalLong(obj, "openingBalanceMinor", 0, ErrorCode.OPENING_BALANCE_NOT_INTEGER);
        if (opening < 0) {
            throw new ApiException(ErrorCode.OPENING_BALANCE_NEGATIVE, "openingBalanceMinor",
                    "openingBalanceMinor must not be negative");
        }
        Account account = store.createAccount(name, opening);
        return Response.json(201, renderAccount(account))
                .withHeader("Location", "/accounts/" + account.id().value());
    }

    public Response get(Request request) {
        return Response.json(200, renderAccount(resolve(request.pathParams().get("id"))));
    }

    // An opening shows up here as an ordinary transfer whose counterparty is world, not a special
    // entry type. The 100 cap is pending pagination.
    public Response statement(Request request) {
        Account account = resolve(request.pathParams().get("id"));
        StatementPage page = store.statement(account.id(), Long.MAX_VALUE, STATEMENT_LIMIT);
        return Response.json(200, renderStatement(page));
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
        return new JsonValue.JsonObject(m);
    }
}
