package com.distributedcache.unit;

import com.distributedcache.advisor.*;
import com.distributedcache.advisor.AdvisorSnapshot.NodeMetrics;
import com.distributedcache.advisor.Recommendation.Category;
import com.distributedcache.advisor.Recommendation.Severity;
import com.distributedcache.advisor.Recommendation.Status;
import com.distributedcache.monitoring.MetricsRecorder.HotKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The rules are pure functions of a snapshot, so they are tested with explicit inputs. */
public class AdvisorRulesTest {

    private static AdvisorSnapshot snap(List<NodeMetrics> nodes, long hits, long misses, List<HotKey> hot, long keyAccesses) {
        return new AdvisorSnapshot(nodes, hits + misses, hits, misses, 5, 20, 1000, hot,
                0, 0, 0, 0, 0, nodes.size(), nodes.size(), 2, 1000, 5000,
                keyAccesses, keyAccesses, hot.size(), 5000, false);
    }

    private static List<Finding> run(AdvisorSnapshot s) {
        return BuiltInRules.all().stream().flatMap(r -> r.evaluate(s).stream()).toList();
    }

    @Test
    void highMemoryNodeProducesMemoryFinding() {
        var s = snap(List.of(new NodeMetrics("node-2", "ACTIVE", 88, 100, 10, 0, 0),
                new NodeMetrics("node-1", "ACTIVE", 10, 100, 10, 0, 0),
                new NodeMetrics("node-3", "ACTIVE", 10, 100, 10, 0, 0)), 0, 0, List.of(), 0);
        List<Finding> f = run(s);
        assertEquals(1, f.stream().filter(x -> x.category() == Category.MEMORY).count());
        Finding m = f.stream().filter(x -> x.category() == Category.MEMORY).findFirst().get();
        assertEquals(Severity.WARNING, m.severity());
        assertTrue(m.supportingMetric().contains("88"));
    }

    @Test
    void lowHitRatioNeedsEnoughSamples() {
        var nodes = List.of(new NodeMetrics("n1", "ACTIVE", 1, 100, 1, 0, 0), new NodeMetrics("n2", "ACTIVE", 1, 100, 1, 0, 0),
                new NodeMetrics("n3", "ACTIVE", 1, 100, 1, 0, 0));
        assertTrue(run(snap(nodes, 5, 20, List.of(), 0)).stream().noneMatch(f -> f.dedupeKey().equals("hit-ratio")),
                "25 gets is too small a sample to judge");
        var f = run(snap(nodes, 580, 420, List.of(), 0)).stream().filter(x -> x.dedupeKey().equals("hit-ratio")).toList();
        assertEquals(1, f.size());
        assertEquals(Severity.WARNING, f.get(0).severity());
        assertTrue(f.get(0).title().contains("58"));
    }

    @Test
    void hotKeyIsDetectedFromAccessShare() {
        var nodes = List.of(new NodeMetrics("n1", "ACTIVE", 1, 100, 1, 0, 0), new NodeMetrics("n2", "ACTIVE", 1, 100, 1, 0, 0),
                new NodeMetrics("n3", "ACTIVE", 1, 100, 1, 0, 0));
        var warn = run(snap(nodes, 500, 500, List.of(new HotKey("product:101", 400), new HotKey("x", 100)), 1000));
        assertTrue(warn.stream().anyMatch(x -> x.category() == Category.HOT_KEYS && x.severity() == Severity.WARNING),
                "40% of accesses on one key is a WARNING");
        var crit = run(snap(nodes, 500, 500, List.of(new HotKey("product:101", 700), new HotKey("x", 100)), 1000));
        assertTrue(crit.stream().anyMatch(x -> x.category() == Category.HOT_KEYS && x.severity() == Severity.CRITICAL),
                "70% of accesses on one key is CRITICAL");
        var none = run(snap(nodes, 500, 500, List.of(new HotKey("a", 200), new HotKey("b", 150)), 1000));
        assertTrue(none.stream().noneMatch(x -> x.category() == Category.HOT_KEYS), "20% share is not a hot key");
    }

