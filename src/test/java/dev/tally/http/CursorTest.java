package dev.tally.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The cursor is a token the server issued, not a number the client asserts. Every way of getting one
 * wrong lands on the same 400.
 */
class CursorTest {
    private static final String TOKEN = "test-token-0123456789";

    private final Cursor cursor = new Cursor(TOKEN);

    private static String encodeRaw(String plaintext) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
    }

    private static String decodeRaw(String encoded) {
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    // The same message whatever failed, so a caller cannot learn which check caught it.
    private void assertRejected(String bad) {
        ApiException e = assertThrows(ApiException.class, () -> cursor.decode(bad));
        assertEquals(ErrorCode.INVALID_CURSOR, e.code);
        assertEquals("cursor", e.field);
        assertEquals(400, e.code.status);
        assertEquals("cursor is not valid", e.getMessage());
    }

    @Test
    void roundTrips() {
        assertEquals(4242L, cursor.decode(cursor.encode(4242L)));
        assertEquals(Long.MAX_VALUE, cursor.decode(cursor.encode(Long.MAX_VALUE)));
        assertEquals(0L, cursor.decode(cursor.encode(0L)));
    }

    @Test
    void aRewrittenPostingIdIsRejected() {
        // The whole point. Before signing, this was base64 of "v1:<id>" and anyone could edit the number.
        String forged = encodeRaw("v1:999999");
        assertRejected(forged);
        assertRejected(encodeRaw("v1:999999:" + encodeRaw("not-a-real-signature")));
    }

    @Test
    void aValidCursorWithItsIdEditedIsRejected() {
        String plaintext = decodeRaw(cursor.encode(500L));
        String signature = plaintext.substring(plaintext.lastIndexOf(':') + 1);
        // Same signature, different id: exactly the attack a keyset cursor invites.
        assertRejected(encodeRaw("v1:501:" + signature));
    }

    @Test
    void aCursorFromAnotherTokenIsRejected() {
        // Rotating the API token has to invalidate outstanding cursors, since the key comes from it.
        String fromElsewhere = new Cursor("a-different-api-token").encode(77L);
        assertRejected(fromElsewhere);
    }

    @Test
    void malformedCursorsAreRejected() {
        assertRejected("garbage");
        assertRejected("");
        assertRejected("!!!not base64!!!");
        assertRejected(encodeRaw("v1:"));
        assertRejected(encodeRaw("v2:5:sig"));
        assertRejected(encodeRaw("v1:notanumber:sig"));
        assertRejected(encodeRaw(""));
    }

    @Test
    void aTruncatedSignatureIsRejected() {
        String encoded = cursor.encode(600L);
        assertRejected(encoded.substring(0, encoded.length() - 4));
        String plaintext = decodeRaw(encoded);
        assertRejected(encodeRaw(plaintext.substring(0, plaintext.length() - 2)));
    }

    @Test
    void theCursorStaysOpaqueAndCarriesASignature() {
        String encoded = cursor.encode(31337L);
        assertNotEquals("31337", encoded);
        String plaintext = decodeRaw(encoded);
        assertTrue(plaintext.startsWith("v1:31337:"), plaintext);
        // A 128-bit truncation of HMAC-SHA-256 is 22 base64url characters.
        assertEquals(22, plaintext.substring(plaintext.lastIndexOf(':') + 1).length(), plaintext);
    }

    @Test
    void encodingIsDeterministicForOneKey() {
        // Two instances from the same token agree, so a restart does not invalidate live cursors.
        assertEquals(cursor.encode(12L), new Cursor(TOKEN).encode(12L));
    }
}
