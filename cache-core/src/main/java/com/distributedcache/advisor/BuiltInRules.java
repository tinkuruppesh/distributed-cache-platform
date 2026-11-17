package com.distributedcache.advisor;

import com.distributedcache.advisor.AdvisorSnapshot.NodeMetrics;
import com.distributedcache.advisor.Recommendation.Category;
import com.distributedcache.advisor.Recommendation.Severity;
import com.distributedcache.monitoring.MetricsRecorder.HotKey;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Deterministic, explainable rules. Each rule has minimum-sample guards so it never fires on tiny or invented data.
 * No AI/LLM involved: thresholds are plain constants you can defend in an interview.
 */
public final class BuiltInRules {
    private BuiltInRules() {}

    private static String f(double v) { return String.format(Locale.ROOT, "%.1f", v); }

    public static List<RecommendationRule> all() {
        return List.of(named("memory", memory()), named("hit-ratio", hitRatio()), named("hot-key", hotKey()),
                named("load-imbalance", loadImbalance()), named("replication", replication()),
                named("latency", latency()), named("evictions", evictions()), named("configuration", configuration()),
                named("scalability", scalability(), s -> Set.of("capacity-vs-rf", "reduced-capacity")));
    }

    private static RecommendationRule named(String name, RecommendationRule delegate) {
        return named(name, delegate, delegate::fullyEvaluatedConditions);
    }

    private static RecommendationRule named(String name, RecommendationRule delegate,
                                            java.util.function.Function<AdvisorSnapshot, Set<String>> evaluatedConditions) {
        return new RecommendationRule() {
            @Override public List<Finding> evaluate(AdvisorSnapshot snapshot) { return delegate.evaluate(snapshot); }
            @Override public String name() { return name; }
            @Override public Set<String> fullyEvaluatedConditions(AdvisorSnapshot snapshot) {
                return evaluatedConditions.apply(snapshot);
            }
        };
    }

    static RecommendationRule memory() {
        return s -> {
            List<Finding> out = new ArrayList<>();
            for (NodeMetrics n : s.nodes()) {
                if (n.maxBytes() <= 0) continue;
                double pct = 100.0 * n.usedBytes() / n.maxBytes();
                if (pct < 80) continue;
                Severity sev = pct >= 90 ? Severity.CRITICAL : Severity.WARNING;
                out.add(new Finding("memory:" + n.id(), Category.MEMORY, sev,
                        n.id() + " memory usage is " + f(pct) + "%",
                        n.id() + " is using " + f(pct) + "% of its configured memory limit.",
                        "memory_used=" + n.usedBytes() + "B / limit=" + n.maxBytes() + "B (" + f(pct) + "%)",
                        "Above 80% the node starts evicting entries, which lowers the hit ratio.",
                        "Increase the per-node memory limit, add a node, or review TTLs and the eviction strategy."));
            }
            return out;
        };
    }

    static RecommendationRule hitRatio() {
        return s -> {
            long gets = s.hits() + s.misses();
            if (gets < 100) return List.of();
            double ratio = 100.0 * s.hits() / gets;
            if (ratio >= 70) return List.of();
            Severity sev = ratio < 50 ? Severity.CRITICAL : Severity.WARNING;
            return List.of(new Finding("hit-ratio", Category.PERFORMANCE, sev, "Cache hit ratio is " + f(ratio) + "%",
                    "Only " + f(ratio) + "% of reads are answered from the cache.",
                    "hits=" + s.hits() + ", misses=" + s.misses() + ", ratio=" + f(ratio) + "%",
                    "A low hit ratio means most reads still reach the primary database, so the cache gives little benefit.",
                    "Review frequently requested keys, increase TTLs where data allows, and check for premature evictions."));
        };
    }

