package com.distributedcache.cluster;

import com.distributedcache.exception.*;
import com.distributedcache.hashing.ConsistentHashRing;
import com.distributedcache.networking.NodeAddress;
import com.distributedcache.recovery.RebalanceService;
import com.distributedcache.recovery.RecoveryManager;

import java.util.*;
import java.util.regex.Pattern;

/** Controlled membership changes: join, graceful removal, maintenance (as opposed to unexpected failure). */
public final class MembershipManager {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_\\-]{1,40}");
    private final ClusterCoordinator c;
    private final RebalanceService rebalance;
    private final RecoveryManager recovery;

    public MembershipManager(ClusterCoordinator c, RebalanceService rebalance, RecoveryManager recovery) {
        this.c = c; this.rebalance = rebalance; this.recovery = recovery;
    }

    /** Join: validate identity -> register -> bulk sync -> add to ring -> delta sync -> ACTIVE. */
    public NodeInfo join(NodeAddress a) {
        if (!ID.matcher(a.id()).matches()) throw new InvalidRequestException("Invalid node id (use letters, digits, - _).");
        if (a.port() < 1 || a.port() > 65535) throw new InvalidRequestException("Invalid port.");
        if (c.node(a.id()) != null) throw new ConflictException("Node id already registered: " + a.id());
        try {
            Map<String, String> pong = c.client.ping(a, c.config.pingTimeoutMs * 4);
            if (!a.id().equals(pong.get("node")))
                throw new InvalidRequestException("Identity mismatch: endpoint reports node id '" + pong.get("node") + "'.");
        } catch (NodeUnavailableException e) {
            throw new ConflictException("Node is not reachable or rejected the cluster secret: " + e.getMessage());
        }
        NodeInfo n = c.register(a);
        n.stats = Map.of();
        if (c.ring.size() == 0) {                       // very first node: nothing to synchronize
            c.topologyLock.lock();
            try { c.ring.addNode(n.id); n.setState(NodeState.ACTIVE); } finally { c.topologyLock.unlock(); }
            c.events.record("NODE_JOINED", n.id, "INFO", n.id + " joined (first node).");
        } else {
            recovery.integrate(n, "JOIN");
            if (n.state() != NodeState.ACTIVE) { c.unregister(n.id); throw new ConflictException("Join failed - see event log."); }
            c.events.record("NODE_JOINED", n.id, "INFO", n.id + " joined the cluster.");
        }
        return n;
    }

    /** Graceful removal: data is first copied to the new owners, then the node leaves. Also works for FAILED nodes. */
    public void remove(String nodeId) {
        NodeInfo n = c.require(nodeId);
        if (c.ring.containsNode(nodeId)) {
            if (c.ring.size() <= 1) throw new ConflictException("Cannot remove the last node of the cluster.");
            drain(n);
        }
        n.setState(NodeState.REMOVED);
        c.unregister(nodeId);
        c.events.record("NODE_REMOVED", nodeId, "INFO", nodeId + " removed from the cluster.");
    }

    public void maintenance(String nodeId) {
        NodeInfo n = c.require(nodeId);
        if (n.state() != NodeState.ACTIVE) throw new ConflictException("Only ACTIVE nodes can enter maintenance (state: " + n.state() + ").");
        if (c.ring.size() <= 1) throw new ConflictException("Cannot drain the last node.");
        drain(n);
        n.setState(NodeState.MAINTENANCE);
        c.events.record("NODE_MAINTENANCE", nodeId, "INFO", nodeId + " drained and in MAINTENANCE.");
    }

    public void resume(String nodeId) {
        NodeInfo n = c.require(nodeId);
        if (!n.transition(NodeState.MAINTENANCE, NodeState.RECOVERING))
            throw new ConflictException("Node is not in MAINTENANCE (state: " + n.state() + ").");
        recovery.integrate(n, "RESUME");
    }

    /** Copy data to the future owners first, then switch routing, then clean up the leaving node. */
    private void drain(NodeInfo n) {
        c.topologyLock.lock();
        try {
            ConsistentHashRing candidate = c.ring.copy();
            candidate.removeNode(n.id);
            List<NodeInfo> participants = c.ringMembers();           // still includes n as a data source
            rebalance.reconcile(candidate, participants, null, null);
            c.ring.removeNode(n.id);
            rebalance.reconcile(c.ring, participants, null, null);   // pass 2 also drops surplus data from n
        } finally { c.topologyLock.unlock(); }
    }
}
