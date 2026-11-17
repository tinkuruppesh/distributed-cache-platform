package com.distributedcache.cache;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;

/**
 * The local, thread-safe, in-memory cache of ONE node.
 * Every mutation happens inside ConcurrentHashMap.compute(), which locks only that key's bin,
 * so writes to different keys never block each other and byte accounting stays exact.
 */
public final class CacheStore {

    private static final class Holder {
        volatile CacheEntry entry;
        volatile long lastAccess;
        final AtomicLong freq = new AtomicLong();
        Holder(CacheEntry e, long now) { this.entry = e; this.lastAccess = now; }
    }

    private record Candidate(String key, Holder holder, long lastAccess, long freq) {}
    public record EvictionEvent(String key, long time) {}

    public static final long DEFAULT_TOMBSTONE_RETENTION_MS = 10 * 60 * 1000L;

    private final ConcurrentHashMap<String, Holder> map = new ConcurrentHashMap<>();
    private final AtomicLong usedBytes = new AtomicLong();
    private final LongAdder hits = new LongAdder(), misses = new LongAdder(), sets = new LongAdder(),
            deletes = new LongAdder(), expired = new LongAdder(), evictions = new LongAdder();
    private final ConcurrentLinkedDeque<EvictionEvent> recentEvictions = new ConcurrentLinkedDeque<>();
    private final Object evictLock = new Object();
    private final LongSupplier clock;
    private final long tombstoneRetentionMs;
    private volatile long maxBytes;
    private volatile EvictionPolicy policy;

    public CacheStore(long maxBytes, EvictionPolicy policy) {
        this(maxBytes, policy, System::currentTimeMillis, DEFAULT_TOMBSTONE_RETENTION_MS);
    }

    public CacheStore(long maxBytes, EvictionPolicy policy, LongSupplier clock, long tombstoneRetentionMs) {
        this.maxBytes = maxBytes;
        this.policy = policy;
        this.clock = clock;
        this.tombstoneRetentionMs = tombstoneRetentionMs;
    }

    // ------------------------------------------------------------------ reads

    /** Returns the live entry, or null (missing, deleted or expired). Updates LRU/LFU statistics. */
    public CacheEntry get(String key) {
        long now = clock.getAsLong();
        Holder h = map.get(key);
        if (h == null) { misses.increment(); return null; }
        CacheEntry e = h.entry;
        if (e.deleted()) { misses.increment(); return null; }
        if (e.isExpired(now)) { removeExpired(key, now); misses.increment(); return null; }
        h.lastAccess = now;
        h.freq.incrementAndGet();
        hits.increment();
        return e;
    }

    /** Like get() but with no statistics side effects (used by EXISTS/TTL/admin tools). */
    public CacheEntry peek(String key) {
        Holder h = map.get(key);
        if (h == null) return null;
        CacheEntry e = h.entry;
        return (e.deleted() || e.isExpired(clock.getAsLong())) ? null : e;
    }

    /** -2 = key does not exist, -1 = no expiry, otherwise remaining milliseconds. */
    public long ttlMillis(String key) {
        CacheEntry e = peek(key);
        if (e == null) return -2;
        return e.remainingTtlMillis(clock.getAsLong());
    }

    /** All non-expired entries INCLUDING tombstones (needed for synchronization). */
    public List<CacheEntry> snapshot() {
        long now = clock.getAsLong();
        List<CacheEntry> out = new ArrayList<>(map.size());
        for (Holder h : map.values()) {
            CacheEntry e = h.entry;
            if (!e.isExpired(now)) out.add(e);
        }
        return out;
    }

    // ----------------------------------------------------------------- writes

    /** Primary write. ttlMs <= 0 means "no expiry". */
    public CacheEntry put(String key, String value, long ttlMs) {
        long now = clock.getAsLong();
        CacheEntry[] out = new CacheEntry[1];
        map.compute(key, (k, h) -> {
            long prevVersion = h == null ? 0 : h.entry.version();
            boolean live = h != null && !h.entry.deleted() && !h.entry.isExpired(now);
            long created = live ? h.entry.createdAt() : now;
            CacheEntry ne = new CacheEntry(k, value, created, ttlMs > 0 ? now + ttlMs : 0,
                    Math.max(prevVersion + 1, now), now, false);
            return swap(h, ne, now, out);
        });
        sets.increment();
        enforceLimit();
        return out[0];
    }

    /** Marks a live key as deleted by writing a tombstone. Returns the tombstone or null if nothing to delete. */
    public CacheEntry delete(String key) {
        long now = clock.getAsLong();
        CacheEntry[] out = new CacheEntry[1];
        map.compute(key, (k, h) -> {
            if (h == null || h.entry.deleted() || h.entry.isExpired(now)) return h;
            CacheEntry cur = h.entry;
            CacheEntry tomb = new CacheEntry(k, null, cur.createdAt(), now + tombstoneRetentionMs,
                    Math.max(cur.version() + 1, now), now, true);
            return swap(h, tomb, now, out);
        });
        if (out[0] != null) deletes.increment();
        return out[0];
    }

