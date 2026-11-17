package com.distributedcache.failover;

import com.distributedcache.cluster.ClusterCoordinator;
import com.distributedcache.cluster.NodeInfo;
import com.distributedcache.cluster.NodeState;
import com.distributedcache.exception.CacheException;
import com.distributedcache.hashing.ConsistentHashRing;
import com.distributedcache.recovery.RebalanceService;
import com.distributedcache.recovery.RebalanceService.ReconcileResult;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Automatic failover with safeguards.
 *
 * Why "replica promotion" = "remove the node from the ring": replicas are the NEXT distinct nodes clockwise, so once the
 * failed node leaves the ring, its first replica automatically becomes primary for exactly the ranges it owned.
 * Then RebalanceService re-replicates so every key has full redundancy again.
 *
 * Safeguards:
 *   - failoverInFlight flag + single-thread executor  -> no concurrent/duplicate failover of the same node
 *   - topologyLock + CAS SUSPECTED->FAILED            -> only one winner can promote
 *   - majority-of-members rule                        -> if the manager can only see a minority it assumes IT is
 *                                                         partitioned and refuses to fail anyone over (no split brain)
 *   - peer probes                                     -> a majority of other healthy nodes must also be unable to reach
 *                                                         the suspect (guards against a bad manager<->node link)
 *   - highest-version reconcile after promotion       -> the freshest surviving copy wins, stale replicas are healed
 * Trade-off (documented): the manager is a single coordinator; with async replication writes acknowledged by the dead
 * primary but not yet replicated are lost.
 */
