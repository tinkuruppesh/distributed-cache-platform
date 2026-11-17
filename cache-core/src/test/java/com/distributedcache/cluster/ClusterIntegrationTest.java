package com.distributedcache.cluster;

import com.distributedcache.exception.ForbiddenOperationException;
import com.distributedcache.exception.ConflictException;
import com.distributedcache.exception.InvalidRequestException;
import com.distributedcache.networking.NodeAddress;
import com.distributedcache.routing.CacheRouter;
import com.distributedcache.simulation.SimulationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.distributedcache.cluster.TestCluster.await;
import static org.junit.jupiter.api.Assertions.*;

/** End-to-end tests against six real cache nodes. Nothing is mocked. */
public class ClusterIntegrationTest {
    private TestCluster t;
    private ClusterRuntime rt;

    @BeforeEach
    void start() throws Exception {
        t = new TestCluster(6);
        rt = t.rt;
    }

    @AfterEach
    void stop() { t.close(); }

    private void load(String prefix, int n) {
        for (int i = 0; i < n; i++) rt.router.set(prefix + i, "value-" + i, null);
    }

    private int misses(String prefix, int n) {
        int miss = 0;
        for (int i = 0; i < n; i++) if (rt.router.get(prefix + i).entry() == null) miss++;
        return miss;
    }

    @Test
    void allSixNodesStartActiveAndOwnTheRing() {
        assertEquals(6, rt.coordinator.ring.size());
        for (NodeInfo n : rt.coordinator.allNodes()) assertEquals(NodeState.ACTIVE, n.state());
        assertEquals("HEALTHY", rt.monitoring.overview().health());
    }

    @Test
    void dataIsDistributedAndEveryKeyHasThreeCopies() {
        load("dist:", 300);
        assertTrue(t.awaitConsistent(5000), "async replication must converge");
        Map<String, Integer> primaries = new HashMap<>();
        for (int i = 0; i < 300; i++) {
            String key = "dist:" + i;
            int holders = 0;
            for (var s : t.servers) if (s.store().peek(key) != null) holders++;
            assertEquals(3, holders, "1 primary + 2 replicas for " + key);
            primaries.merge(rt.router.locate(key).primary(), 1, Integer::sum);
        }
        assertEquals(6, primaries.size(), "every node must be primary for some keys");
    }

    @Test
    void primaryFailureTriggersFailoverAndNoDataIsLost() {
        load("fo:", 200);
        assertTrue(t.awaitConsistent(5000));
        String victim = "node-0";

        long isolatedAt = System.currentTimeMillis();
        t.control(victim, "ISOLATE");
        assertTrue(await(() -> t.state(victim) == NodeState.FAILED, 10_000), "node must be declared FAILED");
        long detection = System.currentTimeMillis() - isolatedAt;
        assertTrue(detection >= 1000, "must NOT be declared dead after a single missed heartbeat (took " + detection + "ms)");

        assertFalse(rt.coordinator.ring.containsNode(victim), "routing updated: failed node left the ring");
        assertEquals(0, misses("fo:", 200), "all keys remain readable through promoted replicas");
        assertEquals(1, rt.failover.history().size());

        // event ordering: suspected before failed
        List<ClusterEvent> ev = rt.coordinator.events.recent(100);
        int idxSuspected = -1, idxFailed = -1;
        for (int i = 0; i < ev.size(); i++) {
            if (ev.get(i).type().equals("NODE_SUSPECTED")) idxSuspected = i;
            if (ev.get(i).type().equals("NODE_FAILED")) idxFailed = i;
        }
        assertTrue(idxSuspected > idxFailed && idxFailed >= 0, "events are newest-first: SUSPECTED must be older than FAILED");

        // full redundancy restored on the 5 remaining nodes
        assertTrue(t.awaitConsistent(8000), "re-replication must restore 3 copies per key");

        // restart: node recovers and rejoins
        t.control(victim, "UNISOLATE");
        assertTrue(await(() -> t.state(victim) == NodeState.ACTIVE, 10_000), "node must recover to ACTIVE");
        assertTrue(rt.coordinator.ring.containsNode(victim));
        assertTrue(t.awaitConsistent(8000));
        assertEquals(0, misses("fo:", 200));
    }

