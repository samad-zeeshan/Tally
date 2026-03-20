package dev.tally.obs;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * One line per record: UTC instant, level, request id, logger, then the message.
 *
 * The formatter reads RequestContext.CURRENT itself. That is safe because Handler.publish runs
 * synchronously on the thread that logged, where the request binding is still in scope; it would break
 * under an async handler. Lines emitted outside a request (startup, shutdown) render req=-.
 */
public final class LineFormatter extends Formatter {
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @Override
    public String format(LogRecord record) {
        StringBuilder line = new StringBuilder()
                .append(TIMESTAMP.format(record.getInstant())).append(' ')
                .append(String.format("%-7s", record.getLevel().getName())).append(" req=")
                .append(RequestContext.currentIdOr("-")).append(' ')
                .append(record.getLoggerName()).append(' ')
                .append(formatMessage(record));
        // The one multi-line case: an exception's class and message on the line, its frames beneath, indented.
        Throwable thrown = record.getThrown();
        if (thrown != null) {
            line.append(" ex=").append(thrown.getClass().getName()).append(": ").append(thrown.getMessage());
            for (StackTraceElement frame : thrown.getStackTrace()) {
                line.append(System.lineSeparator()).append("  at ").append(frame);
            }
        }
        return line.append(System.lineSeparator()).toString();
    }
}
