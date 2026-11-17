package com.distributedcache.monitoring;

import com.distributedcache.advisor.AdvisorSnapshot;
import com.distributedcache.advisor.AdvisorSnapshot.NodeMetrics;
import com.distributedcache.cache.CacheEntry;
import com.distributedcache.cluster.ClusterCoordinator;
import com.distributedcache.cluster.NodeInfo;
import com.distributedcache.cluster.NodeState;
import com.distributedcache.exception.CacheException;
import com.distributedcache.failover.FailoverManager;
import com.distributedcache.hashing.VirtualNode;
import com.distributedcache.monitoring.MetricsRecorder.LatencyStats;
import com.distributedcache.networking.NodeAddress;
import com.distributedcache.replication.ReplicationManager;
import com.distributedcache.routing.CacheRouter;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ScheduledExecutorService;

/** Builds every dashboard view from REAL data (node heartbeats, gateway measurements, ring state). */
public final class MonitoringService implements AutoCloseable {

    public record NodeView(String id, String host, int port, String state, String role, double primaryOwnershipPct,
                           long uptimeMs, long entries, long usedBytes, long maxBytes, double memoryPct,
                           long nodeRequests, long gatewayRequests, long replicationWrites, long evictions, long expired, long hits, long misses,
                           long lastHeartbeatAt, long lastHeartbeatAgoMs, long rttMs, long stateSince,
                           String evictionPolicy, long incarnation, int missedBeats, List<EvictionView> recentEvictions) {}
    public record EvictionView(String key, long time) {}

    public record Overview(String health, int totalNodes, int activeNodes, int suspectedNodes, int failedNodes,
                           int recoveringNodes, int maintenanceNodes, long totalRequests, long successfulRequests,
                           long failedRequests, double hitRatioPct, double missRatioPct, double requestsPerSecond,
                           LatencyStats latency, int failoverCount, ReplicationManager.Stats replication,
                           double memoryUtilizationPct, long totalEntries, int replicationFactor, int virtualNodesPerNode,
                           long fallbackRequests) {}

    public record KeyPlacement(String key, double position, String primary, List<String> replicas) {}
    public record RingView(int virtualNodesPerNode, int replicationFactor, List<VirtualNode> virtualNodes,
                           Map<String, Double> ownership, List<KeyPlacement> keys) {}
    /** `primaryKeys`/`replicaKeys` are retained as API compatibility aliases for expected sampled ownership. */
    public record NodeReplicaCount(String nodeId, int primaryKeys, int replicaKeys,
                                   int expectedPrimaryCount, int expectedReplicaCount,
                                   int actualPrimaryCount, int actualReplicaCount,
                                   int missingReplicaCount, int staleReplicaCount, boolean scanSucceeded,
                                   int keysDiscovered, String scanStatus, String errorReason) {}
    public record ReplicaScanError(String nodeId, String nodeState, String message) {}
    private record ReplicaNodeScan(List<CacheEntry> entries, ReplicaScanError error) {}
    public record ReplicationView(int replicationFactor, boolean synchronous, ReplicationManager.Stats stats,
                                  List<ReplicationManager.LagSample> lag, List<ReplicationManager.Failure> failures,
                                  List<NodeReplicaCount> perNode, int keysSampled, int keysInspected, int sampleLimit,
                                  boolean truncated, boolean cacheEmpty, int nodesAttempted, int nodesScanned,
                                  int nodesUnreachable, boolean scanComplete, List<ReplicaScanError> scanErrors,
                                  List<ReplicationManager.LatencySample> latencySamples) {}
    public record GossipMember(String id, String status, long heartbeat, long incarnation, long ageMs) {}
    public record GossipView(String nodeId, List<GossipMember> members) {}
    public record GossipOverview(boolean converged, List<GossipView> views) {}
    public record HistoryPoint(long time, Map<String, Double> memoryPct, Map<String, String> states) {}

