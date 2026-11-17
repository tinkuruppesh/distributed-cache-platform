package com.distributedcache.routing;

import com.distributedcache.cache.CacheEntry;
import com.distributedcache.cluster.ClusterCoordinator;
import com.distributedcache.cluster.NodeInfo;
import com.distributedcache.cluster.NodeState;
import com.distributedcache.exception.CacheException;
import com.distributedcache.exception.ClusterUnavailableException;
import com.distributedcache.exception.NodeUnavailableException;
import com.distributedcache.monitoring.MetricsRecorder;
import com.distributedcache.monitoring.MetricsRecorder.Op;
import com.distributedcache.networking.NodeAddress;
import com.distributedcache.replication.ReplicationManager;
import com.distributedcache.util.KeyValidator;

import java.util.*;

/**
 * The cache gateway: the single entry point clients use. Routes each key to its owners using the hash ring.
 * Request continuity: if the primary does not answer, the next replica in the key's preference list is tried
 * (suspected nodes are tried last). Every outcome - success, miss, fallback, failure - is measured.
 */
public final class CacheRouter {
    public record WriteResult(CacheEntry entry, String writtenTo, List<String> replicas, int fallbackAttempts) {}
    public record ReadResult(CacheEntry entry, String servedBy, int attempts) {}
    public record Placement(String key, double ringPosition, String primary, List<String> replicas, Map<String, String> states) {}

    private record Attempt<T>(T value, NodeInfo node, List<NodeInfo> candidates, int attempts) {}
    private interface NodeOp<T> { T run(NodeAddress a); }

    private final ClusterCoordinator c;
    private final ReplicationManager replication;
    private final MetricsRecorder metrics;

    public CacheRouter(ClusterCoordinator c, ReplicationManager replication, MetricsRecorder metrics) {
        this.c = c; this.replication = replication; this.metrics = metrics;
    }

    // ------------------------------------------------------------------ routing

    /** Preference-list nodes that may serve the key: healthy ones in ring order, suspected ones last. */
    List<NodeInfo> candidates(String key) {
        List<NodeInfo> healthy = new ArrayList<>(), suspected = new ArrayList<>();
        for (String id : c.ring.preferenceList(key, c.copies())) {
            NodeInfo n = c.node(id);
            if (n == null) continue;
            NodeState s = n.state();
            if (s == NodeState.ACTIVE || s == NodeState.RECOVERING) healthy.add(n);
            else if (s == NodeState.SUSPECTED) suspected.add(n);
        }
        healthy.addAll(suspected);
        return healthy;
    }

    private <T> Attempt<T> execute(String key, NodeOp<T> op) {
        List<NodeInfo> cands = candidates(key);
        if (cands.isEmpty()) throw new ClusterUnavailableException("No eligible node owns key '" + key + "'");
        NodeUnavailableException last = null;
        int attempts = 0;
        for (NodeInfo n : cands) {
            attempts++;
            try { return new Attempt<>(op.run(n.address()), n, cands, attempts); }
            catch (NodeUnavailableException e) { last = e; }
        }
        throw new ClusterUnavailableException("All owners of '" + key + "' failed" + (last == null ? "" : ": " + last.getMessage()));
    }

    private int timeout() { return c.config.requestTimeoutMs; }

    private static List<String> ids(List<NodeInfo> l) { return l.stream().map(n -> n.id).toList(); }

    // ------------------------------------------------------------------ operations

    public WriteResult set(String key, String value, Long ttlMs) {
        return write(key, value, ttlMs, Op.SET);
    }

    /** Update uses the same engine write, consistent-hash routing and replication path as SET. */
    public WriteResult update(String key, String value, Long ttlMs) {
        return write(key, value, ttlMs, Op.UPDATE);
    }

    private WriteResult write(String key, String value, Long ttlMs, Op operation) {
        KeyValidator.validateKey(key);
        KeyValidator.validateValue(value, c.config.maxValueChars);
        long ttl = ttlMs != null ? ttlMs : c.config.defaultTtlSeconds * 1000;
        KeyValidator.validateTtlMs(ttl);
        long t0 = System.nanoTime();
        try {
            Attempt<CacheEntry> a = execute(key, addr -> c.client.put(addr, key, value, ttl, timeout()));
            List<NodeInfo> replicas = a.candidates().stream().filter(n -> n != a.node()).toList();
            replication.replicate(a.value(), replicas);
            metrics.record(operation, true, null, a.attempts(), System.nanoTime() - t0, a.node().id, key, ttl > 0);
            return new WriteResult(a.value(), a.node().id, ids(replicas), a.attempts() - 1);
        } catch (CacheException e) {
            metrics.record(operation, false, null, 1, System.nanoTime() - t0, null, key, ttl > 0);
            throw e;
        }
    }

    /** entry == null means cache miss (key absent, deleted or expired). */
    public ReadResult get(String key) { return read(key, Op.GET); }

    public boolean exists(String key) { return read(key, Op.EXISTS).entry() != null; }