    @Test
    void failoverMovesPrimaryRoutingToReplicaAndPreservesGetSetContinuity() {
        String failed = "node-1";
        String key = null;
        CacheRouter.Placement before = null;
        for (int i = 0; i < 10_000; i++) {
            String candidate = "routing-proof:" + i;
            CacheRouter.Placement placement = rt.router.locate(candidate);
            if (failed.equals(placement.primary())) { key = candidate; before = placement; break; }
        }
        assertNotNull(key, "test key must be owned by the selected node");
        assertTrue(before.replicas().size() >= 1);
        rt.router.set(key, "before-failure", null);
        assertTrue(t.awaitConsistent(5000));
        for (String owner : concat(before.primary(), before.replicas())) {
            var stored = t.server(owner).store().peek(key);
            assertNotNull(stored, owner + " must hold the primary/replica copy before failure");
            assertEquals("before-failure", stored.value());
        }

        t.control(failed, "ISOLATE");
        assertTrue(await(() -> t.state(failed) == NodeState.FAILED, 10_000));
        CacheRouter.Placement after = rt.router.locate(key);
        assertNotEquals(failed, after.primary());
        assertTrue(before.replicas().contains(after.primary()), "the new primary must be an eligible former replica");
        assertEquals("before-failure", rt.router.get(key).entry().value());
        assertEquals(after.primary(), rt.router.get(key).servedBy());

        rt.router.set(key, "written-during-failure", null);
        assertEquals("written-during-failure", rt.router.get(key).entry().value());
        assertTrue(t.awaitConsistent(8000));
        for (String owner : after.states().keySet()) {
            var stored = t.server(owner).store().peek(key);
            assertNotNull(stored, owner + " must hold a current copy after failover reconciliation");
            assertEquals("written-during-failure", stored.value());
        }
        assertEquals(1, rt.failover.history().size());
        assertEquals(failed, rt.failover.history().getFirst().nodeId());
    }

    @Test
    void crashedNodeIsRebuiltFromReplicasAndNewerDataIsNeverOverwritten() {
        load("cr:", 120);
        assertTrue(t.awaitConsistent(5000));
        t.control("node-1", "CRASH");
        assertTrue(await(() -> t.state("node-1") == NodeState.FAILED, 10_000));

        // changes made while the node is down
        for (int i = 0; i < 60; i++) rt.router.set("cr:" + i, "updated-" + i, null);
        rt.router.delete("cr:100");
        load("new:", 40);

        t.control("node-1", "UNISOLATE");
        assertTrue(await(() -> t.state("node-1") == NodeState.ACTIVE, 10_000));
        assertTrue(t.awaitConsistent(8000));
        assertTrue(t.server("node-1").store().size() > 0, "the wiped node must have been repopulated");
        for (int i = 0; i < 60; i++) assertEquals("updated-" + i, rt.router.get("cr:" + i).entry().value());
        assertNull(rt.router.get("cr:100").entry(), "a delete made during the outage must not be resurrected");
        assertEquals(0, misses("new:", 40));
    }

    @Test
    void staleNodeDoesNotOverwriteNewerDataAfterPartitionHeals() {
        rt.router.set("stale:key", "v1", null);
        assertTrue(t.awaitConsistent(5000));
        String primary = rt.router.locate("stale:key").primary();
        t.control(primary, "ISOLATE");                         // data stays on the isolated node (stale after the next write)
        assertTrue(await(() -> t.state(primary) == NodeState.FAILED, 10_000));
        rt.router.set("stale:key", "v2", null);
        t.control(primary, "UNISOLATE");
        assertTrue(await(() -> t.state(primary) == NodeState.ACTIVE, 10_000));
        assertTrue(t.awaitConsistent(8000));
        assertEquals("v2", rt.router.get("stale:key").entry().value(), "stale v1 on the returning node must lose");
        for (var s : t.servers) {
            var e = s.store().peek("stale:key");
            if (e != null) assertEquals("v2", e.value());
        }
    }

