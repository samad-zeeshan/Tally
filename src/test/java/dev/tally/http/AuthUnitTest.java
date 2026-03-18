package dev.tally.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auth.check and the fail-closed token vetting, without a server in the way.
 */
class AuthUnitTest {
    private static final String TOKEN = "unit-token-0123456789";   // 21 chars

    @Test
    void checkMatchesAndRejects() {
        Auth auth = new Auth(TOKEN);
        assertEquals(Auth.Result.OK, auth.check("Bearer " + TOKEN));
        assertEquals(Auth.Result.INVALID, auth.check("Bearer unit-token-9876543210"));   // same length, wrong
        assertEquals(Auth.Result.INVALID, auth.check("Bearer short"));                    // different length, wrong
        assertEquals(Auth.Result.MISSING, auth.check(null));
    }

    @Test
    void missingEnvTokenFailsStartup() {
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> Auth.requireToken(name -> null));
        assertTrue(e.getMessage().contains("TALLY_API_TOKEN"));
    }

    @Test
    void shortTokenFailsStartup() {
        assertThrows(IllegalStateException.class, () -> Auth.requireToken(name -> "0123456789abcde"));   // 15 chars
    }
}
