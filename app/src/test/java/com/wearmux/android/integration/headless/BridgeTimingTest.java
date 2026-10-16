package com.wearmux.android.integration.headless;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class BridgeTimingTest {
    @Test public void callbackSnapshotSurvivesHandlerQueueDelay() {
        AtomicLong wall = new AtomicLong(1000);
        AtomicLong mono = new AtomicLong(2000);
        BridgeTiming timing = new BridgeTiming(wall::get, mono::get);
        BridgeTiming.Snapshot callback = timing.capture();
        wall.set(1500); mono.set(6000);
        BridgeTiming.Snapshot queued = timing.capture();
        assertEquals(1000, callback.wallMillis);
        assertEquals(2000, callback.monoNanos);
        assertEquals(1500, queued.wallMillis);
        assertEquals(6000, queued.monoNanos);
        assertEquals(callback.clockDomain(), queued.clockDomain());
        assertEquals(callback.sequence + 1, queued.sequence);
        assertNotEquals(callback.traceEventId(), queued.traceEventId());
    }

    @Test public void restartIdentityPreventsMixingClockMappings() {
        BridgeTiming.Snapshot first = new BridgeTiming(() -> 1000L, () -> 2000L).capture();
        BridgeTiming.Snapshot restarted = new BridgeTiming(() -> 1000L, () -> 2000L).capture();
        assertNotEquals(first.clockDomain(), restarted.clockDomain());
        assertNotEquals(first.traceEventId(), restarted.traceEventId());
        assertEquals("android:" + first.processInstance + ":elapsed_realtime", first.clockDomain());
    }
}
