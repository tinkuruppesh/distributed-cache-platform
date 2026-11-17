package com.distributedcache.cluster;

import com.distributedcache.cache.CacheEntry;
import com.distributedcache.monitoring.MonitoringService;
import com.distributedcache.replication.ReplicationManager;
import com.distributedcache.routing.CacheRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.distributedcache.cluster.TestCluster.await;
import static org.junit.jupiter.api.Assertions.*;

class ReplicationMonitoringTest {
    private TestCluster cluster;
    private ClusterRuntime runtime;

    @BeforeEach
    void start() throws Exception {
        cluster = new TestCluster(3);
        runtime = cluster.rt;
    }

    @AfterEach
    void stop() { cluster.close(); }

    @Test
    void emptyCacheIsDifferentFromUnavailableScans() {
        MonitoringService.ReplicationView view = runtime.monitoring.replicationView();

        assertTrue(view.scanComplete());
        assertTrue(view.cacheEmpty());
        assertEquals(0, view.keysInspected());
        assertEquals(3, view.nodesAttempted());
        assertEquals(3, view.nodesScanned());
        assertTrue(view.scanErrors().isEmpty());
        assertEquals(3, view.perNode().size());
        assertTrue(view.perNode().stream().allMatch(node -> node.scanStatus().equals("SUCCESS")));
        assertTrue(view.perNode().stream().allMatch(node -> node.keysDiscovered() == 0));
    }

    @Test
    void countsExpectedOwnersAndActuallyObservedCopiesSeparately() {
        for (int i = 0; i < 30; i++) runtime.router.set("replica-count:" + i, "value-" + i, null);
        assertTrue(cluster.awaitConsistent(5000));

        MonitoringService.ReplicationView view = runtime.monitoring.replicationView();

        assertTrue(view.scanComplete());
        assertFalse(view.cacheEmpty());
        assertEquals(30, view.keysInspected());
        assertEquals(30, view.perNode().stream().mapToInt(MonitoringService.NodeReplicaCount::expectedPrimaryCount).sum());
        assertEquals(30, view.perNode().stream().mapToInt(MonitoringService.NodeReplicaCount::actualPrimaryCount).sum());
        assertEquals(60, view.perNode().stream().mapToInt(MonitoringService.NodeReplicaCount::expectedReplicaCount).sum());
        assertEquals(60, view.perNode().stream().mapToInt(MonitoringService.NodeReplicaCount::actualReplicaCount).sum());
        assertEquals(0, view.perNode().stream().mapToInt(MonitoringService.NodeReplicaCount::missingReplicaCount).sum());
        assertEquals(0, view.perNode().stream().mapToInt(MonitoringService.NodeReplicaCount::staleReplicaCount).sum());
        assertEquals(60, runtime.replication.stats().sent());
        assertEquals(60, runtime.replication.stats().acked());
        assertEquals(0, runtime.replication.stats().failed());
    }

    @Test
    void partialAndCompleteScanFailuresAreReported() {
        runtime.config.scanTimeoutMs = 100;
        cluster.control("node-0", "ISOLATE");
        try {
            MonitoringService.ReplicationView partial = runtime.monitoring.replicationView();
            assertFalse(partial.scanComplete());
            assertFalse(partial.cacheEmpty());
            assertEquals(2, partial.nodesScanned());
            assertEquals(1, partial.nodesUnreachable());
            assertEquals("node-0", partial.scanErrors().getFirst().nodeId());
            assertEquals("FAILED", partial.perNode().stream().filter(node -> node.nodeId().equals("node-0"))
                    .findFirst().orElseThrow().scanStatus());
            assertTrue(partial.perNode().stream().filter(node -> node.nodeId().equals("node-0"))
                    .findFirst().orElseThrow().errorReason().contains("timeout"));
            assertEquals(2, partial.perNode().stream().filter(node -> node.scanStatus().equals("SUCCESS")).count());
        } finally {
            cluster.control("node-0", "UNISOLATE");
        }

        for (int i = 0; i < 3; i++) cluster.control("node-" + i, "ISOLATE");
        try {
            MonitoringService.ReplicationView failed = runtime.monitoring.replicationView();
            assertFalse(failed.scanComplete());
            assertFalse(failed.cacheEmpty());
            assertEquals(0, failed.nodesScanned());
            assertEquals(3, failed.nodesUnreachable());
            assertEquals(3, failed.scanErrors().size());
        } finally {
            for (int i = 0; i < 3; i++) cluster.control("node-" + i, "UNISOLATE");
        }
    }

    @Test
    void missingReplicaIsCountedOnlyAfterThatReplicaWasScanned() {
        String key = "replica-missing-check";
        runtime.router.set(key, "present", null);
        assertTrue(cluster.awaitConsistent(5000));
        String replica = runtime.router.locate(key).replicas().getFirst();
        cluster.server(replica).store().dropLocal(key);

        MonitoringService.ReplicationView view = runtime.monitoring.replicationView();

        assertTrue(view.scanComplete());
        assertEquals(1, view.perNode().stream().mapToInt(MonitoringService.NodeReplicaCount::missingReplicaCount).sum());
        assertEquals(1, view.perNode().stream().mapToInt(MonitoringService.NodeReplicaCount::actualReplicaCount).sum());
    }

