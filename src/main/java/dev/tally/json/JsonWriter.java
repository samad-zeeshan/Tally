package dev.tally.json;

import java.util.List;
import java.util.Map;

/**
 * Compact deterministic JSON writer. Members in insertion order, no whitespace.
 */
final class JsonWriter {
    private JsonWriter() {}

    static String write(JsonValue value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    private static void writeValue(JsonValue value, StringBuilder sb) {
        switch (value) {
            case JsonValue.JsonObject(var members) -> writeObject(members, sb);
            case JsonValue.JsonArray(var items) -> writeArray(items, sb);
            case JsonValue.JsonString(var s) -> writeString(s, sb);
            case JsonValue.JsonNumber(var num) -> sb.append(num);
            case JsonValue.JsonBool(var b) -> sb.append(b);
            case JsonValue.JsonNull _ -> sb.append("null");
        }
    }

    private static void writeObject(Map<String, JsonValue> members, StringBuilder sb) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, JsonValue> e : members.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            writeString(e.getKey(), sb);
            sb.append(':');
            writeValue(e.getValue(), sb);
        }
        sb.append('}');
    }

    private static void writeArray(List<JsonValue> items, StringBuilder sb) {
        sb.append('[');
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            writeValue(items.get(i), sb);
        }
        sb.append(']');
    }

    // Escapes the two structural characters and control chars only. The solidus is left bare, and
    // non-ASCII is written raw because UTF-8 encoding is the HTTP layer's job.
    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
