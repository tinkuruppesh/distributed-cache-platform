package com.distributedcache.replication;

import com.distributedcache.cache.CacheEntry;
import com.distributedcache.cluster.ClusterCoordinator;
import com.distributedcache.cluster.NodeInfo;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Pushes every primary write to the replica nodes.
 * Default = ASYNCHRONOUS: replicas normally catch up in the background. A bounded executor applies backpressure
 * under sustained saturation rather than dropping replication writes.
 * Consequence (documented limitation): a write acknowledged by a primary that crashes before replicating is lost.
 */
public final class ReplicationManager implements AutoCloseable {
    private static final int MAX_CONCURRENT_RPC = 8;
    private static final int MAX_QUEUED_RPC = 2048;
    /** stale is retained for API compatibility; alreadyCurrent names the same NOT-APPLIED (equal/newer) response count. */
    public record Stats(long sent, long acked, long applied, long stale, long alreadyCurrent, long failed,
                        double avgLagMs, double p95LagMs, double lastLagMs,
                        double avgLatencyMs, double p95LatencyMs, double lastLatencyMs, long latencySampleCount,
                        double avgAckLatencyMs, double p95AckLatencyMs, long ackLatencySampleCount) {}
    /** lagMs is the monotonic elapsed time from replica RPC start until APPLIED/STALE response. */
    public record LagSample(long time, String node, double lagMs, String key, boolean applied) {}
    public record LatencySample(long time, String node, double latencyMs, String result, String key) {}
    public record Failure(long time, String key, String node, String error, double latencyMs) {}

    private final ClusterCoordinator c;
    /**
     * Keep local TCP fan-out bounded. An unbounded virtual-thread executor used to open thousands of
     * simultaneous replica sockets under bursts; Windows then rejected SO_TIMEOUT setup and left real
     * replica copies missing. The bounded queue applies caller-runs backpressure instead of dropping writes.
     */
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            MAX_CONCURRENT_RPC, MAX_CONCURRENT_RPC, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_RPC),
            Thread.ofVirtual().name("cache-replication-", 0).factory(),
            new ThreadPoolExecutor.CallerRunsPolicy());
    private final LongAdder sent = new LongAdder(), acked = new LongAdder(), applied = new LongAdder(),
            stale = new LongAdder(), failed = new LongAdder();
    private final ConcurrentLinkedDeque<LagSample> lagSamples = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<LatencySample> latencySamples = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Failure> failures = new ConcurrentLinkedDeque<>();
    private final AtomicInteger lagSampleCount = new AtomicInteger();
    private final AtomicInteger latencySampleCount = new AtomicInteger();
    private final AtomicInteger failureCount = new AtomicInteger();
    private volatile double lastLatencyMs;

    public ReplicationManager(ClusterCoordinator c) { this.c = c; }

    public void replicate(CacheEntry entry, List<NodeInfo> targets) {
        List<Future<?>> futures = new ArrayList<>();
        for (NodeInfo t : targets) {
            futures.add(executor.submit(() -> replicateOne(entry, t)));
        }
        if (c.config.syncReplication) {
            for (Future<?> f : futures) { try { f.get(2, TimeUnit.SECONDS); } catch (Exception ignored) {} }
        }
    }

    private void replicateOne(CacheEntry entry, NodeInfo t) {
        long startedNanos = System.nanoTime();
        try {
            // Count attempts at the point the RPC is about to be made, including failed RPCs.
            sent.increment();
            boolean wasApplied = c.client.repl(t.address(), entry, c.config.requestTimeoutMs);
            double latencyMs = elapsedMillis(startedNanos);
            long completedAt = System.currentTimeMillis();
            acked.increment();
            if (wasApplied) applied.increment(); else stale.increment();
            lastLatencyMs = latencyMs;
            String result = wasApplied ? "APPLIED" : "STALE";
            addBounded(lagSamples, lagSampleCount,
                    new LagSample(completedAt, t.id, latencyMs, entry.key(), wasApplied), 600);
            recordLatency(new LatencySample(completedAt, t.id, latencyMs, result, entry.key()));
        } catch (RuntimeException e) {
            double latencyMs = elapsedMillis(startedNanos);
            long completedAt = System.currentTimeMillis();
            failed.increment();
            lastLatencyMs = latencyMs;
            addBounded(failures, failureCount,
                    new Failure(completedAt, entry.key(), t.id, String.valueOf(e.getMessage()), latencyMs), 100);
            recordLatency(new LatencySample(completedAt, t.id, latencyMs, "FAILED", entry.key()));
        }
    }

    private static double elapsedMillis(long startedNanos) {
        return Math.max(0, System.nanoTime() - startedNanos) / 1_000_000.0;
    }

    private void recordLatency(LatencySample sample) {
        addBounded(latencySamples, latencySampleCount, sample, 600);
    }

    /** ConcurrentLinkedDeque.size() traverses the entire queue; never call it on the replication hot path. */
    private static <T> void addBounded(ConcurrentLinkedDeque<T> queue, AtomicInteger count, T item, int limit) {
        queue.addLast(item);
        int observed = count.incrementAndGet();
        while (observed > limit) {
            if (queue.pollFirst() != null) observed = count.decrementAndGet();
            else observed = count.get();
        }
    }

    public Stats stats() {
        double[] values = latencySamples.stream().mapToDouble(LatencySample::latencyMs).sorted().toArray();
        double avg = Arrays.stream(values).average().orElse(0);
        double p95 = percentile(values, 0.95);
        double[] ackValues = latencySamples.stream()
                .filter(sample -> "APPLIED".equals(sample.result()) || "STALE".equals(sample.result()))
                .mapToDouble(LatencySample::latencyMs).sorted().toArray();
        double ackAvg = Arrays.stream(ackValues).average().orElse(0);
        double ackP95 = percentile(ackValues, 0.95);
        return new Stats(sent.sum(), acked.sum(), applied.sum(), stale.sum(), stale.sum(), failed.sum(), avg, p95, lastLatencyMs,
                avg, p95, lastLatencyMs, values.length, ackAvg, ackP95, ackValues.length);
    }

    private static double percentile(double[] sorted, double percentile) {
        if (sorted.length == 0) return 0;
        int nearestRankIndex = (int) Math.ceil(sorted.length * percentile) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, nearestRankIndex))];
    }

    public List<LagSample> lagSamples(int max) {
        List<LagSample> s = new ArrayList<>(lagSamples);
        return s.size() <= max ? s : s.subList(s.size() - max, s.size());
    }

    public List<LatencySample> latencySamples(int max) {
        List<LatencySample> s = new ArrayList<>(latencySamples);
        return s.size() <= max ? s : s.subList(s.size() - max, s.size());
    }

    public List<Failure> recentFailures(int max) {
        List<Failure> s = new ArrayList<>(failures);
        Collections.reverse(s);
        return s.size() <= max ? s : s.subList(0, max);
    }

    @Override public void close() { executor.shutdownNow(); }
}
