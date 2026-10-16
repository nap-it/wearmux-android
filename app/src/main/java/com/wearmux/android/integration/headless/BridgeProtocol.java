package com.wearmux.android.integration.headless;

import android.util.Base64;
import org.json.JSONException;
import org.json.JSONObject;

/** Wire framing and validation for Android BLE bridge protocol v1. */
final class BridgeProtocol {
    static final int VERSION = 1;
    static final int MAX_FRAME = 64 * 1024;
    private BridgeProtocol() {}

    static JSONObject hello() { return put(new JSONObject(), "type", "hello", "version", VERSION); }
    static JSONObject timedHello(BridgeTiming.Snapshot time) {
        return timed(put(hello(), "timingVersion", 1), time);
    }
    static JSONObject timed(JSONObject frame, BridgeTiming.Snapshot time) {
        return time == null ? frame : put(frame, "timing", timing(time));
    }
    static JSONObject timing(BridgeTiming.Snapshot time) {
        return put(new JSONObject(), "clock_domain", time.clockDomain(),
                "mono_clock", "android.os.SystemClock.elapsedRealtimeNanos",
                "process_instance", time.processInstance, "sequence", time.sequence,
                "trace_event_id", time.traceEventId(),
                "wall_time_ns", Long.toString(time.wallMillis) + "000000",
                "mono_time_ns", Long.toString(time.monoNanos),
                "wall_clock_resolution_ns", "1000000");
    }
    static JSONObject clockSample(String id, BridgeTiming.Snapshot received, BridgeTiming.Snapshot sent) {
        return put(new JSONObject(), "type", "clockSample", "probeId", id,
                "received", timing(received), "sent", timing(sent));
    }
    static JSONObject connected(String id, String name, int mtu) { return put(new JSONObject(), "type", "connected", "deviceId", id, "name", name == null ? "" : name, "mtu", mtu); }
    static JSONObject value(String id, String characteristic, byte[] bytes) { return put(new JSONObject(), "type", "value", "deviceId", id, "characteristic", characteristic, "data", Base64.encodeToString(bytes, Base64.NO_WRAP)); }
    static JSONObject writeResult(String id, String requestId, boolean ok, String error) {
        JSONObject o = put(new JSONObject(), "type", "writeResult", "deviceId", id, "requestId", requestId, "ok", ok);
        if (!ok && error != null) put(o, "error", error); return o;
    }
    static JSONObject disconnected(String id, String reason) { return put(new JSONObject(), "type", "disconnected", "deviceId", id, "reason", reason == null ? "unknown" : reason); }
    static JSONObject error(String reason) { return put(new JSONObject(), "type", "error", "error", reason); }

    private static JSONObject put(JSONObject o, Object... fields) { try { for (int i=0;i<fields.length;i+=2) o.put(String.valueOf(fields[i]), fields[i+1]); return o; } catch (JSONException e) { throw new IllegalStateException(e); } }

    static byte[] data(JSONObject frame) throws IllegalArgumentException {
        try { return Base64.decode(frame.getString("data"), Base64.DEFAULT); }
        catch (Exception e) { throw new IllegalArgumentException("invalid base64 data"); }
    }
}
