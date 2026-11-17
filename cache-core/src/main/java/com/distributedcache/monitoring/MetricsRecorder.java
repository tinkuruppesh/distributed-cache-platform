package com.distributedcache.monitoring;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;

/**
 * Real measurements of the gateway: counters, a sliding latency window (for percentiles),
 * per-second time buckets (for charts), per-node request counts and key access frequency (for hot keys).
 */
public final class MetricsRecorder {
    public enum Op { GET, SET, UPDATE, DELETE, EXPIRE, TTL, EXISTS }

    public record Counters(long total, long success, long failed, long hits, long misses, long fallbacks,
                           long setsWithTtl, long setsNoTtl, long gets, long sets, long deletes, long expires,
                           long ttls, long exists, long updates) {}
    public record LatencyStats(double avgMs, double p50Ms, double p95Ms, double p99Ms, double maxMs, int samples) {}
    public record TimePoint(long time, long requests, long hits, long misses, long failures, double avgLatencyMs,
                            long getRequests, long setRequests, long updateRequests, long deleteRequests,
                            Map<String, Long> requestsByNode) {}
    public record HotKey(String key, long count) {}
    /** Read-key coverage is complete until the recorder encounters a new key beyond its bounded map. */
    public record HotKeyStats(long readAccesses, long trackedReadAccesses, int trackedDistinctKeys,
                              int trackingLimit, boolean trackingLimitReached) {}
    public record OperationEvent(long time, String operation, String result, String node, String key,
                                 double latencyMs) {}

    private static final int LAT_N = 20000, BUCKETS = 3600, KEY_CAP = 5000;

    private final LongAdder total = new LongAdder(), success = new LongAdder(), failed = new LongAdder(),
            hits = new LongAdder(), misses = new LongAdder(), fallbacks = new LongAdder(),
            setsTtl = new LongAdder(), setsNoTtl = new LongAdder(), gets = new LongAdder(), sets = new LongAdder(),
            deletes = new LongAdder(), expires = new LongAdder(), ttls = new LongAdder(), exists = new LongAdder(),
            updates = new LongAdder();
    private final ConcurrentLinkedDeque<OperationEvent> recentOperations = new ConcurrentLinkedDeque<>();
    // record() is synchronized; maintain this count directly instead of the O(n) deque.size() call.
    private int recentOperationCount;
    private final long[] lat = new long[LAT_N];
    private int latPos, latCount;
    private final long[] bSec = new long[BUCKETS], bReq = new long[BUCKETS], bHit = new long[BUCKETS],
            bMiss = new long[BUCKETS], bFail = new long[BUCKETS], bLatUs = new long[BUCKETS],
            bGet = new long[BUCKETS], bSet = new long[BUCKETS], bUpdate = new long[BUCKETS], bDelete = new long[BUCKETS];
    @SuppressWarnings("unchecked")
    private final Map<String, Long>[] bNodeReq = (Map<String, Long>[]) new Map<?, ?>[BUCKETS];
    private final ConcurrentHashMap<String, LongAdder> perNode = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> keyAccess = new ConcurrentHashMap<>();
    private final LongAdder readAccesses = new LongAdder();
    private boolean keyTrackingLimitReached;

    /** hit: TRUE/FALSE for reads, null for non-reads. attempts > 1 means a fallback to another replica happened. */
    public synchronized void record(Op op, boolean ok, Boolean hit, int attempts, long latencyNanos,
                                    String servedBy, String key, boolean hasTtl) {
        total.increment();
        if (ok) success.increment(); else failed.increment();
        switch (op) {
            case GET -> gets.increment();
            case SET -> sets.increment();
            case UPDATE -> updates.increment();
            case DELETE -> deletes.increment();
            case EXPIRE -> expires.increment();
            case TTL -> ttls.increment();
            case EXISTS -> exists.increment();
        }
        if (attempts > 1 && ok) fallbacks.increment();
        if (hit != null) { if (hit) hits.increment(); else misses.increment(); }
        if (op == Op.SET) { if (hasTtl) setsTtl.increment(); else setsNoTtl.increment(); }
        long us = latencyNanos / 1000;
        lat[latPos] = us;
        latPos = (latPos + 1) % LAT_N;
        if (latCount < LAT_N) latCount++;
        long sec = System.currentTimeMillis() / 1000;
        int i = (int) (sec % BUCKETS);
        if (bSec[i] != sec) {
            bSec[i] = sec; bReq[i] = bHit[i] = bMiss[i] = bFail[i] = bLatUs[i] = 0;
            bGet[i] = bSet[i] = bUpdate[i] = bDelete[i] = 0;
            bNodeReq[i] = new TreeMap<>();
        }
        bReq[i]++;
        bLatUs[i] += us;
        switch (op) {
            case GET -> bGet[i]++;
            case SET -> bSet[i]++;
            case UPDATE -> bUpdate[i]++;
            case DELETE -> bDelete[i]++;
            default -> { }
        }
        if (!ok) bFail[i]++;
        if (hit != null) { if (hit) bHit[i]++; else bMiss[i]++; }
        if (servedBy != null) {
            perNode.computeIfAbsent(servedBy, k -> new LongAdder()).increment();
            bNodeReq[i].merge(servedBy, 1L, Long::sum);
        }
        // Hot-key analysis is based on actual keyed GET requests, not SET/UPDATE/DELETE activity.
        if (op == Op.GET && key != null) {
            readAccesses.increment();
            LongAdder a = keyAccess.get(key);
            if (a != null) a.increment();
            else if (keyAccess.size() < KEY_CAP) keyAccess.computeIfAbsent(key, k -> new LongAdder()).increment();
            else keyTrackingLimitReached = true;
        }
        String result = !ok ? "ERROR" : hit == null ? "SUCCESS" : hit ? "HIT" : "MISS";
        recentOperations.addLast(new OperationEvent(System.currentTimeMillis(), op.name(), result, servedBy, key, us / 1000.0));
        if (++recentOperationCount > 500) {
            recentOperations.pollFirst();
            recentOperationCount--;
        }
    }

