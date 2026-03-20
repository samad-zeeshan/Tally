package dev.tally.obs;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RedactTest {

    @Test
    void lastFourShown() {
        String id = "abcd1234-5678-90ab-cdef-000000ab89ab";
        assertEquals("...89ab", Redact.account(id));
    }

    @Test
    void shortAndNullInputsCollapseToDots() {
        assertEquals("...", Redact.account(null));
        assertEquals("...", Redact.account("abc"));   // fewer than 4 chars, nothing safe to show
    }

    @Test
    void redactionHidesTheBulkOfTheId() {
        String id = UUID.randomUUID().toString();
        assertFalse(Redact.account(id).contains(id.substring(0, 8)), "the id prefix must not survive");
    }
}
