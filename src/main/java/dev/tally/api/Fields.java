package dev.tally.api;

import dev.tally.json.JsonValue;

/**
 * Small builders for the JsonValue response bodies. Input validation lives at the edge in
 * dev.tally.http.Validation; these are only for rendering.
 */
final class Fields {
    private Fields() {}

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
