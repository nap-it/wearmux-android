package com.wearmux.android.integration.headless;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Clock snapshots are immutable and captured on the callback thread, before Handler.post. */
final class BridgeTiming {
    private final LongSupplier wallMillis;
    private final LongSupplier elapsedNanos;
    private final String instance = UUID.randomUUID().toString();
    private final AtomicLong sequence = new AtomicLong();

    BridgeTiming(LongSupplier wallMillis, LongSupplier elapsedNanos) {
        this.wallMillis = wallMillis;
        this.elapsedNanos = elapsedNanos;
    }

    Snapshot capture() {
        return new Snapshot(instance, sequence.incrementAndGet(), wallMillis.getAsLong(), elapsedNanos.getAsLong());
    }

    static final class Snapshot {
        final String processInstance;
        final long sequence, wallMillis, monoNanos;
        Snapshot(String instance, long sequence, long wallMillis, long monoNanos) {
            this.processInstance = instance;
            this.sequence = sequence;
            this.wallMillis = wallMillis;
            this.monoNanos = monoNanos;
        }
        String clockDomain() { return "android:" + processInstance + ":elapsed_realtime"; }
        String traceEventId() { return processInstance + ":" + sequence; }
    }
}
