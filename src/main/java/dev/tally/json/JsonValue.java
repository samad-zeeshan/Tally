package dev.tally.json;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A parsed JSON value, a sealed hierarchy of records. Numbers are long only.
 */
public sealed interface JsonValue
        permits JsonValue.JsonObject, JsonValue.JsonArray, JsonValue.JsonString,
                JsonValue.JsonNumber, JsonValue.JsonBool, JsonValue.JsonNull {

    record JsonObject(Map<String, JsonValue> members) implements JsonValue {
        public JsonObject {
            // LinkedHashMap, not Map.copyOf: the writer's output and the tests depend on
            // insertion order, and Map.copyOf leaves iteration order unspecified.
            members = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(members)));
        }
    }

    record JsonArray(List<JsonValue> items) implements JsonValue {
        public JsonArray {
            items = List.copyOf(items);
        }
    }

    record JsonString(String value) implements JsonValue {
        public JsonString {
            Objects.requireNonNull(value);
        }
    }

    record JsonNumber(long value) implements JsonValue {}

    record JsonBool(boolean value) implements JsonValue {}

    record JsonNull() implements JsonValue {}
}