    static RecommendationRule hotKey() {
        return s -> {
            // Once distinct-key tracking overflows, the tracked-key denominator is incomplete and
            // cannot support an accurate cluster-wide share. Suppress this finding rather than overstate it.
            if (s.keyTrackingLimitReached() || s.readAccesses() < 200 || s.trackedReadAccesses() < 200 || s.hotKeys().isEmpty()) return List.of();
            HotKey top = s.hotKeys().get(0);
            double share = 100.0 * top.count() / s.trackedReadAccesses();
            if (share < 30) return List.of();
            Severity sev = share >= 60 ? Severity.CRITICAL : Severity.WARNING;
            return List.of(new Finding("hot-key:" + top.key(), Category.HOT_KEYS, sev,
                    "Key " + top.key() + " receives " + f(share) + "% of all accesses",
                    "A single key dominates measured GET traffic, so the node owning it carries a disproportionate read load.",
                    "key=" + top.key() + ", GET reads=" + top.count() + " of " + s.trackedReadAccesses() + " tracked GET reads (" + f(share) + "%)",
                    "Hot keys overload one primary and its replicas regardless of how many nodes exist.",
                    "Evaluate hot-key mitigation: client-side/local caching, key splitting (key#1..#N), or more read replicas."));
        };
    }

    static RecommendationRule loadImbalance() {
        return s -> {
            List<NodeMetrics> active = s.nodes().stream().filter(n -> "ACTIVE".equals(n.state())).toList();
            long total = active.stream().mapToLong(NodeMetrics::gatewayRequests).sum();
            if (active.size() < 3 || total < 300) return List.of();
            double mean = (double) total / active.size();
            List<Finding> out = new ArrayList<>();
            for (NodeMetrics n : active) {
                double ratio = n.gatewayRequests() / mean;
                if (ratio < 1.5) continue;
                out.add(new Finding("load:" + n.id(), Category.SCALABILITY, ratio >= 2.5 ? Severity.CRITICAL : Severity.WARNING,
                        n.id() + " handles " + f(ratio) + "x the average load",
                        n.id() + " serves significantly more requests than the other nodes.",
                        "requests=" + n.gatewayRequests() + ", cluster mean=" + f(mean) + ", ratio=" + f(ratio) + "x",
                        "Uneven load usually means skewed data distribution or a hot key.",
                        "Review data distribution (virtual node count) and check the hot-key analysis."));
            }
            return out;
        };
    }

    static RecommendationRule replication() {
        return s -> {
            List<Finding> out = new ArrayList<>();
            if (s.replicationAcked() >= 20 && s.replicationP95LagMs() > 200) {
                out.add(new Finding("replication-lag", Category.REPLICATION,
                        s.replicationP95LagMs() > 1000 ? Severity.CRITICAL : Severity.WARNING,
                        "Replication lag is high (p95 " + f(s.replicationP95LagMs()) + " ms)",
                        "Replicas are receiving writes noticeably later than primaries.",
                        "replication_lag_p95=" + f(s.replicationP95LagMs()) + "ms over recent writes",
                        "With asynchronous replication, lag is the window in which a primary failure can lose data.",
                        "Investigate node-to-node network latency and node load; consider synchronous replication for critical keys."));
            }
            long attempts = s.replicationAcked() + s.replicationFailed();
            if (attempts >= 20 && s.replicationFailed() > 0) {
                double pct = 100.0 * s.replicationFailed() / attempts;
                if (pct >= 1) out.add(new Finding("replication-failures", Category.REPLICATION,
                        pct >= 10 ? Severity.CRITICAL : Severity.WARNING,
                        f(pct) + "% of replication writes failed",
                        "Some replica writes could not be delivered.",
                        "failed=" + s.replicationFailed() + " of " + attempts + " (" + f(pct) + "%)",
                        "Failed replica writes leave keys with fewer copies until reconciliation runs.",
                        "Check node health and connectivity; a reconcile runs automatically after failover/recovery."));
            }
            return out;
        };
    }

