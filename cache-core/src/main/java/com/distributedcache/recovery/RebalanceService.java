package com.distributedcache.recovery;

import com.distributedcache.cache.CacheEntry;
import com.distributedcache.cluster.ClusterCoordinator;
import com.distributedcache.cluster.NodeInfo;
import com.distributedcache.exception.CacheException;
import com.distributedcache.hashing.ConsistentHashRing;

import java.util.*;
import java.util.function.Function;

/**
 * Anti-entropy / reconciliation. For every key it:
 *   1. finds the NEWEST copy among all reachable holders (version order - stale data never wins),
 *   2. pushes that copy to every node that should own the key under the TARGET ring and lacks it or is behind,
 *   3. drops surplus copies from nodes that no longer own the key (only if all owners now hold the newest copy).
 * The same routine implements node join, failure re-replication, recovery, maintenance and settings changes.
 * Cost is O(total keys) - fine for a demo-scale cache; production systems use Merkle trees / range streaming.
 */
public final class RebalanceService {
    public record ReconcileResult(int keysScanned, int pushed, int dropped, int failedPushes,
                                  int unreachableNodes, int promotedKeys, long durationMs) {}

    private final ClusterCoordinator c;

    public RebalanceService(ClusterCoordinator c) { this.c = c; }

    public ReconcileResult reconcile(ConsistentHashRing target, Collection<NodeInfo> participants,
                                     Function<String, String> previousPrimary, String failedNode) {
        long start = System.currentTimeMillis();
        int copies = c.copies();
        Map<String, NodeInfo> byId = new LinkedHashMap<>();
        int unreachable = 0;
        Map<String, Map<String, CacheEntry>> holders = new HashMap<>();

        for (NodeInfo n : participants) {
            if (byId.containsKey(n.id)) continue;
            try {
                c.client.ping(n.address(), c.config.pingTimeoutMs);                  // cheap reachability filter
                for (CacheEntry e : c.client.scan(n.address(), c.config.scanTimeoutMs))
                    holders.computeIfAbsent(e.key(), k -> new HashMap<>()).put(n.id, e);
                byId.put(n.id, n);
            } catch (CacheException ex) {
                unreachable++;
            }
        }

        int pushed = 0, dropped = 0, failed = 0, promoted = 0;
        for (Map.Entry<String, Map<String, CacheEntry>> kv : holders.entrySet()) {
            String key = kv.getKey();
            Map<String, CacheEntry> h = kv.getValue();
            CacheEntry best = null;
            for (CacheEntry e : h.values()) if (best == null || e.isNewerThan(best)) best = e;
            if (best == null) continue;

            if (previousPrimary != null && failedNode != null && failedNode.equals(previousPrimary.apply(key))) promoted++;

            List<String> owners = target.preferenceList(key, copies);
            boolean allOwnersHaveBest = true;
            for (String ownerId : owners) {
                NodeInfo owner = byId.get(ownerId);
                if (owner == null) { allOwnersHaveBest = false; continue; }
                CacheEntry cur = h.get(ownerId);
                if (cur == null || best.isNewerThan(cur)) {
                    try { c.client.repl(owner.address(), best, c.config.requestTimeoutMs * 2); pushed++; }
                    catch (CacheException ex) { failed++; allOwnersHaveBest = false; }
                }
            }
            if (allOwnersHaveBest) {
                for (String holderId : h.keySet()) {
                    if (!owners.contains(holderId)) {
                        try { c.client.drop(byId.get(holderId).address(), key, c.config.requestTimeoutMs); dropped++; }
                        catch (CacheException ignored) {}
                    }
                }
            }
        }
        return new ReconcileResult(holders.size(), pushed, dropped, failed, unreachable, promoted,
                System.currentTimeMillis() - start);
    }

    /** Convenience: reconcile against the live ring using every ring member. */
    public ReconcileResult reconcileLiveRing() {
        return reconcile(c.ring, c.ringMembers(), null, null);
    }
}
