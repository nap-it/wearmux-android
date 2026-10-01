package com.example.peciwearables.integration.headless;

import static org.junit.Assert.*;
import org.junit.Test;

public class BridgeConfigurationTest {
    private static final String TOKEN = "0123456789abcdef";
    @Test public void acceptsValidEndpoint() { assertTrue(BridgeConfiguration.valid("ws://172.28.178.197:8765/android-ble", TOKEN)); }
    @Test public void rejectsUnsafeEndpointForms() { assertFalse(BridgeConfiguration.valid("ws:///android-ble", TOKEN)); assertFalse(BridgeConfiguration.valid("ws://user:pass@host/android-ble", TOKEN)); assertFalse(BridgeConfiguration.valid("ws://host/android-ble?x=1", TOKEN)); assertFalse(BridgeConfiguration.valid("ws://host/android-ble#x", TOKEN)); }
    @Test public void enforcesTokenBoundsAndWhitespace() { assertFalse(BridgeConfiguration.valid("ws://host/android-ble", "short")); assertFalse(BridgeConfiguration.valid("ws://host/android-ble", "0123456789abcde\n")); assertFalse(BridgeConfiguration.valid("ws://host/android-ble", "x".repeat(257))); }
    @Test public void allowsLocalAuthFreeAndBuildsOptionalHeader() { assertTrue(BridgeConfiguration.valid("ws://host/android-ble", "")); assertTrue(BridgeConfiguration.valid("ws://host/android-ble", null)); assertNull(BridgeConfiguration.authorizationHeader("")); assertEquals("Bearer "+TOKEN, BridgeConfiguration.authorizationHeader(TOKEN)); }
}
