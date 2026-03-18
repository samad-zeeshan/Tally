package dev.tally;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