    @Test
    void loadImbalanceIsDetected() {
        var nodes = List.of(new NodeMetrics("n1", "ACTIVE", 1, 100, 1, 100, 0), new NodeMetrics("n2", "ACTIVE", 1, 100, 1, 100, 0),
                new NodeMetrics("n3", "ACTIVE", 1, 100, 1, 700, 0));
        assertTrue(run(snap(nodes, 0, 0, List.of(), 0)).stream().anyMatch(f -> f.dedupeKey().equals("load:n3")));
    }

    @Test
    void notEnoughNodesForReplicationFactorIsCritical() {
        var nodes = List.of(new NodeMetrics("n1", "ACTIVE", 1, 100, 1, 0, 0), new NodeMetrics("n2", "ACTIVE", 1, 100, 1, 0, 0));
        var f = run(new AdvisorSnapshot(nodes, 0, 0, 0, 0, 0, 0, List.of(), 0, 0, 0, 0, 0, 2, 3, 2, 1000, 5000,
                0, 0, 0, 5000, false));
        assertTrue(f.stream().anyMatch(x -> x.dedupeKey().equals("capacity-vs-rf") && x.severity() == Severity.CRITICAL));
    }

    @Test
    void replicationLagAndBadHeartbeatConfigAreReported() {
        var nodes = List.of(new NodeMetrics("n1", "ACTIVE", 1, 100, 1, 0, 0), new NodeMetrics("n2", "ACTIVE", 1, 100, 1, 0, 0),
                new NodeMetrics("n3", "ACTIVE", 1, 100, 1, 0, 0));
        var f = run(new AdvisorSnapshot(nodes, 0, 0, 0, 0, 0, 0, List.of(), 100, 0, 350, 0, 0, 3, 3, 2, 1000, 2000,
                0, 0, 0, 5000, false));
        assertTrue(f.stream().anyMatch(x -> x.dedupeKey().equals("replication-lag")));
        assertTrue(f.stream().anyMatch(x -> x.dedupeKey().equals("heartbeat-config")));
    }

    @Test
    void healthyClusterProducesNoFindings() {
        var nodes = List.of(new NodeMetrics("n1", "ACTIVE", 10, 100, 1, 100, 0), new NodeMetrics("n2", "ACTIVE", 10, 100, 1, 100, 0),
                new NodeMetrics("n3", "ACTIVE", 10, 100, 1, 100, 0));
        assertTrue(run(snap(nodes, 900, 100, List.of(new HotKey("a", 10), new HotKey("b", 10)), 300)).isEmpty());
    }

    @Test
    void hotKeyFindingIsSuppressedWhenDistinctKeyTrackingOverflowed() {
        var nodes = List.of(new NodeMetrics("n1", "ACTIVE", 1, 100, 1, 0, 0), new NodeMetrics("n2", "ACTIVE", 1, 100, 1, 0, 0),
                new NodeMetrics("n3", "ACTIVE", 1, 100, 1, 0, 0));
        var snapshot = new AdvisorSnapshot(nodes, 0, 0, 0, 0, 0, 0,
                List.of(new HotKey("popular", 900)), 0, 0, 0, 0, 0, 3, 3, 2, 1000, 5000,
                1000, 900, 5000, 5000, true);
        assertTrue(run(snapshot).stream().noneMatch(f -> f.category() == Category.HOT_KEYS));
    }

    @Test
    void storeDeduplicatesAndTracksWorkflowStatus() {
        RecommendationStore store = new RecommendationStore();
        Finding f = new Finding("memory:n1", Category.MEMORY, Severity.WARNING, "t", "d", "m", "r", "a");
        assertEquals(1, store.upsert(List.of(f)));
        assertEquals(0, store.upsert(List.of(f)), "same open finding must not be duplicated");
        Recommendation r = store.list(null, null).get(0);
        assertEquals(Status.OPEN, r.status());
        assertEquals(Status.ACKNOWLEDGED, store.setStatus(r.id(), Status.ACKNOWLEDGED).status());
        assertEquals(0, store.upsert(List.of(f)), "acknowledged finding is still active");
        assertEquals(Status.RESOLVED, store.setStatus(r.id(), Status.RESOLVED).status());
        assertEquals(0, store.upsert(List.of(f)), "cooldown prevents immediate re-creation");
        assertEquals(1, store.list(Status.RESOLVED, null).size());
    }
}