    private final ClusterCoordinator c;
    private final MetricsRecorder metrics;
    private final ReplicationManager replication;
    private final FailoverManager failover;
    private final CacheRouter router;
    private final ConcurrentLinkedDeque<HistoryPoint> history = new ConcurrentLinkedDeque<>();
    private final ExecutorService scanExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "history-sampler");
        t.setDaemon(true);
        return t;
    });

    public MonitoringService(ClusterCoordinator c, MetricsRecorder metrics, ReplicationManager replication,
                             FailoverManager failover, CacheRouter router) {
        this.c = c; this.metrics = metrics; this.replication = replication; this.failover = failover; this.router = router;
    }

    public void start() {
        sampler.scheduleWithFixedDelay(this::sample, 2, 2, TimeUnit.SECONDS);
    }

    private void sample() {
        try {
            Map<String, Double> mem = new LinkedHashMap<>();
            Map<String, String> st = new LinkedHashMap<>();
            for (NodeInfo n : c.allNodes()) {
                long max = n.stat("max");
                mem.put(n.id, max <= 0 ? 0 : Math.round(1000.0 * n.stat("used") / max) / 10.0);
                st.put(n.id, n.state().name());
            }
            history.addLast(new HistoryPoint(System.currentTimeMillis(), mem, st));
            while (history.size() > 300) history.pollFirst();
        } catch (RuntimeException ignored) {}
    }

    public List<HistoryPoint> history(int max) {
        List<HistoryPoint> l = new ArrayList<>(history);
        return l.size() <= max ? l : l.subList(l.size() - max, l.size());
    }

    // ------------------------------------------------------------------ nodes

    public NodeView nodeView(NodeInfo n) {
        Map<String, Double> own = c.ring.ownership();
        return view(n, own);
    }

    public List<NodeView> nodes() {
        Map<String, Double> own = c.ring.ownership();
        Map<String, Long> gw = metrics.requestsPerNode();
        List<NodeView> out = new ArrayList<>();
        for (NodeInfo n : c.allNodes()) out.add(view(n, own, gw));
        return out;
    }

    private NodeView view(NodeInfo n, Map<String, Double> own) { return view(n, own, metrics.requestsPerNode()); }

    private NodeView view(NodeInfo n, Map<String, Double> own, Map<String, Long> gw) {
        long now = System.currentTimeMillis();
        long used = n.stat("used"), max = n.stat("max");
        boolean inRing = c.ring.containsNode(n.id);
        return new NodeView(n.id, n.host, n.port, n.state().name(), inRing ? "PRIMARY+REPLICA" : "NONE",
                Math.round(own.getOrDefault(n.id, 0.0) * 10) / 10.0, n.stat("uptime"), n.stat("entries"), used, max,
                max <= 0 ? 0 : Math.round(1000.0 * used / max) / 10.0, n.stat("requests"), gw.getOrDefault(n.id, 0L),
                n.stat("repl"), n.stat("evictions"), n.stat("expired"), n.stat("hits"), n.stat("misses"), n.lastHeartbeatAt,
                now - n.lastHeartbeatAt, n.lastRttMs, n.stateSince, n.stats.getOrDefault("policy", "-"),
                n.stat("incarnation"), n.missedBeats, evictionEvents(n.stats.get("evictionEvents")));
    }

    private static List<EvictionView> evictionEvents(String encoded) {
        if (encoded == null || encoded.isBlank()) return List.of();
        List<EvictionView> events = new ArrayList<>();
        for (String item : encoded.split(",")) {
            int separator = item.lastIndexOf(':');
            if (separator < 1) continue;
            try {
                String key = new String(Base64.getUrlDecoder().decode(item.substring(0, separator)), java.nio.charset.StandardCharsets.UTF_8);
                events.add(new EvictionView(key, Long.parseLong(item.substring(separator + 1))));
            } catch (IllegalArgumentException ignored) {}
        }
        return events;
    }

    // ------------------------------------------------------------------ overview

    public Overview overview() {
        int total = 0, active = 0, suspected = 0, failed = 0, recovering = 0, maintenance = 0;
        long used = 0, max = 0, entries = 0;
        for (NodeInfo n : c.allNodes()) {
            total++;
            switch (n.state()) {
                case ACTIVE -> active++;
                case SUSPECTED -> suspected++;
                case FAILED -> failed++;
                case RECOVERING, JOINING -> recovering++;
                case MAINTENANCE -> maintenance++;
                default -> { }
            }
            if (n.state() == NodeState.ACTIVE || n.state() == NodeState.SUSPECTED) {
                used += n.stat("used"); max += n.stat("max"); entries += n.stat("entries");
            }
        }
        MetricsRecorder.Counters k = metrics.counters();
        long gets = k.hits() + k.misses();
        double hit = gets == 0 ? 0 : 100.0 * k.hits() / gets;
        String health = active == 0 ? "CRITICAL" : (failed + suspected + recovering > 0 ? "DEGRADED" : "HEALTHY");
        return new Overview(health, total, active, suspected, failed, recovering, maintenance, k.total(), k.success(),
                k.failed(), Math.round(hit * 10) / 10.0, gets == 0 ? 0 : Math.round((100 - hit) * 10) / 10.0,
                metrics.requestsPerSecond(5), metrics.latency(), failover.failoverCount(), replication.stats(),
                max == 0 ? 0 : Math.round(1000.0 * used / max) / 10.0, entries, c.config.replicationFactor,
                c.ring.virtualNodesPerNode(), k.fallbacks());
    }

    // ------------------------------------------------------------------ ring / replication / gossip

    public RingView hashRing(int sampleKeys) {
        List<KeyPlacement> keys = new ArrayList<>();
        for (CacheEntry e : router.searchKeys("", sampleKeys)) {
            CacheRouter.Placement p = router.locate(e.key());
            keys.add(new KeyPlacement(e.key(), p.ringPosition(), p.primary(), p.replicas()));
        }
        return new RingView(c.ring.virtualNodesPerNode(), c.config.replicationFactor, c.ring.virtualNodes(),
                c.ring.ownership(), keys);
    }

    public ReplicationView replicationView() {
        final int sampleLimit = 5000;
        List<NodeInfo> scanNodes = c.allNodes().stream().filter(n -> n.state() != NodeState.REMOVED).toList();
        Map<String, Map<String, CacheEntry>> copiesByKey = new HashMap<>();
        Set<String> scannedNodeIds = new HashSet<>();
        List<ReplicaScanError> scanErrors = new ArrayList<>();
        Map<String, ReplicaScanError> scanErrorsByNode = new HashMap<>();
        Map<String, Integer> discoveredByNode = new HashMap<>();
        long scanAt = System.currentTimeMillis();

        Map<NodeInfo, Future<ReplicaNodeScan>> scans = new LinkedHashMap<>();
        for (NodeInfo node : scanNodes) scans.put(node, scanExecutor.submit(() -> scanNode(node)));
        for (Map.Entry<NodeInfo, Future<ReplicaNodeScan>> scan : scans.entrySet()) {
            NodeInfo node = scan.getKey();
            try {
                ReplicaNodeScan result = scan.getValue().get(Math.max(1, c.config.scanTimeoutMs) + 1000L, TimeUnit.MILLISECONDS);
                if (result.error() != null) {
                    scanErrors.add(result.error());
                    scanErrorsByNode.put(node.id, result.error());
                    continue;
                }
                scannedNodeIds.add(node.id);
                int discovered = 0;
                for (CacheEntry entry : result.entries()) {
                    if (entry.deleted() || entry.isExpired(scanAt)) continue;
                    discovered++;
                    copiesByKey.computeIfAbsent(entry.key(), ignored -> new HashMap<>()).put(node.id, entry);
                }
                discoveredByNode.put(node.id, discovered);
            } catch (TimeoutException e) {
                scan.getValue().cancel(true);
                ReplicaScanError error = new ReplicaScanError(node.id, node.state().name(),
                        "Scan did not complete within " + c.config.scanTimeoutMs + "ms.");
                scanErrors.add(error);
                scanErrorsByNode.put(node.id, error);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                scans.values().forEach(future -> future.cancel(true));
                for (NodeInfo interruptedNode : scanNodes) {
                    if (scannedNodeIds.contains(interruptedNode.id) || scanErrorsByNode.containsKey(interruptedNode.id)) continue;
                    ReplicaScanError error = new ReplicaScanError(interruptedNode.id, interruptedNode.state().name(), "Scan interrupted.");
                    scanErrors.add(error);
                    scanErrorsByNode.put(interruptedNode.id, error);
                }
                break;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                String detail = cause.getMessage();
                ReplicaScanError error = new ReplicaScanError(node.id, node.state().name(),
                        detail == null || detail.isBlank() ? cause.getClass().getSimpleName() : detail);
                scanErrors.add(error);
                scanErrorsByNode.put(node.id, error);
            }
        }

        List<String> allObservedKeys = new ArrayList<>(copiesByKey.keySet());
        Collections.sort(allObservedKeys);
        boolean truncated = allObservedKeys.size() > sampleLimit;
        List<String> keys = allObservedKeys.subList(0, Math.min(sampleLimit, allObservedKeys.size()));
        Map<String, MutableNodeReplicaCount> counts = new TreeMap<>();
        for (NodeInfo node : scanNodes) counts.put(node.id, new MutableNodeReplicaCount(
                scannedNodeIds.contains(node.id), discoveredByNode.getOrDefault(node.id, 0), scanErrorsByNode.get(node.id)));
        for (String key : keys) {
            Map<String, CacheEntry> copies = copiesByKey.getOrDefault(key, Map.of());
            CacheEntry newest = null;
            for (CacheEntry copy : copies.values()) if (copy.isNewerThan(newest)) newest = copy;
            List<String> owners = c.ring.preferenceList(key, c.copies());
            if (owners.isEmpty()) continue;
            String primary = owners.get(0);
            MutableNodeReplicaCount primaryCounts = counts.computeIfAbsent(primary, ignored -> new MutableNodeReplicaCount(false, 0, null));
            primaryCounts.expectedPrimaryCount++;
            CacheEntry primaryCopy = copies.get(primary);
            if (scannedNodeIds.contains(primary) && primaryCopy != null) primaryCounts.actualPrimaryCount++;

            for (int i = 1; i < owners.size(); i++) {
                String replica = owners.get(i);
                MutableNodeReplicaCount replicaCounts = counts.computeIfAbsent(replica, ignored -> new MutableNodeReplicaCount(false, 0, null));
                replicaCounts.expectedReplicaCount++;
                CacheEntry replicaCopy = copies.get(replica);
                if (scannedNodeIds.contains(replica)) {
                    if (replicaCopy == null) replicaCounts.missingReplicaCount++;
                    else {
                        replicaCounts.actualReplicaCount++;
                        if (newest != null && newest.isNewerThan(replicaCopy)) replicaCounts.staleReplicaCount++;
                    }
                }
            }
        }
        List<NodeReplicaCount> per = new ArrayList<>();
        counts.forEach((id, n) -> per.add(new NodeReplicaCount(id, n.expectedPrimaryCount, n.expectedReplicaCount,
                n.expectedPrimaryCount, n.expectedReplicaCount, n.actualPrimaryCount, n.actualReplicaCount,
                n.missingReplicaCount, n.staleReplicaCount, n.scanSucceeded, n.keysDiscovered,
                n.scanSucceeded ? "SUCCESS" : "FAILED", n.scanError == null ? null : n.scanError.message())));
        int nodesScanned = scannedNodeIds.size();
        boolean scanComplete = !scanNodes.isEmpty() && nodesScanned == scanNodes.size();
        int keysInspected = keys.size();
        return new ReplicationView(c.config.replicationFactor, c.config.syncReplication, replication.stats(),
                replication.lagSamples(120), replication.recentFailures(50), per, keysInspected, keysInspected,
                sampleLimit, truncated, scanComplete && keysInspected == 0, scanNodes.size(), nodesScanned,
                scanErrors.size(), scanComplete, scanErrors, replication.latencySamples(120));
    }

    private ReplicaNodeScan scanNode(NodeInfo node) {
        try {
            return new ReplicaNodeScan(c.client.scan(node.address(), c.config.scanTimeoutMs), null);
        } catch (RuntimeException e) {
            String detail = e.getMessage();
            return new ReplicaNodeScan(List.of(), new ReplicaScanError(node.id, node.state().name(),
                    detail == null || detail.isBlank() ? e.getClass().getSimpleName() : detail));
        }
    }

    private static final class MutableNodeReplicaCount {
        private final boolean scanSucceeded;
        private final int keysDiscovered;
        private final ReplicaScanError scanError;
        private int expectedPrimaryCount;
        private int expectedReplicaCount;
        private int actualPrimaryCount;
        private int actualReplicaCount;
        private int missingReplicaCount;
        private int staleReplicaCount;

        private MutableNodeReplicaCount(boolean scanSucceeded, int keysDiscovered, ReplicaScanError scanError) {
            this.scanSucceeded = scanSucceeded;
            this.keysDiscovered = keysDiscovered;
            this.scanError = scanError;
        }
    }

    /** Asks every active node for its gossip membership view and checks whether they all agree. */
    public GossipOverview gossip() {
        List<GossipView> views = new ArrayList<>();
        Set<String> expected = c.ring.nodeIds();
        boolean converged = true;
        for (NodeInfo n : c.ringMembers()) {
            if (n.state() != NodeState.ACTIVE) continue;
            try {
                String raw = c.client.members(n.address(), c.config.pingTimeoutMs * 2);
                List<GossipMember> ms = new ArrayList<>();
                for (String part : raw.split(";")) {
                    String[] f = part.split(",");
                    if (f.length < 7) continue;
                    ms.add(new GossipMember(f[0], f[5], Long.parseLong(f[3]), Long.parseLong(f[4]), Long.parseLong(f[6])));
                }
                views.add(new GossipView(n.id, ms));
                Set<String> alive = new HashSet<>();
                for (GossipMember m : ms) if ("ALIVE".equals(m.status())) alive.add(m.id());
                if (!alive.containsAll(expected)) converged = false;
            } catch (CacheException e) { converged = false; }
        }
        return new GossipOverview(converged && !views.isEmpty(), views);
    }

    // ------------------------------------------------------------------ advisor input

    public AdvisorSnapshot advisorSnapshot() {
        List<NodeMetrics> nodes = new ArrayList<>();
        Map<String, Long> gw = metrics.requestsPerNode();
        int active = 0, total = 0;
        for (NodeInfo n : c.allNodes()) {
            if (n.state() == NodeState.MAINTENANCE || n.state() == NodeState.REMOVED) continue;
            total++;
            if (n.state() == NodeState.ACTIVE) active++;
            nodes.add(new NodeMetrics(n.id, n.state().name(), n.stat("used"), n.stat("max"), n.stat("entries"),
                    gw.getOrDefault(n.id, 0L), n.stat("evictions")));
        }
        MetricsRecorder.Counters k = metrics.counters();
        LatencyStats l = metrics.latency();
        ReplicationManager.Stats r = replication.stats();
        MetricsRecorder.HotKeyStats hotKeyStats = metrics.hotKeyStats();
        return new AdvisorSnapshot(nodes, k.total(), k.hits(), k.misses(), l.p95Ms(), l.p99Ms(), l.samples(),
                metrics.hotKeys(10), r.acked(), r.failed(), r.p95LagMs(),
                k.setsWithTtl(), k.setsNoTtl(), active, total, c.config.replicationFactor,
                c.config.heartbeatIntervalMs, c.config.failureTimeoutMs, hotKeyStats.readAccesses(),
                hotKeyStats.trackedReadAccesses(), hotKeyStats.trackedDistinctKeys(), hotKeyStats.trackingLimit(),
                hotKeyStats.trackingLimitReached());
    }

    @Override public void close() { sampler.shutdownNow(); scanExecutor.shutdownNow(); }
}