    public Counters counters() {
        return new Counters(total.sum(), success.sum(), failed.sum(), hits.sum(), misses.sum(), fallbacks.sum(),
                setsTtl.sum(), setsNoTtl.sum(), gets.sum(), sets.sum(), deletes.sum(), expires.sum(), ttls.sum(), exists.sum(), updates.sum());
    }

    public synchronized LatencyStats latency() {
        if (latCount == 0) return new LatencyStats(0, 0, 0, 0, 0, 0);
        long[] copy = Arrays.copyOf(lat, latCount);
        Arrays.sort(copy);
        double avg = Arrays.stream(copy).average().orElse(0) / 1000.0;
        return new LatencyStats(avg, pct(copy, 0.50), pct(copy, 0.95), pct(copy, 0.99), copy[copy.length - 1] / 1000.0, copy.length);
    }

    private static double pct(long[] sorted, double p) {
        int idx = (int) Math.min(sorted.length - 1, Math.ceil(sorted.length * p) - 1);
        return sorted[Math.max(0, idx)] / 1000.0;
    }

    /** Per-second series for the last `seconds` seconds (oldest first). */
    public synchronized List<TimePoint> timeline(int seconds) {
        long now = System.currentTimeMillis() / 1000;
        int n = Math.min(seconds, BUCKETS);
        List<TimePoint> out = new ArrayList<>(n);
        for (long s = now - n + 1; s <= now; s++) {
            int i = (int) (s % BUCKETS);
            if (bSec[i] == s) out.add(new TimePoint(s * 1000, bReq[i], bHit[i], bMiss[i], bFail[i],
                    bReq[i] == 0 ? 0 : bLatUs[i] / 1000.0 / bReq[i], bGet[i], bSet[i], bUpdate[i], bDelete[i],
                    bNodeReq[i] == null ? Map.of() : Map.copyOf(bNodeReq[i])));
            else out.add(new TimePoint(s * 1000, 0, 0, 0, 0, 0, 0, 0, 0, 0, Map.of()));
        }
        return out;
    }

    public synchronized double requestsPerSecond(int windowSeconds) {
        long now = System.currentTimeMillis() / 1000, sum = 0;
        for (long s = now - windowSeconds + 1; s <= now; s++) {
            int i = (int) (s % BUCKETS);
            if (bSec[i] == s) sum += bReq[i];
        }
        return (double) sum / windowSeconds;
    }

    public Map<String, Long> requestsPerNode() {
        Map<String, Long> m = new TreeMap<>();
        perNode.forEach((k, v) -> m.put(k, v.sum()));
        return m;
    }

    public List<HotKey> hotKeys(int limit) {
        List<HotKey> l = new ArrayList<>();
        keyAccess.forEach((k, v) -> l.add(new HotKey(k, v.sum())));
        l.sort((a, b) -> Long.compare(b.count(), a.count()));
        return l.size() <= limit ? l : new ArrayList<>(l.subList(0, limit));
    }

    public long trackedReadAccesses() {
        long t = 0;
        for (LongAdder a : keyAccess.values()) t += a.sum();
        return t;
    }

    /** @deprecated use {@link #trackedReadAccesses()} to make the hot-key measurement scope explicit. */
    @Deprecated
    public long totalKeyAccesses() { return trackedReadAccesses(); }

    public synchronized HotKeyStats hotKeyStats() {
        return new HotKeyStats(readAccesses.sum(), trackedReadAccesses(), keyAccess.size(), KEY_CAP, keyTrackingLimitReached);
    }

    /** Newest first, bounded in-memory operation history from real gateway calls. */
    public List<OperationEvent> recentOperations(int limit) {
        List<OperationEvent> events = new ArrayList<>(recentOperations);
        Collections.reverse(events);
        return events.size() <= limit ? events : new ArrayList<>(events.subList(0, limit));
    }

    /** Latency summary of real gateway operations retained since the requested timestamp. */
    public LatencyStats latencySince(long sinceMs) {
        long[] samples = recentOperations.stream().filter(e -> e.time() >= sinceMs)
                .mapToLong(e -> Math.max(0, Math.round(e.latencyMs() * 1000.0))).sorted().toArray();
        if (samples.length == 0) return new LatencyStats(0, 0, 0, 0, 0, 0);
        double avg = Arrays.stream(samples).average().orElse(0) / 1000.0;
        return new LatencyStats(avg, pct(samples, 0.50), pct(samples, 0.95), pct(samples, 0.99),
                samples[samples.length - 1] / 1000.0, samples.length);
    }
}