    @Test
    void requestsFallBackToReplicasWhilePrimaryIsBlackHoled() {
        String victim = "node-2";
        t.control(victim, "ISOLATE");
        int found = 0, fallbacks = 0, tried = 0;
        for (int i = 0; tried < 5 && i < 5000; i++) {
            String key = "fb:" + i;
            if (!victim.equals(rt.router.locate(key).primary())) continue;
            tried++;
            CacheRouter.WriteResult w = rt.router.set(key, "x", null);     // must not throw
            if (w.fallbackAttempts() > 0) fallbacks++;
            if (rt.router.get(key).entry() != null) found++;
        }
        assertEquals(5, tried);
        assertEquals(5, found, "writes made through a replica must be readable");
        assertTrue(fallbacks >= 1, "at least the first request hit the timeout and fell back");
    }

    @Test
    void singleMissedHeartbeatNeverCausesFailover() {
        t.control("node-3", "ISOLATE");
        sleep(250);
        t.control("node-3", "UNISOLATE");
        assertTrue(await(() -> t.state("node-3") == NodeState.ACTIVE, 5000));
        sleep(1500);
        assertEquals(0, rt.failover.history().size());
        assertTrue(rt.coordinator.ring.containsNode("node-3"));
    }

    @Test
    void managerRefusesFailoverWithoutQuorum() {
        for (String id : List.of("node-0", "node-1", "node-2")) t.control(id, "ISOLATE");
        sleep(3500);
        for (NodeInfo n : rt.coordinator.allNodes()) assertNotEquals(NodeState.FAILED, n.state(), n.id + " must not fail over without quorum");
        assertEquals(6, rt.coordinator.ring.size());
        assertTrue(rt.coordinator.events.recent(200).stream().anyMatch(e -> e.type().equals("FAILOVER_BLOCKED")));
        for (String id : List.of("node-0", "node-1", "node-2")) t.control(id, "UNISOLATE");
        assertTrue(await(() -> rt.coordinator.allNodes().stream().allMatch(n -> n.state() == NodeState.ACTIVE), 8000));
    }

    @Test
    void multipleSequentialFailuresAreHandled() {
        load("multi:", 150);
        assertTrue(t.awaitConsistent(5000));
        t.control("node-0", "ISOLATE");
        assertTrue(await(() -> t.state("node-0") == NodeState.FAILED, 10_000));
        assertTrue(t.awaitConsistent(8000));
        t.control("node-1", "ISOLATE");
        assertTrue(await(() -> t.state("node-1") == NodeState.FAILED, 10_000));
        assertTrue(t.awaitConsistent(8000));
        assertEquals(2, rt.failover.history().size());
        assertEquals(0, misses("multi:", 150));
    }

    @Test
    void nodeJoinsAndLeavesGracefully() throws Exception {
        load("jn:", 300);
        assertTrue(t.awaitConsistent(5000));
        NodeAddress a = t.newNode("node-new");
        NodeInfo joined = rt.membership.join(a);
        assertEquals(NodeState.ACTIVE, joined.state());
        assertEquals(7, rt.coordinator.ring.size());
        assertTrue(t.awaitConsistent(5000));
        assertTrue(t.server("node-new").store().size() > 0, "new node must receive its key ranges");
        assertEquals(0, misses("jn:", 300));

        rt.membership.remove("node-1");
        assertEquals(6, rt.coordinator.ring.size());
        assertNull(rt.coordinator.node("node-1"));
        assertTrue(t.awaitConsistent(5000));
        assertEquals(0, misses("jn:", 300));
    }

