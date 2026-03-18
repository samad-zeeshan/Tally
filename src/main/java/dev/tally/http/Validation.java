package dev.tally.http;

import dev.tally.core.AccountId;
import dev.tally.json.JsonValue;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict shape checks at the HTTP edge, run after JSON parse and before any store call. It throws on
 * the first failing rule, each carrying its own code and the offending field. The store keeps checking
 * state (account exists, funds, key used); this checks only shape, so the two are defence in depth.
 *
 * The 10^12 cap covers both amountMinor and openingBalanceMinor. That keeps every stored balance,
 * including world's negative sum, far under 2^53, so the browser client's Number.isSafeInteger guard
 * never fires on a legitimately created account; capping only the transfer amount would leave an
 * opening free to mint a single balance past that edge.
 */
public final class Validation {
    public static final int MAX_BODY_BYTES = HttpKernel.MAX_BODY_BYTES;
    public static final long MAX_AMOUNT_MINOR = 1_000_000_000_000L;   // 10^12
    private static final int MAX_NAME_LENGTH = 80;

    // Unknown fields are rejected, not ignored: there is one first-party client, so Postel tolerance
    // buys nothing, and a typo like amountMinr is caught here instead of as a confusing AMOUNT_REQUIRED.
    private static final Set<String> ACCOUNT_FIELDS = Set.of("name", "openingBalanceMinor");
    private static final Set<String> TRANSFER_FIELDS = Set.of("fromAccountId", "toAccountId", "amountMinor");

    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_-]{8,64}");
    // \p{L}\p{N} admits non-ASCII, so "Zoe" with a diaeresis is a valid name; hyphen sits last, literal.
    private static final Pattern NAME_CHARSET = Pattern.compile("[\\p{L}\\p{N} .,'&-]+");

    private Validation() {}

    public record AccountFields(String name, long openingBalanceMinor) {}

    public record TransferFields(AccountId from, AccountId to, long amountMinor) {}

    public static AccountFields account(JsonValue body) {
        JsonValue.JsonObject obj = object(body);
        rejectUnknownFields(obj, ACCOUNT_FIELDS);
        // Trim before the length and charset checks; the trimmed value is what gets stored.
        String name = requiredString(obj, "name", ErrorCode.NAME_REQUIRED, "name is required and must be a string").strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            throw new ApiException(ErrorCode.NAME_LENGTH, "name", "name must be 1 to 80 characters");
        }
        if (!NAME_CHARSET.matcher(name).matches()) {
            throw new ApiException(ErrorCode.NAME_CHARSET, "name", "name may use letters, digits, spaces and . , ' & -");
        }
        long opening = optionalLong(obj, "openingBalanceMinor", 0, ErrorCode.OPENING_BALANCE_NOT_INTEGER,
                "openingBalanceMinor must be a whole number of minor units");
        if (opening < 0) {
            throw new ApiException(ErrorCode.OPENING_BALANCE_NEGATIVE, "openingBalanceMinor",
                    "openingBalanceMinor must not be negative");
        }
        if (opening > MAX_AMOUNT_MINOR) {
            throw new ApiException(ErrorCode.OPENING_BALANCE_TOO_LARGE, "openingBalanceMinor",
                    "openingBalanceMinor must be at most " + MAX_AMOUNT_MINOR + " minor units");
        }
        return new AccountFields(name, opening);
    }

    public static TransferFields transfer(JsonValue body) {
        JsonValue.JsonObject obj = object(body);
        rejectUnknownFields(obj, TRANSFER_FIELDS);
        AccountId from = requiredId(obj, "fromAccountId", ErrorCode.FROM_ACCOUNT_ID_REQUIRED, ErrorCode.FROM_ACCOUNT_ID_INVALID);
        AccountId to = requiredId(obj, "toAccountId", ErrorCode.TO_ACCOUNT_ID_REQUIRED, ErrorCode.TO_ACCOUNT_ID_INVALID);
        if (from.equals(to)) {
            throw new ApiException(ErrorCode.SAME_ACCOUNT, "toAccountId",
                    "fromAccountId and toAccountId must be different accounts");
        }
        long amount = requiredLong(obj, "amountMinor", ErrorCode.AMOUNT_REQUIRED, ErrorCode.AMOUNT_NOT_INTEGER);
        if (amount < 1) {
            throw new ApiException(ErrorCode.AMOUNT_NOT_POSITIVE, "amountMinor", "amountMinor must be greater than zero");
        }
        if (amount > MAX_AMOUNT_MINOR) {
            throw new ApiException(ErrorCode.AMOUNT_TOO_LARGE, "amountMinor",
                    "amountMinor must be at most " + MAX_AMOUNT_MINOR + " minor units");
        }
        return new TransferFields(from, to, amount);
    }

    // Header, not body: it is checked before the body is read so a bad key never triggers a body parse.
    public static String idempotencyKey(String header) {
        if (header == null) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_MISSING, "the Idempotency-Key header is required");
        }
        if (!KEY_FORMAT.matcher(header).matches()) {
            throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_INVALID,
                    "Idempotency-Key must be 8 to 64 characters of letters, digits, _ or -");
        }
        return header;
    }

    private static JsonValue.JsonObject object(JsonValue body) {
        if (body instanceof JsonValue.JsonObject obj) {
            return obj;
        }
        throw new ApiException(ErrorCode.BODY_NOT_OBJECT, "request body must be a JSON object");
    }

    // Present and a string. Blank is allowed here; the name's length rule owns emptiness.
    private static String requiredString(JsonValue.JsonObject obj, String name, ErrorCode missing, String message) {
        if (obj.members().get(name) instanceof JsonValue.JsonString s) {
            return s.value();
        }
        throw new ApiException(missing, name, message);
    }

    private static AccountId requiredId(JsonValue.JsonObject obj, String name, ErrorCode missing, ErrorCode invalid) {
        String raw = requiredString(obj, name, missing, name + " is required and must be a string");
        try {
            return new AccountId(UUID.fromString(raw));
        } catch (IllegalArgumentException notAUuid) {
            throw new ApiException(invalid, name, name + " must be an account id");
        }
    }

    private static long requiredLong(JsonValue.JsonObject obj, String name, ErrorCode missing, ErrorCode notInteger) {
        JsonValue value = obj.members().get(name);
        if (value == null) {
            throw new ApiException(missing, name, name + " is required");
        }
        if (value instanceof JsonValue.JsonNumber num) {
            return num.value();
        }
        throw new ApiException(notInteger, name, name + " must be a whole number of minor units");
    }

    private static long optionalLong(JsonValue.JsonObject obj, String name, long fallback, ErrorCode notInteger, String message) {
        JsonValue value = obj.members().get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof JsonValue.JsonNumber num) {
            return num.value();
        }
        throw new ApiException(notInteger, name, message);
    }

    private static void rejectUnknownFields(JsonValue.JsonObject obj, Set<String> known) {
        for (String key : obj.members().keySet()) {
            if (!known.contains(key)) {
                throw new ApiException(ErrorCode.UNKNOWN_FIELD, key, "unknown field: " + key);
            }
        }
    }
}
