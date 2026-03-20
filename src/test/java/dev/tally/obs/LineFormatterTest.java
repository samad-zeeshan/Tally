package dev.tally.obs;

import org.junit.jupiter.api.Test;

import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LineFormatterTest {
    private final LineFormatter formatter = new LineFormatter();

    private LogRecord record(Level level, String message) {
        LogRecord r = new LogRecord(level, message);
        r.setLoggerName("dev.tally.http.HttpKernel");
        return r;
    }

    @Test
    void singleLineKeyValueFormat() {
        String[] captured = new String[1];
        ScopedValue.where(RequestContext.CURRENT, new RequestContext("req-abcdef01"))
                .run(() -> captured[0] = formatter.format(record(Level.INFO, "method=POST path=/transfers status=201 ms=14")));
        String line = captured[0].strip();   // drop the trailing line separator
        assertTrue(line.matches(
                "\\S+ INFO\\s+req=req-abcdef01 dev\\.tally\\.http\\.HttpKernel method=POST path=/transfers status=201 ms=14"),
                line);
    }

    @Test
    void unboundContextRendersDash() {
        String line = formatter.format(record(Level.INFO, "startup"));
        assertTrue(line.contains("req=-"), line);
    }

    @Test
    void exceptionIndentedOnFollowingLines() {
        LogRecord r = record(Level.SEVERE, "boom");
        r.setThrown(new IllegalStateException("the cause"));
        String[] lines = formatter.format(r).split("\\R", -1);
        assertTrue(lines[0].contains("ex=java.lang.IllegalStateException: the cause"), lines[0]);
        assertTrue(lines[1].startsWith("  at "), lines[1]);
        assertFalse(lines[0].startsWith("  "), "the first line is not indented");
    }
}
