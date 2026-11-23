package com.realmvc.recorder;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Bounded queue of PCM chunks. {@link #put} waits while the queue is full so a
 * fast reader cannot drop frames; {@link #close} unblocks both sides at stop.
 */
final class PcmQueue {
    private final ArrayBlockingQueue<short[]> q;
    private volatile boolean closed;

    PcmQueue(int capacity) {
        q = new ArrayBlockingQueue<>(capacity);
    }

    void put(short[] chunk) throws InterruptedException {
        while (!closed) {
            if (q.offer(chunk, 200, TimeUnit.MILLISECONDS)) return;
        }
    }

    short[] poll(long timeoutMs) throws InterruptedException {
        return q.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    void close() { closed = true; }

    boolean isClosed() { return closed; }

    boolean isEmpty() { return q.isEmpty(); }
}