public final class FailoverManager implements AutoCloseable {
    private final ClusterCoordinator c;
    private final RebalanceService rebalance;
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "failover-manager");
        t.setDaemon(true);
        return t;
    });
    private final CopyOnWriteArrayList<FailoverRecord> history = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<FailoverRecord>> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, Long> lastBlockedNotice = new ConcurrentHashMap<>();

    public FailoverManager(ClusterCoordinator c, RebalanceService rebalance) {
        this.c = c; this.rebalance = rebalance;
    }

    public void addListener(Consumer<FailoverRecord> l) { listeners.add(l); }

    public List<FailoverRecord> history() { return new ArrayList<>(history); }

    public int failoverCount() { return history.size(); }

    public Optional<FailoverRecord> latestFor(String nodeId, long since) {
        FailoverRecord found = null;
        for (FailoverRecord r : history) if (r.nodeId().equals(nodeId) && r.detectedAt() >= since) found = r;
        return Optional.ofNullable(found);
    }

    /** Called by the heartbeat service when a SUSPECTED node has been silent longer than failure.timeout. */
    public void handleFailureCandidate(NodeInfo n) {
        if (!n.failoverInFlight.compareAndSet(false, true)) return;     // a failover attempt is already running
        exec.submit(() -> {
            try { execute(n); }
            catch (RuntimeException e) {
                c.events.record("FAILOVER_ERROR", n.id, "CRITICAL", "Failover aborted: " + e.getMessage());
            } finally { n.failoverInFlight.set(false); }
        });
    }

    private void execute(NodeInfo n) {
        if (n.state() != NodeState.SUSPECTED) return;
        long now = System.currentTimeMillis();

        // --- safeguard 1: do we (the manager) still see a majority of the cluster? -------------------------------
        List<NodeInfo> members = c.quorumMembers();
        long window = c.config.suspectTimeoutMs + c.config.heartbeatIntervalMs;
        List<NodeInfo> reachable = new ArrayList<>();
        for (NodeInfo m : members) if (m != n && now - m.lastHeartbeatAt <= window) reachable.add(m);
        if (reachable.size() * 2 <= members.size()) {
            blocked(n, "Failover blocked: manager reaches only " + reachable.size() + " of " + members.size()
                    + " members (no quorum) - assuming a network partition on the manager side.");
            return;
        }

        // --- safeguard 2: do the other healthy nodes agree that the suspect is unreachable? -----------------------
        List<NodeInfo> probers = new ArrayList<>();
        for (NodeInfo m : reachable) if (m.state() == NodeState.ACTIVE && probers.size() < 3) probers.add(m);
        int responded = 0, unreachableVotes = 0;
        for (NodeInfo p : probers) {
            try {
                boolean canReach = c.client.probe(p.address(), n.host, n.port, c.config.pingTimeoutMs * 2 + 400); // must exceed the node-side probe window (250ms)
                responded++;
                if (!canReach) unreachableVotes++;
            } catch (CacheException ignored) { /* prober itself unreachable: abstain */ }
        }
        if (!probers.isEmpty() && (responded == 0 || unreachableVotes * 2 <= responded)) {
            blocked(n, "Failure not confirmed: only " + unreachableVotes + "/" + responded
                    + " peers also fail to reach " + n.id + " (likely a manager-side link problem).");
            return;
        }

        // --- the actual failover, serialized with every other topology change -----------------------------------
        c.topologyLock.lock();
        try {
            if (!n.transition(NodeState.SUSPECTED, NodeState.FAILED)) return;   // somebody else already handled it
            long detectedAt = System.currentTimeMillis();
            c.events.record("NODE_FAILED", n.id, "CRITICAL",
                    n.id + " declared FAILED after " + (detectedAt - n.lastHeartbeatAt) + "ms without heartbeat ("
                            + responded + " peer(s) confirmed, " + reachable.size() + "/" + members.size() + " members reachable).");
            long failoverStart = System.currentTimeMillis();
            c.events.record("FAILOVER_STARTED", n.id, "WARNING", "Promoting replicas of " + n.id + " ...");

            ConsistentHashRing before = c.ring.copy();
            c.ring.removeNode(n.id);                              // routing is updated HERE: replicas are now primaries
            long routingUpdatedAt = System.currentTimeMillis();
            c.events.record("REPLICA_PROMOTED", n.id, "INFO",
                    "Hash ring updated: replicas of " + n.id + " are now primary. Requests flow to healthy nodes.");
            c.events.record("FAILOVER_COMPLETED", n.id, "INFO",
                    "Routing updated " + (routingUpdatedAt - failoverStart) + "ms after failover start; re-replicating in background.");

            ReconcileResult rr = new ReconcileResult(0, 0, 0, 0, 0, 0, 0);
            try { rr = rebalance.reconcile(c.ring, c.ringMembers(), before::getPrimaryNode, n.id); }
            catch (RuntimeException e) { c.events.record("REREPLICATION_ERROR", n.id, "WARNING", e.getMessage()); }
            c.events.record("REREPLICATION_COMPLETED", n.id, "INFO", "Re-replicated " + rr.pushed() + " entries ("
                    + rr.failedPushes() + " failed) in " + rr.durationMs() + "ms; " + rr.promotedKeys() + " keys changed primary.");

            FailoverRecord rec = new FailoverRecord(UUID.randomUUID().toString().substring(0, 8), n.id, n.lastHeartbeatAt,
                    detectedAt, failoverStart, routingUpdatedAt, rr.promotedKeys(), rr.pushed(), rr.failedPushes(),
                    rr.durationMs(), reachable.size(), members.size(), "HEARTBEAT_TIMEOUT");
            history.add(rec);
            for (Consumer<FailoverRecord> l : listeners) { try { l.accept(rec); } catch (RuntimeException ignored) {} }
        } finally { c.topologyLock.unlock(); }
    }

    private void blocked(NodeInfo n, String message) {
        long now = System.currentTimeMillis();
        Long last = lastBlockedNotice.get(n.id);
        if (last == null || now - last > 10_000) {
            lastBlockedNotice.put(n.id, now);
            c.events.record("FAILOVER_BLOCKED", n.id, "WARNING", message);
        }
    }

    @Override public void close() { exec.shutdownNow(); }
}
