package dev.tally.json;

/**
 * A malformed JSON document, carrying a 1-based line and column so a client can find the spot.
 */
public final class JsonParseException extends RuntimeException {
    public final int line;
    public final int column;

    public JsonParseException(String message, int line, int column) {
        super(message + " at line " + line + ", column " + column);
        this.line = line;
        this.column = column;
    }
}
