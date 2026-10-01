package com.wearmux.android.integration.headless;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Small deterministic queue state machine used to enforce one ATT operation at a time. */
final class OperationQueue<T> {
    private final int limit; private final ArrayDeque<T> pending = new ArrayDeque<>(); private T active;
    OperationQueue(int limit) { if (limit < 1) throw new IllegalArgumentException("limit"); this.limit=limit; }
    boolean offer(T value) { if (value == null || pending.size() + (active == null ? 0 : 1) >= limit) return false; pending.add(value); return true; }
    T begin() { if (active == null) active=pending.poll(); return active; }
    T peek() { return active != null ? active : pending.peek(); }
    boolean isActive(T value) { return active == value; }
    T complete(T value) { if (active != value) return null; T done=active; active=null; return done; }
    void clear() { pending.clear(); active=null; }
    List<T> clearAndReturn() { List<T> all=new ArrayList<>(); if(active!=null) all.add(active); all.addAll(pending); clear(); return all; }
    int size() { return pending.size() + (active == null ? 0 : 1); }
}
