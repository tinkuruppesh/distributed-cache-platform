package com.distributedcache.recovery;

import com.distributedcache.cluster.ClusterCoordinator;
import com.distributedcache.cluster.NodeInfo;
import com.distributedcache.cluster.NodeState;
import com.distributedcache.hashing.ConsistentHashRing;
import com.distributedcache.recovery.RebalanceService.ReconcileResult;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Brings a node (new, returning, or resuming from maintenance) into the ring SAFELY:
 *   pass 1: bulk sync computed against a CANDIDATE ring that already includes the node (live routing untouched),
 *   then the node is added to the real ring,
 *   pass 2: delta sync to catch writes that arrived during pass 1.
 * Only the newest version of every key is ever copied, so a returning node cannot overwrite newer data with stale data.
 */
public final class RecoveryManager implements AutoCloseable {
    public record RecoveryRecord(String id, String nodeId, String kind, long startedAt, long syncCompletedAt,
                                 long completedAt, int pushed, int dropped, int failedPushes, boolean success, String message) {}

    private final ClusterCoordinator c;
    private final RebalanceService rebalance;
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final CopyOnWriteArrayList<RecoveryRecord> history = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<RecoveryRecord>> listeners = new CopyOnWriteArrayList<>();

    public RecoveryManager(ClusterCoordinator c, RebalanceService rebalance) { this.c = c; this.rebalance = rebalance; }

    public void addListener(Consumer<RecoveryRecord> l) { listeners.add(l); }
    public List<RecoveryRecord> history() { return new ArrayList<>(history); }

    public Optional<RecoveryRecord> latestFor(String nodeId, long since) {
        RecoveryRecord found = null;
        for (RecoveryRecord r : history) if (r.nodeId().equals(nodeId) && r.startedAt() >= since) found = r;
        return Optional.ofNullable(found);
    }

    /** FAILED -> RECOVERING (atomic) then synchronize asynchronously. Called when a failed node answers a heartbeat. */
    public void beginRecovery(NodeInfo n) {
        if (!n.transition(NodeState.FAILED, NodeState.RECOVERING)) return;
        n.lastHeartbeatAt = System.currentTimeMillis();
        c.events.record("NODE_RECOVERY_DETECTED", n.id, "INFO", n.id + " is answering heartbeats again - starting recovery.");
        exec.submit(() -> integrate(n, "RECOVERY"));
    }

    /** Re-run reconciliation after a SUSPECTED node was cleared, to heal writes that fell back to replicas. */
    public void resyncAsync(String reason) {
        exec.submit(() -> {
            c.topologyLock.lock();
            try {
                ReconcileResult r = rebalance.reconcileLiveRing();
                c.events.record("RESYNC_COMPLETED", null, "INFO", reason + ": pushed " + r.pushed() + ", dropped " + r.dropped() + ".");
            } catch (RuntimeException e) {
                c.events.record("RESYNC_ERROR", null, "WARNING", e.getMessage());
            } finally { c.topologyLock.unlock(); }
        });
    }

    /** Runs synchronously; node state must already be JOINING or RECOVERING. */
    public RecoveryRecord integrate(NodeInfo n, String kind) {
        long start = System.currentTimeMillis();
        int pushed = 0, dropped = 0, failedPushes = 0;
        c.events.record("NODE_" + kind + "_STARTED", n.id, "INFO", "Synchronizing " + n.id + " (" + kind.toLowerCase() + ") ...");
        c.topologyLock.lock();
        boolean addedToRing = false;
        try {
            List<NodeInfo> participants = new ArrayList<>(c.ringMembers());
            participants.add(n);
            ConsistentHashRing candidate = c.ring.copy();
            candidate.addNode(n.id);
            ReconcileResult bulk = rebalance.reconcile(candidate, participants, null, null);
            pushed += bulk.pushed(); dropped += bulk.dropped(); failedPushes += bulk.failedPushes();
            requireCompleteSync(n.id, "bulk", bulk);
            if (!reachable(n)) throw new IllegalStateException(n.id + " became unreachable during sync");
            long syncDone = System.currentTimeMillis();
            c.ring.addNode(n.id);
            addedToRing = true;
            ReconcileResult delta = rebalance.reconcile(c.ring, c.ringMembers(), null, null);
            pushed += delta.pushed(); dropped += delta.dropped(); failedPushes += delta.failedPushes();
            requireCompleteSync(n.id, "delta", delta);
            ConsistencyVerifier.ConsistencyReport verification = new ConsistencyVerifier(c).verify();
            if (!verification.consistent()) {
                throw new IllegalStateException(n.id + " consistency verification failed before activation: "
                        + verification.missingCopies() + " missing copies, " + verification.staleCopies()
                        + " stale copies, " + verification.unreachableNodes() + " unreachable nodes");
            }
            n.missedBeats = 0;
            n.lastHeartbeatAt = System.currentTimeMillis();
            n.setState(NodeState.ACTIVE);
            long done = System.currentTimeMillis();
            RecoveryRecord rec = new RecoveryRecord(UUID.randomUUID().toString().substring(0, 8), n.id, kind, start, syncDone, done,
                    pushed, dropped, failedPushes,
                    true, "OK");
            c.events.record("NODE_ACTIVE", n.id, "INFO", n.id + " is ACTIVE again: " + rec.pushed() + " entries synchronized in "
                    + (done - start) + "ms.");
            publish(rec);
            return rec;
        } catch (RuntimeException e) {
            if (addedToRing) c.ring.removeNode(n.id);
            n.setState(NodeState.FAILED);
            RecoveryRecord rec = new RecoveryRecord(UUID.randomUUID().toString().substring(0, 8), n.id, kind, start,
                    System.currentTimeMillis(), System.currentTimeMillis(), pushed, dropped, failedPushes, false, String.valueOf(e.getMessage()));
            c.events.record("NODE_RECOVERY_FAILED", n.id, "CRITICAL", "Recovery of " + n.id + " failed: " + e.getMessage());
            publish(rec);
            return rec;
        } finally { c.topologyLock.unlock(); }
    }

    private static void requireCompleteSync(String nodeId, String phase, ReconcileResult result) {
        if (result.failedPushes() > 0 || result.unreachableNodes() > 0) {
            throw new IllegalStateException(nodeId + " " + phase + " sync incomplete: "
                    + result.failedPushes() + " failed pushes, " + result.unreachableNodes() + " unreachable nodes");
        }
    }

    private boolean reachable(NodeInfo n) {
        try { c.client.ping(n.address(), c.config.pingTimeoutMs); return true; } catch (RuntimeException e) { return false; }
    }

    private void publish(RecoveryRecord rec) {
        history.add(rec);
        for (Consumer<RecoveryRecord> l : listeners) { try { l.accept(rec); } catch (RuntimeException ignored) {} }
    }

    @Override public void close() { exec.shutdownNow(); }
}
