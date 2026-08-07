package dev.tally.obs;

import java.util.logging.ConsoleHandler;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Programmatic java.util.logging setup: one stderr handler, single-line text or JSON lines, no
 * logging.properties. Stderr, not a file, because it suits containers and dodges OneDrive's sync
 * lock on this machine.
 */
public final class Logs {
    private static final String ROOT = "dev.tally";

    private Logs() {}

    public static synchronized void init() {
        Logger root = Logger.getLogger(ROOT);
        root.setUseParentHandlers(false);   // do not also print through the JDK's default root handler
        for (Handler existing : root.getHandlers()) {
            root.removeHandler(existing);
        }
        Level level = parseLevel(System.getenv("TALLY_LOG_LEVEL"));
        ConsoleHandler handler = new ConsoleHandler();   // ConsoleHandler writes to stderr
        handler.setFormatter(formatterFor(System.getenv("TALLY_LOG_FORMAT")));
        handler.setLevel(level);
        root.setLevel(level);
        root.addHandler(handler);
    }

    public static Logger get(Class<?> cls) {
        return Logger.getLogger(cls.getName());
    }

    // Line stays the default because it reads well in a terminal. The Kubernetes ConfigMap asks for json.
    static Formatter formatterFor(String name) {
        return "json".equalsIgnoreCase(name) ? new JsonFormatter() : new LineFormatter();
    }

    private static Level parseLevel(String name) {
        if (name == null) {
            return Level.INFO;
        }
        return switch (name.toUpperCase()) {
            case "FINE" -> Level.FINE;
            case "WARNING" -> Level.WARNING;
            default -> Level.INFO;
        };
    }
}
