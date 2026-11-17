package com.distributedcache.failure;

import com.distributedcache.cluster.ClusterCoordinator;
import com.distributedcache.cluster.NodeInfo;
import com.distributedcache.cluster.NodeState;
import com.distributedcache.exception.NodeUnavailableException;
import com.distributedcache.failover.FailoverManager;
import com.distributedcache.recovery.RecoveryManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Heartbeat failure detector. Every heartbeat.interval the manager PINGs every node (in parallel).
 * A node is NOT declared dead after one missed beat:
 *   silent >= suspect timeout  -> SUSPECTED   (still in the ring, requests try it last)
 *   silent >= failure timeout  -> failover candidate (FailoverManager still has to confirm it)
 * A SUSPECTED node that answers again goes back to ACTIVE; a FAILED node that answers starts recovery.
 */
public final class HeartbeatService implements AutoCloseable {
    private final ClusterCoordinator c;
    private final FailoverManager failover;
    private final RecoveryManager recovery;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "heartbeat");
        t.setDaemon(true);
        return t;
    });
    private final ExecutorService pings = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean stopped;

    public HeartbeatService(ClusterCoordinator c, FailoverManager failover, RecoveryManager recovery) {
        this.c = c; this.failover = failover; this.recovery = recovery;
    }

    public void start() { scheduler.schedule(this::loop, c.config.heartbeatIntervalMs, TimeUnit.MILLISECONDS); }

    private void loop() {
        try { tick(); }
        catch (RuntimeException ignored) {}
        finally { if (!stopped) scheduler.schedule(this::loop, c.config.heartbeatIntervalMs, TimeUnit.MILLISECONDS); }
    }

    void tick() {
        List<Future<?>> fs = new ArrayList<>();
        for (NodeInfo n : c.monitorable()) fs.add(pings.submit(() -> pingOne(n)));
        for (Future<?> f : fs) { try { f.get(); } catch (Exception ignored) {} }
    }

    private void pingOne(NodeInfo n) {
        long t0 = System.currentTimeMillis();
        try {
            Map<String, String> kv = c.client.ping(n.address(), c.config.pingTimeoutMs);
            long now = System.currentTimeMillis();
            n.lastRttMs = now - t0;
            n.lastHeartbeatAt = now;
            n.missedBeats = 0;
            n.stats = kv;
            NodeState s = n.state();
            if (s == NodeState.SUSPECTED && n.transition(NodeState.SUSPECTED, NodeState.ACTIVE)) {
                c.events.record("NODE_ALIVE", n.id, "INFO", n.id + " answered again - suspicion cleared.");
                recovery.resyncAsync("Post-suspicion resync of " + n.id);
            } else if (s == NodeState.FAILED) {
                recovery.beginRecovery(n);
            }
        } catch (NodeUnavailableException e) {
            n.missedBeats++;
            long silent = System.currentTimeMillis() - n.lastHeartbeatAt;
            if (n.state() == NodeState.ACTIVE && silent >= c.config.suspectTimeoutMs
                    && n.transition(NodeState.ACTIVE, NodeState.SUSPECTED)) {
                c.events.record("NODE_SUSPECTED", n.id, "WARNING",
                        n.id + " missed " + n.missedBeats + " heartbeats (" + silent + "ms silent) - SUSPECTED.");
            }
            if (n.state() == NodeState.SUSPECTED && silent >= c.config.failureTimeoutMs) failover.handleFailureCandidate(n);
        }
    }

    @Override public void close() { stopped = true; scheduler.shutdownNow(); pings.shutdownNow(); }
}