    @Test
    void maintenanceDrainsAndResumeSynchronizesBeforeReturningToService() {
        load("maintenance:", 120);
        assertTrue(t.awaitConsistent(5000));

        rt.membership.maintenance("node-2");
        assertEquals(NodeState.MAINTENANCE, rt.coordinator.require("node-2").state());
        assertFalse(rt.coordinator.ring.containsNode("node-2"));
        assertEquals(0, misses("maintenance:", 120), "keys remain readable after graceful drain");

        rt.membership.resume("node-2");
        assertEquals(NodeState.ACTIVE, rt.coordinator.require("node-2").state());
        assertTrue(rt.coordinator.ring.containsNode("node-2"));
        assertTrue(t.awaitConsistent(8000));
        assertEquals(0, misses("maintenance:", 120));
        assertTrue(rt.coordinator.events.recent(100).stream().anyMatch(e -> e.type().equals("NODE_ACTIVE") && "node-2".equals(e.nodeId())));
    }

    @Test
    void nodeWithWrongIdentityCannotJoin() throws Exception {
        NodeAddress real = t.newNode("honest");
        NodeAddress lie = new NodeAddress("someone-else", real.host(), real.port());
        assertThrows(InvalidRequestException.class, () -> rt.membership.join(lie));
    }

    @Test
    void ttlExpiresOnPrimaryAndReplicas() {
        rt.router.set("ttl:key", "v", 400L);
        assertNotNull(rt.router.get("ttl:key").entry());
        long ttl = rt.router.ttl("ttl:key");
        assertTrue(ttl > 0 && ttl <= 400, "ttl=" + ttl);
        assertTrue(t.awaitConsistent(3000));
        sleep(600);
        assertNull(rt.router.get("ttl:key").entry());
        assertEquals(-2, rt.router.ttl("ttl:key"));
        for (var s : t.servers) assertNull(s.store().peek("ttl:key"));
    }

