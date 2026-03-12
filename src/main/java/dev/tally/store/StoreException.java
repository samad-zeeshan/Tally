package dev.tally.store;

/**
 * An unchecked wrapper over a SQLException, so the Store seam stays free of checked JDBC exceptions.
 */
public final class StoreException extends RuntimeException {
    public StoreException(String message) {
        super(message);
    }

    public StoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
