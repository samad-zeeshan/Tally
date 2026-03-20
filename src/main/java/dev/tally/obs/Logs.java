package dev.tally.obs;

import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Programmatic java.util.logging setup: one stderr handler with the single-line formatter, no
 * logging.properties. Stderr, not a file, because it suits containers and dodges OneDrive's sync
 * lock on this machine. See ADR-0016.
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
        handler.setFormatter(new LineFormatter());
        handler.setLevel(level);
        root.setLevel(level);
        root.addHandler(handler);
    }

    public static Logger get(Class<?> cls) {
        return Logger.getLogger(cls.getName());
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
