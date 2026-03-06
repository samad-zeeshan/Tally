package dev.tally.json;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JsonWriteTest {

    @Test
    void writesScalars() {
        assertEquals("\"hi\"", Json.write(new JsonValue.JsonString("hi")));
        assertEquals("42", Json.write(new JsonValue.JsonNumber(42)));
        assertEquals("true", Json.write(new JsonValue.JsonBool(true)));
        assertEquals("false", Json.write(new JsonValue.JsonBool(false)));
        assertEquals("null", Json.write(new JsonValue.JsonNull()));
    }

    @Test
    void writesEmptyContainers() {
        assertEquals("{}", Json.write(new JsonValue.JsonObject(Map.of())));
        assertEquals("[]", Json.write(new JsonValue.JsonArray(List.of())));
    }

    @Test
    void writesNestedCompact() {
        JsonValue doc = Json.parse("{\"a\":[1,{\"b\":2}],\"c\":true}");
        String out = Json.write(doc);
        assertEquals("{\"a\":[1,{\"b\":2}],\"c\":true}", out);
        assertFalse(out.contains(" "), "compact output has no whitespace");
    }

    @Test
    void escapesQuoteBackslashAndControls() {
        String value = "\"\\\n\t" + (char) 1;   // a quote, a backslash, newline, tab, and a 0x01
        assertEquals("\"\\\"\\\\\\n\\t\\u0001\"", Json.write(new JsonValue.JsonString(value)));
    }

    @Test
    void doesNotEscapeSolidus() {
        assertEquals("\"a/b\"", Json.write(new JsonValue.JsonString("a/b")));
    }

    @Test
    void writesNonAsciiRaw() {
        String out = Json.write(new JsonValue.JsonString("é😀"));
        assertEquals("\"é😀\"", out);
    }

    @Test
    void preservesInsertionOrder() {
        var members = new java.util.LinkedHashMap<String, JsonValue>();
        members.put("b", new JsonValue.JsonNumber(1));
        members.put("a", new JsonValue.JsonNumber(2));
        members.put("c", new JsonValue.JsonNumber(3));
        assertEquals("{\"b\":1,\"a\":2,\"c\":3}", Json.write(new JsonValue.JsonObject(members)));
    }

    @Test
    void roundTrips() {
        String doc = "{\"s\":\"x\",\"n\":-5,\"t\":true,\"f\":false,\"z\":null,\"a\":[1,2,{\"k\":3}]}";
        assertEquals(Json.parse(doc), Json.parse(Json.write(Json.parse(doc))));
    }
}
