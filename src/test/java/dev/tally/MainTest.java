package dev.tally;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    // Pins the property names and values against a typo. It does not prove the server honours them,
    // which is a manual check (curl --limit-rate against a running instance).
    @Test
    void serverTuningSetsTimeoutProperties() {
        Main.applyServerTuning();
        assertEquals("10000", System.getProperty("sun.net.httpserver.maxReqTime"));
        assertEquals("30000", System.getProperty("sun.net.httpserver.maxRspTime"));
        assertEquals("16384", System.getProperty("sun.net.httpserver.maxReqHeaderSize"));
    }

    @Test
    void dnsAnswersAreCachedForSecondsNotTheDefaultThirty() {
        Main.applyServerTuning();
        assertEquals("5", java.security.Security.getProperty("networkaddress.cache.ttl"));
    }

    // Compose and a laptop migrate at startup. Kubernetes turns it off and runs a Job instead,
    // so only an explicit false may skip it.
    @Test
    void migrationsRunOnStartUnlessExplicitlyTurnedOff() {
        assertTrue(Main.migrateOnStart(Map.<String, String>of()::get));
        assertTrue(Main.migrateOnStart(Map.of("TALLY_MIGRATE_ON_START", "true")::get));
        assertTrue(Main.migrateOnStart(Map.of("TALLY_MIGRATE_ON_START", "no")::get));
        assertFalse(Main.migrateOnStart(Map.of("TALLY_MIGRATE_ON_START", "false")::get));
        assertFalse(Main.migrateOnStart(Map.of("TALLY_MIGRATE_ON_START", "FALSE")::get));
    }
}
