package com.distributedcache.cluster;

import com.distributedcache.exception.NotFoundException;
import com.distributedcache.hashing.ConsistentHashRing;
import com.distributedcache.networking.NodeAddress;
import com.distributedcache.networking.NodeClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Shared state of the cluster manager: node registry, hash ring, event log, config.
 * topologyLock serializes every operation that changes who owns what (failover, join, removal, recovery),
 * so two topology changes can never interleave.
 */
public final class ClusterCoordinator {
    public final ClusterConfig config;
    public final NodeClient client;
    public final EventLog events = new EventLog();
    public final ConsistentHashRing ring;
    public final ReentrantLock topologyLock = new ReentrantLock();
    private final Map<String, NodeInfo> nodes = new ConcurrentHashMap<>();

    public ClusterCoordinator(ClusterConfig config) {
        this.config = config;
        this.client = new NodeClient(config.clusterSecret, 500);
        this.ring = new ConsistentHashRing(config.virtualNodes);
    }

    public NodeInfo register(NodeAddress a) {
        NodeInfo n = new NodeInfo(a);
        nodes.put(a.id(), n);
        return n;
    }

    public void unregister(String id) { nodes.remove(id); }
    public NodeInfo node(String id) { return nodes.get(id); }

    public NodeInfo require(String id) {
        NodeInfo n = nodes.get(id);
        if (n == null) throw new NotFoundException("Unknown node: " + id);
        return n;
    }

    public List<NodeInfo> allNodes() {
        List<NodeInfo> l = new ArrayList<>(nodes.values());
        l.sort(Comparator.comparing(n -> n.id));
        return l;
    }

    /** Nodes currently in the ring (the owners of data). */
    public List<NodeInfo> ringMembers() {
        List<NodeInfo> l = new ArrayList<>();
        for (String id : ring.nodeIds()) { NodeInfo n = nodes.get(id); if (n != null) l.add(n); }
        return l;
    }

    /** Nodes the heartbeat service must watch. */
    public List<NodeInfo> monitorable() {
        List<NodeInfo> l = new ArrayList<>();
        for (NodeInfo n : nodes.values()) {
            NodeState s = n.state();
            if (s != NodeState.MAINTENANCE && s != NodeState.REMOVED) l.add(n);
        }
        return l;
    }

    /** Members counted for the failover quorum: every node that is supposed to be part of the cluster. */
    public List<NodeInfo> quorumMembers() {
        List<NodeInfo> l = new ArrayList<>();
        for (NodeInfo n : nodes.values()) {
            NodeState s = n.state();
            if (s == NodeState.ACTIVE || s == NodeState.SUSPECTED || s == NodeState.FAILED
                    || s == NodeState.RECOVERING || s == NodeState.JOINING) l.add(n);
        }
        return l;
    }

    /** Number of copies of every key: 1 primary + replicationFactor replicas. */
    public int copies() { return 1 + config.replicationFactor; }
}
