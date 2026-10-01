package com.example.peciwearables.integration.headless;

import static org.junit.Assert.*;
import org.junit.Test;

public class OperationQueueTest {
    @Test public void startsOnlyOneAndRejectsStaleCompletion() {
        OperationQueue<String> q=new OperationQueue<>(2); String first="first", second="second", third="third";
        assertTrue(q.offer(first)); assertTrue(q.offer(second)); assertFalse(q.offer(third)); assertSame(first,q.begin());
        assertNull(q.complete(second)); assertTrue(q.isActive(first)); assertSame(first,q.complete(first));
        assertTrue(q.offer(second)); assertSame(second,q.begin());
    }
    @Test public void clearDropsPendingAndActiveAfterDisconnect() {
        OperationQueue<String> q=new OperationQueue<>(3); q.offer("a"); q.offer("b"); q.begin(); q.clear();
        assertEquals(0,q.size()); assertNull(q.begin());
    }
}
