package com.distributedcache.hashing;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Consistent hash ring with virtual nodes.
 * - Each physical node is placed on the ring at `virtualNodes` pseudo-random points.
 * - A key belongs to the first point clockwise from hash(key); the following DISTINCT physical nodes
 *   clockwise are its replicas. Adding/removing a node therefore only moves the key ranges next to it.
 * Thread-safe (read/write lock): lookups run concurrently, topology changes are exclusive.
 */
public final class ConsistentHashRing {
    private final TreeMap<Long, VirtualNode> ring = new TreeMap<>();
    private final LinkedHashSet<String> nodes = new LinkedHashSet<>();
    private final HashFunction hash;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile int virtualNodes;

    public ConsistentHashRing(int virtualNodes) { this(virtualNodes, new Md5HashFunction()); }

    public ConsistentHashRing(int virtualNodes, HashFunction hash) {
        if (virtualNodes < 1) throw new IllegalArgumentException("virtualNodes must be >= 1");
        this.virtualNodes = virtualNodes;
        this.hash = hash;
    }

    private static double position(long h) {
        long unsigned = h ^ Long.MIN_VALUE;              // signed order -> unsigned order
        return (double) (unsigned >>> 11) / (double) (1L << 53);
    }

    public boolean addNode(String nodeId) {
        lock.writeLock().lock();
        try { return addInternal(nodeId); } finally { lock.writeLock().unlock(); }
    }

    private boolean addInternal(String nodeId) {
        if (!nodes.add(nodeId)) return false;
        for (int i = 0; i < virtualNodes; i++) {
            int salt = 0;
            long h = hash.hash(nodeId + "#" + i);
            while (ring.containsKey(h)) h = hash.hash(nodeId + "#" + i + "~" + (++salt)); // collision guard
            ring.put(h, new VirtualNode(nodeId, i, h, position(h)));
        }
        return true;
    }

    public boolean removeNode(String nodeId) {
        lock.writeLock().lock();
        try {
            if (!nodes.remove(nodeId)) return false;
            ring.values().removeIf(v -> v.nodeId().equals(nodeId));
            return true;
        } finally { lock.writeLock().unlock(); }
    }

    public boolean containsNode(String nodeId) {
        lock.readLock().lock();
        try { return nodes.contains(nodeId); } finally { lock.readLock().unlock(); }
    }

    public String getPrimaryNode(String key) {
        List<String> l = preferenceList(key, 1);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Replica owners only (excludes the primary). */
    public List<String> getReplicaNodes(String key, int replicas) {
        List<String> l = preferenceList(key, replicas + 1);
        return l.size() <= 1 ? List.of() : new ArrayList<>(l.subList(1, l.size()));
    }

    /** Primary first, then replicas, all distinct physical nodes, at most `count` long. */
    public List<String> preferenceList(String key, int count) {
        lock.readLock().lock();
        try {
            if (ring.isEmpty()) return List.of();
            int want = Math.min(count, nodes.size());
            LinkedHashSet<String> out = new LinkedHashSet<>();
            long h = hash.hash(key);
            for (VirtualNode v : ring.tailMap(h, true).values()) {
                out.add(v.nodeId());
                if (out.size() == want) return new ArrayList<>(out);
            }
            for (VirtualNode v : ring.headMap(h, false).values()) {
                out.add(v.nodeId());
                if (out.size() == want) break;
            }
            return new ArrayList<>(out);
        } finally { lock.readLock().unlock(); }
    }

    public Set<String> nodeIds() {
        lock.readLock().lock();
        try { return new LinkedHashSet<>(nodes); } finally { lock.readLock().unlock(); }
    }

    public int size() {
        lock.readLock().lock();
        try { return nodes.size(); } finally { lock.readLock().unlock(); }
    }

    public int virtualNodesPerNode() { return virtualNodes; }

    /** Independent deep copy: used to compute "what would the ring look like if..." before committing a change. */
    public ConsistentHashRing copy() { return copyWithVirtualNodes(virtualNodes); }

    public ConsistentHashRing copyWithVirtualNodes(int vnodes) {
        lock.readLock().lock();
        try {
            ConsistentHashRing c = new ConsistentHashRing(vnodes, hash);
            for (String n : nodes) c.addNode(n);
            return c;
        } finally { lock.readLock().unlock(); }
    }

    /** Rebuilds this ring in place with a new virtual-node count. */
    public void reconfigure(int vnodes) {
        lock.writeLock().lock();
        try {
            List<String> members = new ArrayList<>(nodes);
            ring.clear();
            nodes.clear();
            this.virtualNodes = vnodes;
            for (String n : members) addInternal(n);
        } finally { lock.writeLock().unlock(); }
    }

    /** All virtual nodes in ring order (for the hash-ring visualization). */
    public List<VirtualNode> virtualNodes() {
        lock.readLock().lock();
        try { return new ArrayList<>(ring.values()); } finally { lock.readLock().unlock(); }
    }

    /** Percentage of the hash space each physical node is primary for. */
    public Map<String, Double> ownership() {
        lock.readLock().lock();
        try {
            Map<String, Double> out = new LinkedHashMap<>();
            for (String n : nodes) out.put(n, 0.0);
            if (ring.isEmpty()) return out;
            final double SPACE = 18446744073709551616.0; // 2^64
            long prev = ring.lastKey();
            for (Map.Entry<Long, VirtualNode> e : ring.entrySet()) {
                long diff = e.getKey() - prev;            // wrap-around arithmetic == unsigned distance
                double d = diff >= 0 ? diff : diff + SPACE;
                if (ring.size() == 1) d = SPACE;
                out.merge(e.getValue().nodeId(), d / SPACE * 100.0, Double::sum);
                prev = e.getKey();
            }
            return out;
        } finally { lock.readLock().unlock(); }
    }

    public double positionOf(String key) { return position(hash.hash(key)); }

    /** Keys whose owner set (first `copies` nodes) changes if nodeId is added to / removed from this ring. */
    public List<String> getAffectedKeys(Collection<String> keys, String nodeId, boolean adding, int copies) {
        ConsistentHashRing after = copy();
        if (adding) after.addNode(nodeId); else after.removeNode(nodeId);
        List<String> out = new ArrayList<>();
        for (String k : keys) {
            if (!preferenceList(k, copies).equals(after.preferenceList(k, copies))) out.add(k);
        }
        return out;
    }
}