    @Test
    void concurrentClientsAreAllServedAndMeasured() throws Exception {
        int threads = 16, ops = 200;
        List<Thread> ts = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int id = i;
            Thread th = new Thread(() -> {
                for (int j = 0; j < ops; j++) {
                    rt.router.set("cc:" + id + ":" + j, "v", null);
                    rt.router.get("cc:" + id + ":" + j);
                }
            });
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) th.join();
        var counters = rt.metrics.counters();
        assertEquals((long) threads * ops * 2, counters.total());
        assertEquals(0, counters.failed());
        boolean converged = t.awaitConsistent(8000);
        if (!converged) {
            var replication = rt.replication.stats();
            var consistency = rt.verifier.verify();
            assertTrue(false, "async replication did not converge in 8s; replication=" + replication
                    + ", recent replication failures=" + rt.replication.recentFailures(10)
                    + ", post-timeout verification=" + consistency);
        }
        assertTrue(rt.metrics.latency().p99Ms() > 0);
    }

    @Test
    void simulationProducesReportFromMeasuredValues() {
        load("sim-base:", 50);
        rt.router.set("product:101", "iPhone 17 Pro", null);
        rt.router.set("sim:key:0", "user-owned-value", null);
        SimulationService.RunView started = rt.simulation.start(
                new SimulationService.StartRequest("node-4", SimulationService.Mode.ISOLATE, true, 60, 1));
        assertTrue(await(() -> rt.simulation.get(started.id()).phase() == SimulationService.Phase.COMPLETED
                || rt.simulation.get(started.id()).phase() == SimulationService.Phase.FAILED, 60_000), "simulation must finish");
        SimulationService.Report r = rt.simulation.get(started.id()).report();
        assertNotNull(r);
        assertEquals("COMPLETED", r.outcome());
        assertTrue(r.detectionMs() >= 1000, "detection time measured: " + r.detectionMs());
        assertTrue(r.failoverMs() >= 0);
        assertTrue(r.recoveryMs() > 0);
        assertTrue(r.requestsTotal() > 0);
        assertTrue(r.latencySamples() > 0, "request latency comes from retained gateway measurements");
        assertTrue(r.averageLatencyMs() >= 0 && r.p95LatencyMs() >= 0 && r.p99LatencyMs() >= 0);
        assertEquals(r.requestsTotal(), r.requestsSucceeded() + r.requestsFailed());
        assertEquals(r.requestsFailed() == 0 ? 0.0 : Math.round(10000.0 * r.requestsFailed() / r.requestsTotal()) / 100.0, r.errorRatePct(), 0.01);
        assertTrue(r.consistency().consistent(), "cluster must be consistent after recovery: " + r.consistency());
        assertFalse(r.notes().isEmpty());
        assertEquals(NodeState.ACTIVE, rt.coordinator.require("node-4").state());
        assertEquals("iPhone 17 Pro", rt.router.get("product:101").entry().value(), "normal recovery preserves the existing product entry");
        assertEquals("user-owned-value", rt.router.get("sim:key:0").entry().value(), "generated workload must not overwrite legacy sim:key entries");
    }

    @Test
    void simulationRejectsInvalidRatesAndDuplicateRuns() {
        assertThrows(InvalidRequestException.class, () -> rt.simulation.start(
                new SimulationService.StartRequest("node-0", SimulationService.Mode.ISOLATE, true, 0, 0)));
        SimulationService.RunView running = rt.simulation.start(
                new SimulationService.StartRequest("node-0", SimulationService.Mode.ISOLATE, false, 0, 0));
        assertThrows(ConflictException.class, () -> rt.simulation.start(
                new SimulationService.StartRequest("node-1", SimulationService.Mode.ISOLATE, false, 0, 0)));
        rt.simulation.stop(running.id());
        assertTrue(await(() -> rt.simulation.get(running.id()).phase() == SimulationService.Phase.CANCELLED
                || rt.simulation.get(running.id()).phase() == SimulationService.Phase.FAILED, 60_000));
    }

    @Test
    void simulationCanBeDisabled() {
        rt.config.simulationEnabled = false;
        assertThrows(ForbiddenOperationException.class, () -> rt.simulation.start(
                new SimulationService.StartRequest("node-0", SimulationService.Mode.ISOLATE, false, 0, 0)));
    }

    @Test
    void settingsAreValidatedThenAppliedWithRebalancing() {
        load("st:", 200);
        assertTrue(t.awaitConsistent(5000));
        ClusterSettings cur = rt.settings();

        assertThrows(InvalidRequestException.class, () -> rt.applySettings(new ClusterSettings(
                cur.heartbeatIntervalMs(), cur.failureTimeoutMs(), 9, cur.virtualNodes(), 0, cur.maxMemoryBytes(), "LRU", 3000)));
        assertEquals(2, rt.config.replicationFactor, "an invalid request must change nothing");
        assertThrows(InvalidRequestException.class, () -> rt.applySettings(new ClusterSettings(
                1000, 1500, 2, 64, 0, cur.maxMemoryBytes(), "LRU", 3000)));   // timeout < 3x heartbeat

        rt.applySettings(new ClusterSettings(200, 1200, 1, 32, 0, 32L << 20, "LFU", 3000));
        assertEquals(1, rt.config.replicationFactor);
        assertEquals(32, rt.coordinator.ring.virtualNodesPerNode());
        assertEquals(com.distributedcache.cache.EvictionPolicy.LFU, t.server("node-0").store().policy());
        assertTrue(t.awaitConsistent(8000));
        assertEquals(0, misses("st:", 200));
        for (int i = 0; i < 200; i++) {
            int holders = 0;
            for (var s : t.servers) if (s.store().peek("st:" + i) != null) holders++;
            assertEquals(2, holders, "replication factor 1 means exactly 2 copies after rebalancing");
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static List<String> concat(String first, List<String> rest) {
        java.util.ArrayList<String> values = new java.util.ArrayList<>();
        values.add(first); values.addAll(rest); return values;
    }
}
