package com.distributedcache.simulation;

import com.distributedcache.cluster.ClusterCoordinator;
import com.distributedcache.cluster.ClusterEvent;
import com.distributedcache.cluster.NodeInfo;
import com.distributedcache.cluster.NodeState;
import com.distributedcache.cache.CacheEntry;
import com.distributedcache.exception.*;
import com.distributedcache.failover.FailoverManager;
import com.distributedcache.failover.FailoverRecord;
import com.distributedcache.monitoring.MetricsRecorder;
import com.distributedcache.recovery.ConsistencyVerifier;
import com.distributedcache.recovery.ConsistencyVerifier.ConsistencyReport;
import com.distributedcache.recovery.RecoveryManager;
import com.distributedcache.recovery.RecoveryManager.RecoveryRecord;
import com.distributedcache.replication.ReplicationManager;
import com.distributedcache.routing.CacheRouter;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Controlled failure simulation (demo environments only; disabled by config otherwise).
 * It never kills processes or touches Docker: it sends an authenticated ISOLATE/CRASH command to the node, which then
 * stops answering exactly like a partitioned/crashed machine. Everything that follows (suspicion, confirmation,
 * failover, recovery) is performed by the REAL failure-handling code, and the report is computed from measurements.
 */
public final class SimulationService implements AutoCloseable {
    public enum Mode { ISOLATE, CRASH }
    public enum Phase { INJECTING, DETECTING, FAILOVER, AWAITING_RESTART, RECOVERING, COMPLETED, FAILED, CANCELLED }

    public record StartRequest(String nodeId, Mode mode, boolean generateTraffic, int trafficRps, int autoRestartAfterSec) {}
    public record SimEvent(long time, String type, String message) {}
    public record AffectedKey(String key, long preFailureVersion, String originalPrimary, List<String> originalReplicas,
                              String newPrimary, List<String> currentReplicas, int verifiedCopies,
                              int expectedCopies, boolean copiesVerified) {}

    public record Report(String simulationId, String failedNode, String mode, String outcome,
                         long failureStartedAt, long failureDetectedAt, long failoverStartedAt, long failoverCompletedAt,
                         long restartedAt, long recoveryStartedAt, long recoveryCompletedAt,
                         Long detectionMs, Long failoverMs, Long recoveryMs, Long syncMs,
                         Long requestsTotal, Long requestsSucceeded, Long requestsFailed, Double errorRatePct,
                         long requestsServedByFallback, int keysPromoted, int reReplicated,
                         long replicationSent, long replicationAcked, long replicationFailed,
                         double averageLatencyMs, double p95LatencyMs, double p99LatencyMs, long latencySamples,
                         double requestsPerSecond, List<AffectedKey> affectedKeys,
                         boolean recoverySucceeded, String finalNodeStatus,
                         ConsistencyReport consistency, List<String> notes) {}

    public record RunView(String id, String nodeId, Mode mode, Phase phase, long startedAt, long endedAt,
                          long autoRestartAt,
                          boolean generateTraffic, List<SimEvent> events, Report report) {}

    private final class Run {
        final String id = UUID.randomUUID().toString().substring(0, 8);
        final StartRequest req;
        final long startedAt = System.currentTimeMillis();
        volatile long endedAt;
        volatile Phase phase = Phase.INJECTING;
        volatile long failureStartedAt, restartedAt;
        volatile boolean restartRequested;
        volatile long autoRestartAt;
        volatile TrafficGenerator traffic;
        volatile List<AffectedKey> affectedKeys = List.of();
        volatile Report report;
        final List<SimEvent> events = new CopyOnWriteArrayList<>();
        Run(StartRequest r) { req = r; }
        void event(String type, String msg) {
            events.add(new SimEvent(System.currentTimeMillis(), type, msg));
            publish(this);
        }
        RunView view() {
            return new RunView(id, req.nodeId(), req.mode(), phase, startedAt, endedAt, autoRestartAt, req.generateTraffic(),
                    new ArrayList<>(events), report);
        }
    }

