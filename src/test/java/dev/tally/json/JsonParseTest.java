package dev.tally.json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonParseTest {

    private static void rejects(String text) {
        assertThrows(JsonParseException.class, () -> Json.parse(text));
    }

    @Test
    void parsesEmptyObject() {
        JsonValue.JsonObject o = assertInstanceOf(JsonValue.JsonObject.class, Json.parse("{}"));
        assertEquals(0, o.members().size());
    }

    @Test
    void parsesEmptyArray() {
        JsonValue.JsonArray a = assertInstanceOf(JsonValue.JsonArray.class, Json.parse("[]"));
        assertEquals(0, a.items().size());
    }

    @Test
    void parsesScalarsAtTopLevel() {
        assertEquals(new JsonValue.JsonString("hi"), Json.parse("\"hi\""));
        assertEquals(new JsonValue.JsonNumber(42), Json.parse("42"));
        assertEquals(new JsonValue.JsonBool(true), Json.parse("true"));
        assertEquals(new JsonValue.JsonBool(false), Json.parse("false"));
        assertEquals(new JsonValue.JsonNull(), Json.parse("null"));
    }

    @Test
    void parsesNegativeAndBoundaryLongs() {
        assertEquals(new JsonValue.JsonNumber(-7), Json.parse("-7"));
        assertEquals(new JsonValue.JsonNumber(0), Json.parse("0"));
        assertEquals(new JsonValue.JsonNumber(0), Json.parse("-0"));
        assertEquals(new JsonValue.JsonNumber(Long.MAX_VALUE), Json.parse("9223372036854775807"));
        assertEquals(new JsonValue.JsonNumber(Long.MIN_VALUE), Json.parse("-9223372036854775808"));
    }

    @Test
    void parsesNestedDocument() {
        JsonValue v = Json.parse("{\"a\":[1,{\"b\":2}]}");
        JsonValue.JsonObject outer = assertInstanceOf(JsonValue.JsonObject.class, v);
        JsonValue.JsonArray arr = assertInstanceOf(JsonValue.JsonArray.class, outer.members().get("a"));
        assertEquals(new JsonValue.JsonNumber(1), arr.items().get(0));
        JsonValue.JsonObject inner = assertInstanceOf(JsonValue.JsonObject.class, arr.items().get(1));
        assertEquals(new JsonValue.JsonNumber(2), inner.members().get("b"));
    }

    @Test
    void preservesMemberOrder() {
        JsonValue.JsonObject o = assertInstanceOf(JsonValue.JsonObject.class, Json.parse("{\"b\":1,\"a\":2,\"c\":3}"));
        assertEquals(java.util.List.of("b", "a", "c"), java.util.List.copyOf(o.members().keySet()));
    }

    @Test
    void acceptsWhitespaceAroundTokens() {
        JsonValue.JsonObject o = assertInstanceOf(JsonValue.JsonObject.class, Json.parse("  {\n\t\"a\" : 1 ,\r\n \"b\" : 2 }  "));
        assertEquals(new JsonValue.JsonNumber(1), o.members().get("a"));
        assertEquals(new JsonValue.JsonNumber(2), o.members().get("b"));
    }

    @Test
    void parsesAllShortEscapes() {
        // JSON text: "\" \\ \/ \b \f \n \r \t"
        JsonValue.JsonString s = assertInstanceOf(JsonValue.JsonString.class,
                Json.parse("\"\\\" \\\\ \\/ \\b \\f \\n \\r \\t\""));
        assertEquals("\" \\ / \b \f \n \r \t", s.value());
    }

    @Test
    void parsesUnicodeEscape() {
        assertEquals(new JsonValue.JsonString("A"), Json.parse("\"\\u0041\""));
    }

    @Test
    void parsesSurrogatePairEscape() {
        JsonValue.JsonString s = assertInstanceOf(JsonValue.JsonString.class, Json.parse("\"\\uD83D\\uDE00\""));
        assertEquals("😀", s.value());
        assertEquals(2, s.value().length());
    }

    @Test
    void parsesRawNonAsciiString() {
        assertEquals(new JsonValue.JsonString("é"), Json.parse("\"é\""));
    }

    @Test
    void rejectsFractionNumber() {
        JsonParseException e = assertThrows(JsonParseException.class, () -> Json.parse("1.5"));
        assertTrue(e.getMessage().contains("integer minor units"));
    }

    @Test
    void rejectsIntegralLookingFraction() {
        rejects("1.0");
    }

    @Test
    void rejectsExponentNumber() {
        rejects("1e3");
        rejects("2E-1");
    }

    @Test
    void rejectsLeadingZero() {
        rejects("012");
    }

    @Test
    void rejectsPlusSign() {
        rejects("+1");
    }

    @Test
    void rejectsLoneMinus() {
        rejects("-");
    }

    @Test
    void rejectsNumberBeyondLongRange() {
        JsonParseException e = assertThrows(JsonParseException.class, () -> Json.parse("9223372036854775808"));
        assertTrue(e.getMessage().contains("out of range"));
    }

    @Test
    void rejectsDuplicateKeys() {
        JsonParseException e = assertThrows(JsonParseException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
        assertTrue(e.getMessage().contains("a"));
    }

    @Test
    void rejectsTrailingContent() {
        rejects("1 2");
        rejects("{} x");
    }

    @Test
    void rejectsEmptyAndWhitespaceInput() {
        rejects("");
        rejects("  \n ");
    }

    @Test
    void rejectsUnterminatedString() {
        rejects("\"abc");
    }

    @Test
    void rejectsInvalidEscape() {
        rejects("\"\\x\"");
    }

    @Test
    void rejectsShortUnicodeEscape() {
        rejects("\"\\u12\"");
    }

    @Test
    void rejectsRawControlCharInString() {
        rejects("\"\"");
    }

    @Test
    void rejectsUnterminatedContainers() {
        rejects("{\"a\":1");
        rejects("[1,2");
    }

    @Test
    void rejectsMissingColonOrComma() {
        rejects("{\"a\" 1}");
        rejects("[1 2]");
    }

    @Test
    void rejectsTrailingComma() {
        rejects("[1,]");
        rejects("{\"a\":1,}");
    }

    @Test
    void rejectsMisspelledLiterals() {
        rejects("nul");
        rejects("tru");
        rejects("falze");
    }

    @Test
    void allowsNestingToDepthLimit() {
        assertInstanceOf(JsonValue.JsonArray.class, Json.parse("[".repeat(64) + "]".repeat(64)));
    }

    @Test
    void rejectsNestingBeyondDepthLimit() {
        JsonParseException e = assertThrows(JsonParseException.class,
                () -> Json.parse("[".repeat(65) + "]".repeat(65)));
        assertTrue(e.getMessage().contains("deep"));
    }

    @Test
    void rejectsLeadingBom() {
        rejects("﻿{}");
    }

    @Test
    void errorCarriesLineAndColumn() {
        JsonParseException e = assertThrows(JsonParseException.class, () -> Json.parse("{\n\n1}"));
        assertEquals(3, e.line);
        assertEquals(1, e.column);
    }
}
