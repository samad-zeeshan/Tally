package dev.tally.obs;

import dev.tally.json.Json;
import dev.tally.json.JsonValue;
import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonFormatterTest {
    private final JsonFormatter formatter = new JsonFormatter();

    private LogRecord record(Level level, String message) {
        LogRecord r = new LogRecord(level, message);
        r.setLoggerName("dev.tally.http.HttpKernel");
        return r;
    }

    private String formatInRequest(String requestId, LogRecord record) {
        String[] captured = new String[1];
        ScopedValue.where(RequestContext.CURRENT, new RequestContext(requestId))
                .run(() -> captured[0] = formatter.format(record));
        return captured[0];
    }

    private static JsonValue.JsonObject parse(String line) {
        assertTrue(line.endsWith("\n") || line.endsWith("\r\n"), "one record ends one line");
        assertEquals(1, line.strip().lines().count(), "a record is exactly one line: " + line);
        return (JsonValue.JsonObject) Json.parse(line.strip());
    }

    private static String str(JsonValue.JsonObject o, String key) {
        return ((JsonValue.JsonString) o.members().get(key)).value();
    }

    @Test
    void anAccessLineBecomesOneObjectWithItsPairsAsFields() {
        JsonValue.JsonObject o = parse(formatInRequest("req-abcdef01",
                record(Level.INFO, "method=POST path=/transfers status=201 ms=14")));
        assertTrue(str(o, "ts").matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"), str(o, "ts"));
        assertEquals("INFO", str(o, "level"));
        assertEquals("req-abcdef01", str(o, "requestId"));
        assertEquals("dev.tally.http.HttpKernel", str(o, "logger"));
        assertEquals("POST", str(o, "method"));
        assertEquals("/transfers", str(o, "path"));
        assertEquals(201, ((JsonValue.JsonNumber) o.members().get("status")).value());
        assertEquals(14, ((JsonValue.JsonNumber) o.members().get("ms")).value());
        assertFalse(o.members().containsKey("msg"), "no free text, so no msg field");
    }

    @Test
    void freeWordsStayInMsg() {
        JsonValue.JsonObject o = parse(formatInRequest("req-abcdef01",
                record(Level.INFO, "transfer ok from=...ab12 amount=250 key=...9f0e")));
        assertEquals("transfer ok", str(o, "msg"));
        assertEquals("...ab12", str(o, "from"));
        assertEquals(250, ((JsonValue.JsonNumber) o.members().get("amount")).value());
    }

    @Test
    void aMessagePairCannotOverwriteAReservedField() {
        JsonValue.JsonObject o = parse(formatInRequest("req-abcdef01", record(Level.INFO, "level=FAKE requestId=spoofed")));
        assertEquals("INFO", str(o, "level"));
        assertEquals("req-abcdef01", str(o, "requestId"));
        assertEquals("level=FAKE requestId=spoofed", str(o, "msg"), "the colliding pairs are kept as text");
    }

    @Test
    void outsideARequestThereIsNoRequestId() {
        JsonValue.JsonObject o = parse(formatter.format(record(Level.INFO, "startup")));
        assertFalse(o.members().containsKey("requestId"));
        assertEquals("startup", str(o, "msg"));
    }

    @Test
    void anExceptionStaysOnTheSameLine() {
        LogRecord r = record(Level.SEVERE, "unhandled error");
        r.setThrown(new IllegalStateException("the cause"));
        JsonValue.JsonObject o = parse(formatInRequest("req-abcdef01", r));
        assertEquals("java.lang.IllegalStateException: the cause", str(o, "error"));
        JsonValue.JsonArray stack = assertInstanceOf(JsonValue.JsonArray.class, o.members().get("stack"));
        assertFalse(stack.items().isEmpty());
    }

    @Test
    void quotesAndNewlinesInAMessageCannotBreakTheLine() {
        JsonValue.JsonObject o = parse(formatter.format(record(Level.WARNING, "bad \"input\"\nlevel=ERROR forged")));
        assertEquals("WARNING", str(o, "level"));
        assertTrue(str(o, "msg").contains("\"input\""), str(o, "msg"));
    }

    @Test
    void theFormatIsChosenByName() {
        assertInstanceOf(JsonFormatter.class, Logs.formatterFor("json"));
        assertInstanceOf(JsonFormatter.class, Logs.formatterFor("JSON"));
        assertInstanceOf(LineFormatter.class, Logs.formatterFor("line"));
        assertInstanceOf(LineFormatter.class, Logs.formatterFor(null));
    }
}
