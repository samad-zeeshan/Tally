package dev.tally.store;

import dev.tally.core.TransferRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A stable fingerprint of a transfer's request tuple, so a stored key can detect a reuse with a
 * different request without keeping the raw fields around for comparison.
 */
final class Fingerprint {
    private Fingerprint() {}

    static String of(TransferRequest request) {
        String canonical = request.from().value() + "|" + request.to().value() + "|" + request.amountMinor();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }
}