    /** Changes the TTL of a live key (ttlMs <= 0 removes the expiry). Returns the new entry or null. */
    public CacheEntry expire(String key, long ttlMs) {
        long now = clock.getAsLong();
        CacheEntry[] out = new CacheEntry[1];
        map.compute(key, (k, h) -> {
            if (h == null || h.entry.deleted() || h.entry.isExpired(now)) return h;
            CacheEntry cur = h.entry;
            CacheEntry ne = new CacheEntry(k, cur.value(), cur.createdAt(), ttlMs > 0 ? now + ttlMs : 0,
                    Math.max(cur.version() + 1, now), now, false);
            return swap(h, ne, now, out);
        });
        return out[0];
    }

    /**
     * Replica write: applied ONLY if the incoming entry is newer than what we hold.
     * This single rule is what prevents stale writes from overwriting newer data.
     */
    public boolean applyReplica(CacheEntry in) {
        long now = clock.getAsLong();
        if (in.isExpired(now)) return false;
        boolean[] applied = {false};
        map.compute(in.key(), (k, h) -> {
            if (h != null && !in.isNewerThan(h.entry)) return h;
            applied[0] = true;
            return swap(h, in, now, null);
        });
        if (applied[0]) enforceLimit();
        return applied[0];
    }

    /** Physically removes a key without a tombstone (used to clean surplus copies after rebalancing). */
    public void dropLocal(String key) {
        map.compute(key, (k, h) -> {
            if (h != null) usedBytes.addAndGet(-h.entry.approxBytes());
            return null;
        });
    }

    public void clear() { map.clear(); usedBytes.set(0); }

    // ------------------------------------------------------------ housekeeping

    /** Scheduled TTL cleanup (the "active" half of expiration; get() is the "lazy" half). */
    public int cleanupExpired() {
        long now = clock.getAsLong();
        int removed = 0;
        for (String key : map.keySet()) {
            if (removeExpired(key, now)) removed++;
        }
        return removed;
    }

    private boolean removeExpired(String key, long now) {
        boolean[] removed = {false};
        map.compute(key, (k, h) -> {
            if (h != null && h.entry.isExpired(now)) {
                usedBytes.addAndGet(-h.entry.approxBytes());
                if (!h.entry.deleted()) expired.increment();
                removed[0] = true;
                return null;
            }
            return h;
        });
        return removed[0];
    }

    private Holder swap(Holder h, CacheEntry ne, long now, CacheEntry[] out) {
        if (h == null) {
            usedBytes.addAndGet(ne.approxBytes());
            h = new Holder(ne, now);
        } else {
            usedBytes.addAndGet(ne.approxBytes() - h.entry.approxBytes());
            h.entry = ne;
            h.lastAccess = now;
        }
        if (out != null) out[0] = ne;
        return h;
    }

    /** Evicts (LRU or LFU) until usage drops to 90% of the limit. O(n log n) per eviction burst - fine for a demo. */
    private void enforceLimit() {
        long max = maxBytes;
        if (max <= 0 || usedBytes.get() <= max) return;
        synchronized (evictLock) {
            if (usedBytes.get() <= max) return;
            long target = (long) (max * 0.9);
            List<Candidate> list = new ArrayList<>(map.size());
            for (Map.Entry<String, Holder> en : map.entrySet()) {
                Holder h = en.getValue();
                list.add(new Candidate(en.getKey(), h, h.lastAccess, h.freq.get()));
            }
            Comparator<Candidate> cmp = policy == EvictionPolicy.LFU
                    ? Comparator.comparingLong(Candidate::freq).thenComparingLong(Candidate::lastAccess)
                    : Comparator.comparingLong(Candidate::lastAccess);
            list.sort(cmp);
            for (Candidate c : list) {
                if (usedBytes.get() <= target) break;
                map.compute(c.key(), (k, h) -> {
                    if (h != c.holder()) return h;
                    usedBytes.addAndGet(-h.entry.approxBytes());
                    evictions.increment();
                    recentEvictions.addLast(new EvictionEvent(k, clock.getAsLong()));
                    while (recentEvictions.size() > 50) recentEvictions.pollFirst();
                    return null;
                });
            }
        }
    }

    // ----------------------------------------------------------------- config / stats

    public void configure(EvictionPolicy policy, long maxBytes) {
        this.policy = policy;
        this.maxBytes = maxBytes;
        enforceLimit();
    }

    public EvictionPolicy policy() { return policy; }
    public long maxBytes() { return maxBytes; }
    public long usedBytes() { return usedBytes.get(); }
    public int size() { return map.size(); }
    public long hits() { return hits.sum(); }
    public long misses() { return misses.sum(); }
    public long sets() { return sets.sum(); }
    public long deletes() { return deletes.sum(); }
    public long expiredCount() { return expired.sum(); }
    public long evictionCount() { return evictions.sum(); }
    public List<String> recentEvictedKeys() { return recentEvictions.stream().map(EvictionEvent::key).toList(); }
    public List<EvictionEvent> recentEvictions() { return new ArrayList<>(recentEvictions); }
    public double memoryPressure() { return maxBytes <= 0 ? 0 : (double) usedBytes.get() / maxBytes; }
}