    @Test
    void staleReplicaIsCountedWhenNewerVersionExistsElsewhere() {
        runtime.config.requestTimeoutMs = 100;
        runtime.config.suspectTimeoutMs = 10_000;
        String key = "replica-stale-check";
        runtime.router.set(key, "old", null);
        assertTrue(cluster.awaitConsistent(5000));
        String replica = runtime.router.locate(key).replicas().getFirst();
        long failuresBefore = runtime.replication.stats().failed();
        cluster.control(replica, "ISOLATE");
        try {
            runtime.router.set(key, "new", null);
            assertTrue(await(() -> runtime.replication.stats().failed() > failuresBefore, 3000));
        } finally {
            cluster.control(replica, "UNISOLATE");
        }

        MonitoringService.ReplicationView view = runtime.monitoring.replicationView();
        assertTrue(view.scanComplete());
        assertEquals(1, view.perNode().stream().mapToInt(MonitoringService.NodeReplicaCount::staleReplicaCount).sum());
        assertEquals(2, view.perNode().stream().mapToInt(MonitoringService.NodeReplicaCount::actualReplicaCount).sum(),
                "actual replica copies includes a copy that was found but is stale");
    }

    @Test
    void appliedStaleAndFailedResponsesRecordMonotonicRpcLatency() {
        String key = "replica-response-check";
        runtime.router.set(key, "current", null);
        assertTrue(await(() -> runtime.replication.stats().acked() >= 2, 3000));
        CacheRouter.Placement placement = runtime.router.locate(key);
        NodeInfo replica = runtime.coordinator.require(placement.replicas().getFirst());
        CacheEntry current = runtime.coordinator.client.get(runtime.coordinator.require(placement.primary()).address(), key, 1000);
        CacheEntry old = new CacheEntry(key, "older", current.createdAt(), 0, current.version() - 1,
                current.updatedAt() - 1, false);
        long staleBefore = runtime.replication.stats().stale();
        runtime.replication.replicate(old, List.of(replica));
        assertTrue(await(() -> runtime.replication.stats().stale() > staleBefore, 3000));

        long failedBefore = runtime.replication.stats().failed();
        runtime.config.requestTimeoutMs = 100;
        runtime.config.suspectTimeoutMs = 10_000;
        cluster.control(replica.id, "ISOLATE");
        try {
            runtime.replication.replicate(current, List.of(replica));
            assertTrue(await(() -> runtime.replication.stats().failed() > failedBefore, 3000));
        } finally {
            cluster.control(replica.id, "UNISOLATE");
        }

        List<ReplicationManager.LatencySample> samples = runtime.replication.latencySamples(20);
        assertTrue(samples.stream().anyMatch(sample -> sample.result().equals("APPLIED")));
        assertTrue(samples.stream().anyMatch(sample -> sample.result().equals("STALE")));
        assertTrue(samples.stream().anyMatch(sample -> sample.result().equals("FAILED")));
        assertTrue(samples.stream().allMatch(sample -> Double.isFinite(sample.latencyMs()) && sample.latencyMs() >= 0));
        assertTrue(runtime.replication.stats().p95LatencyMs() >= runtime.replication.stats().avgLatencyMs());
        ReplicationManager.Stats stats = runtime.replication.stats();
        double expectedAckAverage = samples.stream()
                .filter(sample -> !sample.result().equals("FAILED"))
                .mapToDouble(ReplicationManager.LatencySample::latencyMs).average().orElseThrow();
        assertEquals(4, stats.sent());
        assertEquals(3, stats.acked());
        assertEquals(2, stats.applied());
        assertEquals(1, stats.stale());
        assertEquals(1, stats.alreadyCurrent());
        assertEquals(1, stats.failed());
        assertEquals(3, stats.ackLatencySampleCount());
        assertEquals(expectedAckAverage, stats.avgAckLatencyMs(), 0.001);
        assertTrue(stats.p95AckLatencyMs() >= stats.avgAckLatencyMs());
    }

    @Test
    void asynchronousReplicationReturnsBeforeReplicaFailureIsRecorded() {
        String replicaId = "node-1";
        NodeInfo replica = runtime.coordinator.require(replicaId);
        runtime.config.requestTimeoutMs = 600;
        long failedBefore = runtime.replication.stats().failed();
        cluster.control(replicaId, "ISOLATE");
        try {
            CacheEntry entry = new CacheEntry("async-replication-check", "value", System.currentTimeMillis(), 0,
                    System.currentTimeMillis(), System.currentTimeMillis(), false);
            runtime.config.syncReplication = false;
            runtime.replication.replicate(entry, List.of(replica));

            assertEquals(failedBefore, runtime.replication.stats().failed(),
                    "an async write must return before the replica timeout response exists");
            assertTrue(await(() -> runtime.replication.stats().failed() > failedBefore, 3000),
                    "the failed RPC must be counted when its response path completes");
        } finally {
            cluster.control(replicaId, "UNISOLATE");
        }
    }

    @Test
    void synchronousReplicationWaitsUntilReplicaOutcomeIsRecorded() {
        String replicaId = "node-1";
        NodeInfo replica = runtime.coordinator.require(replicaId);
        runtime.config.requestTimeoutMs = 300;
        runtime.config.syncReplication = true;
        long failedBefore = runtime.replication.stats().failed();
        cluster.control(replicaId, "ISOLATE");
        try {
            CacheEntry entry = new CacheEntry("sync-replication-check", "value", System.currentTimeMillis(), 0,
                    System.currentTimeMillis(), System.currentTimeMillis(), false);
            runtime.replication.replicate(entry, List.of(replica));
            assertTrue(runtime.replication.stats().failed() > failedBefore,
                    "sync mode should return after a completed failure response or its configured wait limit");
        } finally {
            runtime.config.syncReplication = false;
            cluster.control(replicaId, "UNISOLATE");
        }
    }
}
