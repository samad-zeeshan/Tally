package dev.tally.api;

import dev.tally.http.ApiException;
import dev.tally.http.ErrorCode;
import dev.tally.json.JsonValue;

import java.util.Set;

/**
 * Typed field readers over the JsonValue hierarchy, each carrying its own code and field name.
 */
final class Fields {
    private Fields() {}

    static JsonValue.JsonObject object(JsonValue body) {
        if (body instanceof JsonValue.JsonObject obj) {
            return obj;
        }
        throw new ApiException(ErrorCode.BODY_NOT_OBJECT, "request body must be a JSON object");
    }

    static String requiredString(JsonValue.JsonObject obj, String name, ErrorCode missing) {
        if (obj.members().get(name) instanceof JsonValue.JsonString s && !s.value().isBlank()) {
            return s.value();
        }
        throw new ApiException(missing, name, name + " is required and must be a non-blank string");
    }

    static long requiredLong(JsonValue.JsonObject obj, String name, ErrorCode missing, ErrorCode notInteger) {
        JsonValue value = obj.members().get(name);
        if (value == null) {
            throw new ApiException(missing, name, name + " is required");
        }
        if (value instanceof JsonValue.JsonNumber num) {
            return num.value();
        }
        throw new ApiException(notInteger, name, name + " must be a whole number of minor units");
    }

    static long optionalLong(JsonValue.JsonObject obj, String name, long fallback, ErrorCode notInteger) {
        JsonValue value = obj.members().get(name);
        if (value == null) {
            return fallback;
        }
        if (value instanceof JsonValue.JsonNumber num) {
            return num.value();
        }
        throw new ApiException(notInteger, name, name + " must be a whole number of minor units");
    }

    static void rejectUnknownFields(JsonValue.JsonObject obj, Set<String> known) {
        for (String key : obj.members().keySet()) {
            if (!known.contains(key)) {
                throw new ApiException(ErrorCode.UNKNOWN_FIELD, key, "unknown field: " + key);
            }
        }
    }

    static JsonValue str(String value) {
        return new JsonValue.JsonString(value);
    }

    static JsonValue num(long value) {
        return new JsonValue.JsonNumber(value);
    }

    static JsonValue bool(boolean value) {
        return new JsonValue.JsonBool(value);
    }
}
