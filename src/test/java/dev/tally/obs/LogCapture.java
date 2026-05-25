package dev.tally.obs;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Attaches a handler to the "dev.tally" logger and collects lines, through LineFormatter unless told otherwise.
 * Formatting happens in publish, synchronously on the logging thread, so the request id binding is
 * still in scope and the captured lines carry the right req=.
 */
public final class LogCapture implements AutoCloseable {
    private final Logger root = Logger.getLogger("dev.tally");
    private final Level priorLevel = root.getLevel();
    // The lines are added on the request's virtual thread and read on the test thread; a copy-on-write
    // list gives both thread safety and the visibility a plain ArrayList would not guarantee.
    private final List<String> lines = new CopyOnWriteArrayList<>();
    private final Handler handler;

    public LogCapture() {
        this(new LineFormatter());
    }

    public LogCapture(Formatter formatter) {
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                lines.add(formatter.format(record));
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        handler.setLevel(Level.ALL);
        root.addHandler(handler);
        root.setLevel(Level.FINE);   // capture down to FINE; prior level restored on close
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }

    @Override
    public void close() {
        root.removeHandler(handler);
        root.setLevel(priorLevel);
    }
}
