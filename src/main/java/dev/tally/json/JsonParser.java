package dev.tally.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recursive-descent JSON parser over a char array, with the scanner fused into the parser.
 *
 * A separate tokenizer would add a layer and an allocation per token without making anything
 * clearer at this grammar size.
 */
final class JsonParser {
    private static final int MAX_DEPTH = 64;

    private final String text;
    private final int n;
    private int pos;
    private int depth;

    JsonParser(String text) {
        this.text = text;
        this.n = text.length();
    }

    JsonValue parse() {
        skipWhitespace();
        if (pos >= n) {
            throw error("empty input");
        }
        JsonValue value = parseValue();
        skipWhitespace();
        // A leading BOM lands here too: it is not whitespace, so it is an unexpected character.
        if (pos < n) {
            throw error("unexpected trailing content after the top-level value");
        }
        return value;
    }

    private JsonValue parseValue() {
        char c = peek();
        return switch (c) {
            case '{' -> parseObject();
            case '[' -> parseArray();
            case '"' -> new JsonValue.JsonString(readString());
            case 't', 'f', 'n' -> readLiteral();
            default -> {
                if (c == '-' || isDigit(c)) {
                    yield readNumber();
                }
                throw error("unexpected character '" + c + "'");
            }
        };
    }

    private JsonValue parseObject() {
        pos++;   // consume {
        if (++depth > MAX_DEPTH) {
            throw error("nesting is too deep, the limit is " + MAX_DEPTH);
        }
        Map<String, JsonValue> members = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            depth--;
            return new JsonValue.JsonObject(members);
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected a string key");
            }
            String key = readString();
            // A money API must not let a duplicate key be resolved by parser whim.
            if (members.containsKey(key)) {
                throw error("duplicate key: " + key);
            }
            skipWhitespace();
            if (peek() != ':') {
                throw error("expected ':' after object key");
            }
            pos++;
            skipWhitespace();
            members.put(key, parseValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == '}') {
                pos++;
                depth--;
                return new JsonValue.JsonObject(members);
            } else {
                throw error("expected ',' or '}' in object");
            }
        }
    }

    private JsonValue parseArray() {
        pos++;   // consume [
        if (++depth > MAX_DEPTH) {
            throw error("nesting is too deep, the limit is " + MAX_DEPTH);
        }
        List<JsonValue> items = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            depth--;
            return new JsonValue.JsonArray(items);
        }
        while (true) {
            skipWhitespace();
            items.add(parseValue());
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                pos++;
            } else if (c == ']') {
                pos++;
                depth--;
                return new JsonValue.JsonArray(items);
            } else {
                throw error("expected ',' or ']' in array");
            }
        }
    }

    private String readString() {
        pos++;   // consume opening quote
        StringBuilder sb = new StringBuilder();
        while (pos < n) {
            char c = text.charAt(pos++);
            if (c == '"') {
                return sb.toString();
            }
            if (c == '\\') {
                if (pos >= n) {
                    break;
                }
                char esc = text.charAt(pos++);
                switch (esc) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> sb.append(readHex4());   // one UTF-16 unit, so surrogate pairs combine naturally
                    default -> throw error("invalid escape \\" + esc);
                }
            } else if (c < 0x20) {
                throw error("unescaped control character in string");
            } else {
                sb.append(c);
            }
        }
        throw error("unterminated string");
    }

    private char readHex4() {
        if (pos + 4 > n) {
            throw error("incomplete \\u escape");
        }
        int value = 0;
        for (int i = 0; i < 4; i++) {
            int digit = Character.digit(text.charAt(pos++), 16);
            if (digit < 0) {
                throw error("invalid hex digit in \\u escape");
            }
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private JsonValue readNumber() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        if (pos >= n || !isDigit(text.charAt(pos))) {
            throw error("expected a digit");
        }
        if (text.charAt(pos) == '0') {
            pos++;
            if (pos < n && isDigit(text.charAt(pos))) {
                throw error("leading zeros are not allowed");
            }
        } else {
            while (pos < n && isDigit(text.charAt(pos))) {
                pos++;
            }
        }
        // The hard line of the project: money is integer minor units, so no fractional number exists.
        if (pos < n) {
            char c = text.charAt(pos);
            if (c == '.' || c == 'e' || c == 'E') {
                throw error("fractional numbers are not accepted, amounts are integer minor units");
            }
        }
        String literal = text.substring(start, pos);
        try {
            return new JsonValue.JsonNumber(Long.parseLong(literal));
        } catch (NumberFormatException outOfRange) {
            throw error("number out of range for a 64-bit integer: " + literal);
        }
    }

    private JsonValue readLiteral() {
        if (text.regionMatches(pos, "true", 0, 4)) {
            pos += 4;
            return new JsonValue.JsonBool(true);
        }
        if (text.regionMatches(pos, "false", 0, 5)) {
            pos += 5;
            return new JsonValue.JsonBool(false);
        }
        if (text.regionMatches(pos, "null", 0, 4)) {
            pos += 4;
            return new JsonValue.JsonNull();
        }
        throw error("invalid literal");
    }

    private void skipWhitespace() {
        while (pos < n) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                pos++;
            } else {
                return;
            }
        }
    }

    private char peek() {
        if (pos >= n) {
            throw error("unexpected end of input");
        }
        return text.charAt(pos);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // Line and column are computed from the error position, so the scanner does not carry them.
    private JsonParseException error(String message) {
        int line = 1;
        int column = 1;
        int at = Math.min(pos, n);
        for (int i = 0; i < at; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new JsonParseException(message, line, column);
    }
}
