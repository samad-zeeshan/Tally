package dev.tally.obs;

/**
 * Redaction for log lines: show the last four characters of an internal id, no more.
 *
 * Last-4 over a hash prefix because it can be eyeballed against a known id while debugging; a hash is
 * stronger but not matchable by eye, and these are internal UUIDs, not secrets.
 */
public final class Redact {
    private Redact() {}

    public static String account(String id) {
        if (id == null || id.length() < 4) {
            return "...";
        }
        return "..." + id.substring(id.length() - 4);
    }

    // Clients may embed order ids in idempotency keys, so keys get the same treatment.
    public static String key(String key) {
        return account(key);
    }
}
