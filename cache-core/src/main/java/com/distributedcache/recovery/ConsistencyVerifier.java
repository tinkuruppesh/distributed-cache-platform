package com.distributedcache.recovery;

import com.distributedcache.cache.CacheEntry;
import com.distributedcache.cluster.ClusterCoordinator;
import com.distributedcache.cluster.NodeInfo;
import com.distributedcache.exception.CacheException;

import java.util.*;

/** Measures (never assumes) whether all owners of every key hold the same newest version. */
public final class ConsistencyVerifier {
    public record ConsistencyReport(int keysChecked, int consistentKeys, int missingCopies, int staleCopies,
                                    boolean consistent, int unreachableNodes, long durationMs) {}

    private final ClusterCoordinator c;

    public ConsistencyVerifier(ClusterCoordinator c) { this.c = c; }

    public ConsistencyReport verify() {
        long start = System.currentTimeMillis();
        Map<String, Map<String, CacheEntry>> holders = new HashMap<>();
        int unreachable = 0;
        for (NodeInfo n : c.ringMembers()) {
            try {
                for (CacheEntry e : c.client.scan(n.address(), c.config.scanTimeoutMs))
                    holders.computeIfAbsent(e.key(), k -> new HashMap<>()).put(n.id, e);
            } catch (CacheException ex) { unreachable++; }
        }
        int consistent = 0, missing = 0, stale = 0;
        for (Map.Entry<String, Map<String, CacheEntry>> kv : holders.entrySet()) {
            CacheEntry best = null;
            for (CacheEntry e : kv.getValue().values()) if (best == null || e.isNewerThan(best)) best = e;
            boolean ok = true;
            for (String owner : c.ring.preferenceList(kv.getKey(), c.copies())) {
                CacheEntry e = kv.getValue().get(owner);
                if (e == null) { missing++; ok = false; }
                else if (best != null && best.isNewerThan(e)) { stale++; ok = false; }
            }
            if (ok) consistent++;
        }
        int checked = holders.size();
        return new ConsistencyReport(checked, consistent, missing, stale, missing == 0 && stale == 0 && unreachable == 0,
                unreachable, System.currentTimeMillis() - start);
    }
}
