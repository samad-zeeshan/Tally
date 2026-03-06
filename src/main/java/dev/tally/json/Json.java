package dev.tally.json;

/**
 * The JSON facade: parse a string to a JsonValue, write a JsonValue to a compact string.
 */
public final class Json {
    private Json() {}

    public static JsonValue parse(String text) {
        return new JsonParser(text).parse();
    }

    public static String write(JsonValue value) {
        return JsonWriter.write(value);
    }
}