    private ReadResult read(String key, Op op) {
        KeyValidator.validateKey(key);
        long t0 = System.nanoTime();
        try {
            Attempt<Optional<CacheEntry>> a = execute(key, addr -> Optional.ofNullable(c.client.get(addr, key, timeout())));
            CacheEntry e = a.value().orElse(null);
            metrics.record(op, true, e != null, a.attempts(), System.nanoTime() - t0, a.node().id, key, false);
            return new ReadResult(e, a.node().id, a.attempts());
        } catch (CacheException e) {
            metrics.record(op, false, null, 1, System.nanoTime() - t0, null, key, false);
            throw e;
        }
    }

    /** Returns true if a live key was deleted. The tombstone is replicated like any write. */
    public boolean delete(String key) {
        KeyValidator.validateKey(key);
        long t0 = System.nanoTime();
        try {
            Attempt<Optional<CacheEntry>> a = execute(key, addr -> Optional.ofNullable(c.client.delete(addr, key, timeout())));
            a.value().ifPresent(tomb -> replication.replicate(tomb, a.candidates().stream().filter(n -> n != a.node()).toList()));
            metrics.record(Op.DELETE, true, null, a.attempts(), System.nanoTime() - t0, a.node().id, key, false);
            return a.value().isPresent();
        } catch (CacheException e) {
            metrics.record(Op.DELETE, false, null, 1, System.nanoTime() - t0, null, key, false);
            throw e;
        }
    }

    /** Remaining TTL in ms: -2 = no such key, -1 = no expiry. */
    public long ttl(String key) {
        KeyValidator.validateKey(key);
        long t0 = System.nanoTime();
        try {
            Attempt<Long> a = execute(key, addr -> c.client.ttl(addr, key, timeout()));
            metrics.record(Op.TTL, true, null, a.attempts(), System.nanoTime() - t0, a.node().id, null, false);
            return a.value();
        } catch (CacheException e) {
            metrics.record(Op.TTL, false, null, 1, System.nanoTime() - t0, null, null, false);
            throw e;
        }
    }

    /** Sets a new TTL (ttlMs = 0 removes expiry). Returns the updated entry or null if the key does not exist. */
    public CacheEntry expire(String key, long ttlMs) {
        KeyValidator.validateKey(key);
        KeyValidator.validateTtlMs(ttlMs);
        long t0 = System.nanoTime();
        try {
            Attempt<Optional<CacheEntry>> a = execute(key, addr -> Optional.ofNullable(c.client.expire(addr, key, ttlMs, timeout())));
            a.value().ifPresent(e -> replication.replicate(e, a.candidates().stream().filter(n -> n != a.node()).toList()));
            metrics.record(Op.EXPIRE, true, null, a.attempts(), System.nanoTime() - t0, a.node().id, key, ttlMs > 0);
            return a.value().orElse(null);
        } catch (CacheException e) {
            metrics.record(Op.EXPIRE, false, null, 1, System.nanoTime() - t0, null, key, ttlMs > 0);
            throw e;
        }
    }

    // ------------------------------------------------------------------ introspection (Cache Explorer / Hash Ring)

    public Placement locate(String key) {
        List<String> pref = c.ring.preferenceList(key, c.copies());
        Map<String, String> states = new LinkedHashMap<>();
        for (String id : pref) { NodeInfo n = c.node(id); states.put(id, n == null ? "UNKNOWN" : n.state().name()); }
        return new Placement(key, c.ring.positionOf(key), pref.isEmpty() ? null : pref.get(0),
                pref.size() <= 1 ? List.of() : pref.subList(1, pref.size()), states);
    }

    /** Admin search: scans reachable ring members and returns the newest live copy of each key matching the prefix. */
    public List<CacheEntry> searchKeys(String prefix, int limit) {
        return searchKeys(prefix, 0, limit);
    }

    /** Sorted live entries after an offset. Scanning remains over the current active ring members. */
    public List<CacheEntry> searchKeys(String prefix, int offset, int limit) {
        if (offset < 0 || limit < 0) throw new IllegalArgumentException("offset and limit must be non-negative");
        Map<String, CacheEntry> best = new HashMap<>();
        for (NodeInfo n : c.ringMembers()) {
            if (n.state() != NodeState.ACTIVE) continue;
            try {
                for (CacheEntry e : c.client.scan(n.address(), c.config.scanTimeoutMs)) {
                    if (e.deleted()) continue;
                    if (prefix != null && !prefix.isEmpty() && !e.key().startsWith(prefix)) continue;
                    best.merge(e.key(), e, (x, y) -> y.isNewerThan(x) ? y : x);
                }
            } catch (CacheException ignored) {}
        }
        List<CacheEntry> out = new ArrayList<>(best.values());
        out.sort(Comparator.comparing(CacheEntry::key));
        if (offset >= out.size() || limit == 0) return List.of();
        int end = (int) Math.min(out.size(), (long) offset + limit);
        return new ArrayList<>(out.subList(offset, end));
    }
}