    private final ClusterCoordinator c;
    private final CacheRouter router;
    private final MetricsRecorder metrics;
    private final ReplicationManager replication;
    private final FailoverManager failover;
    private final RecoveryManager recovery;
    private final ConsistencyVerifier verifier;
    private final ConcurrentHashMap<String, Run> runs = new ConcurrentHashMap<>();
    private final AtomicReference<Run> active = new AtomicReference<>();
    private final ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
    private final CopyOnWriteArrayList<Consumer<RunView>> listeners = new CopyOnWriteArrayList<>();

    public SimulationService(ClusterCoordinator c, CacheRouter router, MetricsRecorder metrics, ReplicationManager replication,
                             FailoverManager failover, RecoveryManager recovery, ConsistencyVerifier verifier) {
        this.c = c; this.router = router; this.metrics = metrics; this.replication = replication;
        this.failover = failover; this.recovery = recovery; this.verifier = verifier;
        c.events.addListener(this::onClusterEvent);
    }

    public void addListener(Consumer<RunView> l) { listeners.add(l); }

    private void publish(Run run) {
        RunView snapshot = run.view();
        for (Consumer<RunView> listener : listeners) {
            try { listener.accept(snapshot); }
            catch (RuntimeException ignored) { /* persistence/observer failures must not break the failure workflow */ }
        }
    }

    private void onClusterEvent(ClusterEvent e) {
        Run r = active.get();
        if (r == null || e.type().startsWith("SIM_")) return;
        if (e.nodeId() == null || e.nodeId().equals(r.req.nodeId())) r.event(e.type(), e.message());
    }

    // ------------------------------------------------------------------ API

    public RunView start(StartRequest req) {
        if (!c.config.simulationEnabled)
            throw new ForbiddenOperationException("Failure simulation is disabled. Enable it only in an isolated demo environment.");
        if (req.nodeId() == null || req.mode() == null) throw new InvalidRequestException("nodeId and mode are required.");
        NodeInfo node = c.require(req.nodeId());
        if (node.state() != NodeState.ACTIVE) throw new ConflictException("Node must be ACTIVE to simulate a failure (is " + node.state() + ").");
        if (c.ring.size() < 2) throw new ConflictException("Need at least 2 active nodes.");
        long activeNodes = c.allNodes().stream().filter(n -> n.state() == NodeState.ACTIVE).count();
        if (activeNodes < 3) throw new ConflictException("At least 3 ACTIVE nodes are required to preserve the cluster majority during failover.");
        if (req.trafficRps() < 0 || req.trafficRps() > 500) throw new InvalidRequestException("trafficRps must be 0-500.");
        if (req.autoRestartAfterSec() < 0 || req.autoRestartAfterSec() > 1800) throw new InvalidRequestException("autoRestartAfterSec must be 0-1800.");
        if (req.generateTraffic() && req.trafficRps() < 1) throw new InvalidRequestException("trafficRps must be at least 1 when traffic generation is enabled.");
        Run run = new Run(req);
        if (!active.compareAndSet(null, run)) throw new ConflictException("Another simulation is already running.");
        runs.put(run.id, run);
        publish(run); // persist the run before asynchronous failure injection begins
        exec.submit(() -> {
            try { execute(run, node); }
            catch (RuntimeException e) {
                run.event("SIM_ERROR", String.valueOf(e.getMessage()));
                run.phase = Phase.FAILED;
                run.endedAt = System.currentTimeMillis();
            } finally {
                active.compareAndSet(run, null);
                publish(run);
            }
        });
        return run.view();
    }

    public RunView restartNode(String id) {
        Run r = require(id);
        if (r.phase != Phase.AWAITING_RESTART) throw new ConflictException("Simulation is not waiting for a restart (phase " + r.phase + ").");
        r.restartRequested = true;
        if (r.traffic != null) r.traffic.stop();
        return r.view();
    }

    /** Stops generated traffic and requests safe recovery; it never leaves a simulated node isolated. */
    public RunView stop(String id) {
        Run r = require(id);
        if (r.phase == Phase.COMPLETED || r.phase == Phase.FAILED || r.phase == Phase.CANCELLED)
            throw new ConflictException("Simulation is already finished.");
        r.restartRequested = true;
        if (r.traffic != null) r.traffic.stop();
        r.event("SIM_STOP_REQUESTED", "Stop requested; generated traffic will stop and the node will be safely restored.");
        return r.view();
    }

