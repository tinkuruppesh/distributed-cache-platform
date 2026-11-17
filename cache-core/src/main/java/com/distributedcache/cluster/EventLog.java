package com.distributedcache.cluster;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Bounded in-memory journal of cluster events (also pushed to listeners, e.g. the simulation timeline). */
public final class EventLog {
    private static final int MAX = 1000;
    private final ConcurrentLinkedDeque<ClusterEvent> events = new ConcurrentLinkedDeque<>();
    private final AtomicLong seq = new AtomicLong();
    private final CopyOnWriteArrayList<Consumer<ClusterEvent>> listeners = new CopyOnWriteArrayList<>();

    public ClusterEvent record(String type, String nodeId, String severity, String message) {
        ClusterEvent e = new ClusterEvent(seq.incrementAndGet(), System.currentTimeMillis(), type, nodeId, severity, message);
        events.addFirst(e);
        while (events.size() > MAX) events.pollLast();
        for (Consumer<ClusterEvent> l : listeners) {
            try { l.accept(e); } catch (RuntimeException ignored) {}
        }
        return e;
    }

    /** Newest first. */
    public List<ClusterEvent> recent(int limit) {
        List<ClusterEvent> out = new ArrayList<>();
        for (ClusterEvent e : events) { out.add(e); if (out.size() >= limit) break; }
        return out;
    }

    public void addListener(Consumer<ClusterEvent> l) { listeners.add(l); }
    public void removeListener(Consumer<ClusterEvent> l) { listeners.remove(l); }
}
