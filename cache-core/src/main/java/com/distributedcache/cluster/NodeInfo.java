package com.distributedcache.cluster;

import com.distributedcache.networking.NodeAddress;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Manager-side record of one node (state machine + last heartbeat data). */
public final class NodeInfo {
    public final String id, host;
    public final int port;
    public final AtomicReference<NodeState> state = new AtomicReference<>(NodeState.JOINING);
    /** Guards against two concurrent failover attempts for the same node. */
    public final AtomicBoolean failoverInFlight = new AtomicBoolean();
    public volatile long registeredAt = System.currentTimeMillis();
    public volatile long lastHeartbeatAt = System.currentTimeMillis();
    public volatile long stateSince = System.currentTimeMillis();
    public volatile long lastRttMs;
    public volatile int missedBeats;
    public volatile Map<String, String> stats = Map.of();

    public NodeInfo(NodeAddress a) { this.id = a.id(); this.host = a.host(); this.port = a.port(); }

    public NodeAddress address() { return new NodeAddress(id, host, port); }
    public NodeState state() { return state.get(); }

    public void setState(NodeState s) { state.set(s); stateSince = System.currentTimeMillis(); }

    /** Atomic compare-and-set transition; only ONE caller can win, which is what prevents duplicate promotions. */
    public boolean transition(NodeState from, NodeState to) {
        if (state.compareAndSet(from, to)) { stateSince = System.currentTimeMillis(); return true; }
        return false;
    }

    public long stat(String key) {
        try { return Long.parseLong(stats.getOrDefault(key, "0")); } catch (NumberFormatException e) { return 0; }
    }
}