    public RunView get(String id) { return require(id).view(); }

    public List<RunView> list() {
        List<Run> l = new ArrayList<>(runs.values());
        l.sort((a, b) -> Long.compare(b.startedAt, a.startedAt));
        List<RunView> out = new ArrayList<>();
        for (Run r : l) out.add(r.view());
        return out;
    }

    private Run require(String id) {
        Run r = runs.get(id);
        if (r == null) throw new NotFoundException("Unknown simulation: " + id);
        return r;
    }

    // ------------------------------------------------------------------ the scripted scenario

    private void execute(Run run, NodeInfo node) {
        StartRequest req = run.req;
        List<String> notes = new ArrayList<>();
        TrafficGenerator traffic = null;
        boolean injected = false;
        try {
            try { run.affectedKeys = snapshotPrimaryKeys(node); }
            catch (RuntimeException e) { notes.add("Affected-key snapshot was unavailable before failure injection: " + e.getMessage()); }
            if (req.generateTraffic() && req.trafficRps() > 0) {
                traffic = new TrafficGenerator(router, req.trafficRps(), run.id);
                run.traffic = traffic;
                traffic.seed();
            }
            MetricsRecorder.Counters c0 = metrics.counters();
            ReplicationManager.Stats r0 = replication.stats();
            if (traffic != null) {
                traffic.start();
                run.event("SIM_TRAFFIC_STARTED", "Generating ~" + req.trafficRps() + " requests/s through the gateway (80% reads / 20% writes).");
                sleep(1000);   // let a baseline build up
            }

            // 1. inject the failure
            run.failureStartedAt = System.currentTimeMillis();
            run.phase = Phase.INJECTING;
            c.client.control(node.address(), req.mode() == Mode.CRASH ? "CRASH" : "ISOLATE", 2000);
            injected = true;
            run.event("SIM_FAILURE_INJECTED", node.id + " is now " + (req.mode() == Mode.CRASH ? "crashed (memory wiped)" : "isolated (network partition)") + ".");
            c.events.record("SIM_FAILURE_INJECTED", node.id, "WARNING", "Simulation " + run.id + ": failure injected into " + node.id + ".");

            // 2. wait for the real detector + failover to finish
            run.phase = Phase.DETECTING;
            long deadline = Math.max(60_000, c.config.failureTimeoutMs * 4);
            boolean failedOver = waitUntil(() -> {
                if (node.state() == NodeState.FAILED) run.phase = Phase.FAILOVER;
                return failover.latestFor(node.id, run.failureStartedAt - 1).isPresent();
            }, deadline);
            if (!failedOver) {
                notes.add("The cluster did not complete a failover within " + deadline + "ms (it may have been blocked by the quorum safeguard).");
                run.event("SIM_DETECTION_TIMEOUT", "No failover completed in time - restoring the node.");
                safeUnisolate(node);
                finish(run, c0, r0, notes, null, null, "FAILOVER_NOT_COMPLETED", traffic);
                return;
            }
            FailoverRecord fo = failover.latestFor(node.id, run.failureStartedAt - 1).get();
            run.affectedKeys = verifyAffectedKeys(run.affectedKeys);
            run.event("SIM_FAILOVER_DONE", "Failure detected after " + (fo.detectedAt() - run.failureStartedAt) + "ms; routing updated "
                    + (fo.failoverCompletedAt() - fo.failoverStartedAt()) + "ms after failover start.");
            run.phase = Phase.AWAITING_RESTART;
            run.event("SIM_AWAITING_RESTART", "Traffic continues on the healthy nodes. Restart the node when ready.");

            // 3. wait for the operator (or auto-restart), then bring the node back
            run.autoRestartAt = req.autoRestartAfterSec() > 0 ? System.currentTimeMillis() + req.autoRestartAfterSec() * 1000L : 0;
            if (run.autoRestartAt == 0) {
                while (!run.restartRequested) sleep(100);
            } else {
                waitUntil(() -> run.restartRequested || System.currentTimeMillis() >= run.autoRestartAt, 30 * 60_000L);
            }
            boolean cancelled = run.restartRequested;
            run.restartedAt = System.currentTimeMillis();
            c.client.control(node.address(), "UNISOLATE", 3000);
            injected = false;
            run.phase = Phase.RECOVERING;
            run.event("SIM_NODE_RESTARTED", node.id + " restarted - waiting for the cluster to detect and re-synchronize it.");
            c.events.record("SIM_NODE_RESTARTED", node.id, "INFO", "Simulation " + run.id + ": " + node.id + " restarted.");

            boolean recovered = waitUntil(() -> node.state() == NodeState.ACTIVE
                    && recovery.latestFor(node.id, run.restartedAt - 1).filter(RecoveryRecord::success).isPresent(), 120_000);
            RecoveryRecord rr = recovery.latestFor(node.id, run.restartedAt - 1).orElse(null);
            if (!recovered) notes.add("The node did not return to ACTIVE within 120s.");
            finish(run, c0, r0, notes, fo, rr, cancelled && recovered ? "CANCELLED" : recovered ? "COMPLETED" : "RECOVERY_NOT_COMPLETED", traffic);
        } finally {
            if (traffic != null) traffic.stop();
            if (injected) safeUnisolate(node);
        }
    }

