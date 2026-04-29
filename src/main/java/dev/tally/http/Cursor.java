package dev.tally.http;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * The opaque statement cursor codec. One instance encodes and decodes, so the two can never drift.
 *
 * The HMAC is what makes a cursor a token the server issued. Unsigned it was base64 of the posting id,
 * which anyone could rewrite, and "contained by a check somewhere else" is a bad thing for a public
 * identifier to rest on.
 */
public final class Cursor {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    // RFC 2104 sanctions truncating an HMAC to half its output, and 128 bits keeps the cursor short.
    private static final int SIGNATURE_BYTES = 16;
    private static final String HMAC = "HmacSHA256";

    private final SecretKeySpec key;

    // Derived from the API token so statements need no second secret, and labelled so it is a different
    // key from the token itself: a signature can never be replayed as a credential, and rotating the
    // token invalidates outstanding cursors, which is the safe direction to fail.
    public Cursor(String apiToken) {
        this.key = new SecretKeySpec(sha256("tally-cursor-v1:" + apiToken), HMAC);
    }

    // The "v1:" tag keeps a future format change detectable, and the raw posting id stays inside.
    public String encode(long postingId) {
        String payload = "v1:" + postingId;
        String signed = payload + ":" + ENCODER.encodeToString(sign(payload));
        return ENCODER.encodeToString(signed.getBytes(StandardCharsets.UTF_8));
    }

    // Truncated, re-encoded or signed with the wrong key all land on the same 400: a caller learns the
    // cursor is not valid, never which check caught it.
    public long decode(String cursor) {
        String decoded;
        try {
            decoded = new String(DECODER.decode(cursor), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException notBase64) {
            throw invalid();
        }
        int lastColon = decoded.lastIndexOf(':');
        if (!decoded.startsWith("v1:") || lastColon < 3) {
            throw invalid();
        }
        String payload = decoded.substring(0, lastColon);
        byte[] presented;
        try {
            presented = DECODER.decode(decoded.substring(lastColon + 1));
        } catch (IllegalArgumentException notBase64) {
            throw invalid();
        }
        // Constant time, as Auth compares the token: a byte-at-a-time comparison lets a caller time its
        // way to a valid signature one byte per round.
        if (!MessageDigest.isEqual(sign(payload), presented)) {
            throw invalid();
        }
        try {
            return Long.parseLong(payload.substring(3));
        } catch (NumberFormatException notALong) {
            throw invalid();
        }
    }

    private static ApiException invalid() {
        return new ApiException(ErrorCode.INVALID_CURSOR, "cursor", "cursor is not valid");
    }

    private byte[] sign(String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            byte[] full = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[SIGNATURE_BYTES];
            System.arraycopy(full, 0, truncated, 0, SIGNATURE_BYTES);
            return truncated;
        } catch (GeneralSecurityException unavailable) {
            throw new IllegalStateException(HMAC + " is required but unavailable", unavailable);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }
}
