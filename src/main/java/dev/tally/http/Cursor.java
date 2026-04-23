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
 * The cursor is signed. Unsigned it was only base64 of the posting id, which anyone could rewrite to any
 * number; that was contained because the query is scoped to the account in the path, but "contained by a
 * check somewhere else" is not a property you want a public identifier to depend on. An HMAC makes the
 * cursor a token the server issued rather than a number the client asserts.
 */
public final class Cursor {
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    // 128 bits of HMAC-SHA-256 is far past forgery reach and keeps the cursor short. RFC 2104 sanctions
    // truncation to at least half the output; this is exactly half.
    private static final int SIGNATURE_BYTES = 16;
    private static final String HMAC = "HmacSHA256";

    private final SecretKeySpec key;

    // The key is derived from the configured API token rather than being new configuration of its own,
    // and the label keeps it a different key from the token: a cursor signature can never be replayed as
    // a credential, and rotating the token invalidates outstanding cursors, which is the safe direction.
    public Cursor(String apiToken) {
        this.key = new SecretKeySpec(sha256("tally-cursor-v1:" + apiToken), HMAC);
    }

    // Opaque and versioned on purpose: the raw posting id never leaks into the public API, and the
    // "v1:" tag keeps a future format change detectable.
    public String encode(long postingId) {
        String payload = "v1:" + postingId;
        String signed = payload + ":" + ENCODER.encodeToString(sign(payload));
        return ENCODER.encodeToString(signed.getBytes(StandardCharsets.UTF_8));
    }

    // Every rejection is the same 400 INVALID_CURSOR with the same message, whether the cursor was
    // truncated, re-encoded, or signed with the wrong key: a caller learns that it is not valid, never
    // which check caught it.
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
        // Constant time, the same way Auth compares the token: a byte-at-a-time comparison would let a
        // caller time its way to a valid signature one byte per round.
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