    private void finish(Run run, MetricsRecorder.Counters c0, ReplicationManager.Stats r0, List<String> notes,
                        FailoverRecord fo, RecoveryRecord rr, String outcome, TrafficGenerator traffic) {
        if (traffic != null) traffic.stop();
        sleep(1500);   // let in-flight asynchronous replication settle before measuring
        MetricsRecorder.Counters c1 = metrics.counters();
        ReplicationManager.Stats r1 = replication.stats();
        MetricsRecorder.LatencyStats latency = metrics.latencySince(run.startedAt);
        long total = c1.total() - c0.total(), ok = c1.success() - c0.success(), bad = c1.failed() - c0.failed();
        long fb = c1.fallbacks() - c0.fallbacks();
        ConsistencyReport cons = verifier.verify();
        NodeInfo recoveredNode = c.node(run.req.nodeId());
        String finalNodeStatus = recoveredNode == null ? "UNKNOWN" : recoveredNode.state().name();
        boolean recoverySucceeded = rr != null && rr.success() && NodeState.ACTIVE.name().equals(finalNodeStatus);
        Double err = errorRatePct(bad, total);

        if (bad > 0) notes.add(bad + " request(s) failed during the simulation window.");
        if (fb > 0) notes.add(fb + " request(s) succeeded only after falling back to another replica (higher latency).");
        if (run.req.mode() == Mode.CRASH) notes.add("CRASH mode wiped the node's memory; its data was rebuilt from the surviving replicas.");
        notes.add("Replication is asynchronous: a write acknowledged by the failed node but not yet copied to a replica can be lost. "
                + "This report measures consistency AFTER recovery; it does not claim zero data loss.");
        if (!cons.consistent()) notes.add("Consistency check found " + cons.missingCopies() + " missing and " + cons.staleCopies() + " stale copies.");

        long fs = run.failureStartedAt;
        run.report = new Report(run.id, run.req.nodeId(), run.req.mode().name(), outcome, fs,
                fo == null ? 0 : fo.detectedAt(), fo == null ? 0 : fo.failoverStartedAt(), fo == null ? 0 : fo.failoverCompletedAt(),
                run.restartedAt, rr == null ? 0 : rr.startedAt(), rr == null ? 0 : rr.completedAt(),
                fo == null ? null : fo.detectedAt() - fs,
                fo == null ? null : fo.failoverCompletedAt() - fo.failoverStartedAt(),
                rr == null ? null : rr.completedAt() - run.restartedAt,
                rr == null ? null : rr.completedAt() - rr.startedAt(),
                total, ok, bad, err, fb,
                fo == null ? 0 : fo.keysPromoted(), fo == null ? 0 : fo.reReplicated(),
                r1.sent() - r0.sent(), r1.acked() - r0.acked(), r1.failed() - r0.failed(),
                latency.samples() == 0 ? 0 : latency.avgMs(), latency.samples() == 0 ? 0 : latency.p95Ms(),
                latency.samples() == 0 ? 0 : latency.p99Ms(), latency.samples(), total / Math.max(0.001, (System.currentTimeMillis() - run.startedAt) / 1000.0),
                run.affectedKeys, recoverySucceeded, finalNodeStatus, cons, notes);
        run.phase = "COMPLETED".equals(outcome) ? Phase.COMPLETED : "CANCELLED".equals(outcome) ? Phase.CANCELLED : Phase.FAILED;
        run.endedAt = System.currentTimeMillis();
        run.event("SIM_REPORT_READY", "Report generated: outcome " + outcome + ", error rate "
                + (run.report.errorRatePct() == null ? "unavailable" : run.report.errorRatePct() + "%") + ".");
    }