    static RecommendationRule latency() {
        return s -> {
            if (s.latencySamples() < 100 || s.p99LatencyMs() <= 100) return List.of();
            return List.of(new Finding("latency-p99", Category.PERFORMANCE,
                    s.p99LatencyMs() > 500 ? Severity.CRITICAL : Severity.WARNING,
                    "P99 latency is " + f(s.p99LatencyMs()) + " ms",
                    "The slowest 1% of requests take over 100 ms.",
                    "p95=" + f(s.p95LatencyMs()) + "ms, p99=" + f(s.p99LatencyMs()) + "ms over " + s.latencySamples() + " requests",
                    "Tail latency is often caused by timeouts to unhealthy nodes before falling back to a replica.",
                    "Check for suspected/failed nodes and reduce request timeout or heartbeat interval if appropriate."));
        };
    }

    static RecommendationRule evictions() {
        return s -> {
            List<Finding> out = new ArrayList<>();
            for (NodeMetrics n : s.nodes()) {
                if (n.evictions() == 0) continue;
                out.add(new Finding("evictions:" + n.id(), Category.MEMORY,
                        n.evictions() >= 100 ? Severity.WARNING : Severity.INFO,
                        n.id() + " has evicted " + n.evictions() + " entries",
                        "Entries are being removed to stay inside the memory limit.",
                        "evictions=" + n.evictions() + ", entries=" + n.entries(),
                        "Evicted keys must be re-fetched from the database and lower the hit ratio.",
                        "Raise the memory limit, shorten TTLs for cold data, or switch eviction policy (LRU vs LFU)."));
            }
            return out;
        };
    }

    static RecommendationRule configuration() {
        return s -> {
            List<Finding> out = new ArrayList<>();
            long sets = s.setsWithTtl() + s.setsNoTtl();
            if (sets >= 100 && 100.0 * s.setsNoTtl() / sets >= 90) {
                out.add(new Finding("ttl-config", Category.CONFIGURATION, Severity.INFO,
                        f(100.0 * s.setsNoTtl() / sets) + "% of writes have no TTL",
                        "Almost all cached entries never expire.",
                        "writes_without_ttl=" + s.setsNoTtl() + " of " + sets,
                        "Entries without TTL can serve stale data and fill memory.",
                        "Set a default TTL in Settings or pass a TTL per write."));
            }
            if (s.failureTimeoutMs() < 3 * s.heartbeatIntervalMs()) {
                out.add(new Finding("heartbeat-config", Category.CONFIGURATION, Severity.WARNING,
                        "Failure timeout is too close to the heartbeat interval",
                        "A node could be declared failed after one or two delayed heartbeats.",
                        "heartbeat=" + s.heartbeatIntervalMs() + "ms, failure.timeout=" + s.failureTimeoutMs() + "ms",
                        "Detection needs several missed heartbeats to avoid false positives.",
                        "Use a failure timeout of at least 3-5x the heartbeat interval."));
            }
            return out;
        };
    }

    static RecommendationRule scalability() {
        return s -> {
            List<Finding> out = new ArrayList<>();
            if (s.activeNodes() < s.replicationFactor() + 1) {
                out.add(new Finding("capacity-vs-rf", Category.SCALABILITY, Severity.CRITICAL,
                        "Not enough active nodes for the replication factor",
                        "Only " + s.activeNodes() + " node(s) are active but " + (s.replicationFactor() + 1) + " copies are configured.",
                        "active_nodes=" + s.activeNodes() + ", replication_factor=" + s.replicationFactor(),
                        "Keys cannot be fully replicated, so another failure may cause data loss.",
                        "Restore failed nodes or add nodes; lower the replication factor only as a last resort."));
            } else if (s.activeNodes() < s.totalNodes()) {
                out.add(new Finding("reduced-capacity", Category.SCALABILITY, Severity.WARNING,
                        (s.totalNodes() - s.activeNodes()) + " node(s) are not active",
                        "Cluster capacity and redundancy are reduced.",
                        "active=" + s.activeNodes() + " of " + s.totalNodes(),
                        "Remaining nodes carry extra load and extra data.",
                        "Recover or replace the unavailable nodes."));
            }
            return out;
        };
    }

}
