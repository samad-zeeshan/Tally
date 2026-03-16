package dev.tally.http;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The opaque statement cursor codec. One place encodes and decodes, so the two can never drift.
 */
public final class Cursor {
    private Cursor() {}

    // Opaque and versioned on purpose: the raw posting id never leaks into the public API, and the
    // "v1:" tag keeps a future format change detectable.
    public static String encode(long postingId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(("v1:" + postingId).getBytes(StandardCharsets.UTF_8));
    }

    // A malformed cursor is a client mistake; the handler turns this into 400 INVALID_CURSOR.
    public static long decode(String cursor) {
        String decoded;
        try {
            decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            throw new ApiException(ErrorCode.INVALID_CURSOR, "cursor", "cursor is not valid");
        }
        if (!decoded.startsWith("v1:")) {
            throw new ApiException(ErrorCode.INVALID_CURSOR, "cursor", "cursor is not valid");
        }
        try {
            return Long.parseLong(decoded.substring(3));
        } catch (NumberFormatException notALong) {
            throw new ApiException(ErrorCode.INVALID_CURSOR, "cursor", "cursor is not valid");
        }
    }
}