    static Double errorRatePct(long failedRequests, long totalRequests) {
        if (totalRequests <= 0) return null;
        return Math.round(100.0 * failedRequests / totalRequests * 100.0) / 100.0;
    }

    private List<AffectedKey> snapshotPrimaryKeys(NodeInfo node) {
        List<AffectedKey> affected = new ArrayList<>();
        for (CacheEntry entry : c.client.scan(node.address(), c.config.scanTimeoutMs)) {
            if (entry.deleted() || entry.isExpired(System.currentTimeMillis())) continue;
            List<String> owners = c.ring.preferenceList(entry.key(), c.copies());
            if (owners.isEmpty() || !node.id.equals(owners.getFirst())) continue;
            affected.add(new AffectedKey(entry.key(), entry.version(), node.id, List.copyOf(owners.subList(1, owners.size())),
                    null, List.of(), 0, 0, false));
        }
        return List.copyOf(affected);
    }

    /** Reads actual copies directly from current ring owners; these inspections do not route through/cache-count as client GETs. */
    private List<AffectedKey> verifyAffectedKeys(List<AffectedKey> before) {
        List<AffectedKey> verified = new ArrayList<>(before.size());
        for (AffectedKey key : before) {
            CacheRouter.Placement placement = router.locate(key.key());
            Map<String, CacheEntry> copies = new LinkedHashMap<>();
            CacheEntry newest = null;
            for (String ownerId : placement.states().keySet()) {
                NodeInfo owner = c.node(ownerId);
                if (owner == null || owner.state() != NodeState.ACTIVE) continue;
                try {
                    CacheEntry copy = c.client.get(owner.address(), key.key(), c.config.requestTimeoutMs);
                    if (copy != null && !copy.deleted() && !copy.isExpired(System.currentTimeMillis())) {
                        copies.put(ownerId, copy);
                        if (copy.isNewerThan(newest)) newest = copy;
                    }
                } catch (RuntimeException ignored) { /* unavailable copies are reflected in the measured count */ }
            }
            int matching = 0;
            if (newest != null) for (CacheEntry copy : copies.values()) {
                if (copy.version() == newest.version() && copy.updatedAt() == newest.updatedAt()
                        && Objects.equals(copy.value(), newest.value())) matching++;
            }
            List<String> currentReplicas = placement.replicas();
            int expected = placement.primary() == null ? 0 : 1 + currentReplicas.size();
            verified.add(new AffectedKey(key.key(), key.preFailureVersion(), key.originalPrimary(), key.originalReplicas(), placement.primary(),
                    currentReplicas, matching, expected, expected > 0 && matching == expected
                    && newest != null && newest.version() >= key.preFailureVersion()));
        }
        return List.copyOf(verified);
    }

    private void safeUnisolate(NodeInfo node) {
        try { c.client.control(node.address(), "UNISOLATE", 3000); } catch (RuntimeException ignored) {}
    }

    private static boolean waitUntil(BooleanSupplier cond, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) return true;
            sleep(100);
        }
        return cond.getAsBoolean();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    @Override public void close() { exec.shutdownNow(); }
}
