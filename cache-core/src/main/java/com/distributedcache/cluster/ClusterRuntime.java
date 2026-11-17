package com.distributedcache.cluster;

import com.distributedcache.advisor.*;
import com.distributedcache.cache.EvictionPolicy;
import com.distributedcache.exception.CacheException;
import com.distributedcache.exception.InvalidRequestException;
import com.distributedcache.failover.FailoverManager;
import com.distributedcache.failure.HeartbeatService;
import com.distributedcache.monitoring.MetricsRecorder;
import com.distributedcache.monitoring.MonitoringService;
import com.distributedcache.networking.NodeAddress;
import com.distributedcache.hashing.ConsistentHashRing;
import com.distributedcache.recovery.ConsistencyVerifier;
import com.distributedcache.recovery.RebalanceService;
import com.distributedcache.recovery.RecoveryManager;
import com.distributedcache.replication.ReplicationManager;
import com.distributedcache.routing.CacheRouter;
import com.distributedcache.simulation.SimulationService;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Composition root of the pure-Java cluster manager: creates and wires every component. */
public final class ClusterRuntime implements AutoCloseable {
    private static final System.Logger LOG = System.getLogger(ClusterRuntime.class.getName());
    public final ClusterConfig config;
    public final ClusterCoordinator coordinator;
    public final MetricsRecorder metrics = new MetricsRecorder();
    public final ReplicationManager replication;
    public final CacheRouter router;
    public final RebalanceService rebalance;
    public final ConsistencyVerifier verifier;
    public final FailoverManager failover;
    public final RecoveryManager recovery;
    public final MembershipManager membership;
    public final HeartbeatService heartbeat;
    public final MonitoringService monitoring;
    public final RecommendationStore recommendations = new RecommendationStore();
    public final RecommendationEngine advisor;
    public final SimulationService simulation;
    private final ScheduledExecutorService advisorScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "advisor");
        t.setDaemon(true);
        return t;
    });

    public ClusterRuntime(ClusterConfig config) {
        this.config = config;
        this.coordinator = new ClusterCoordinator(config);
        this.replication = new ReplicationManager(coordinator);
        this.router = new CacheRouter(coordinator, replication, metrics);
        this.rebalance = new RebalanceService(coordinator);
        this.verifier = new ConsistencyVerifier(coordinator);
        this.failover = new FailoverManager(coordinator, rebalance);
        this.recovery = new RecoveryManager(coordinator, rebalance);
        this.membership = new MembershipManager(coordinator, rebalance, recovery);
        this.heartbeat = new HeartbeatService(coordinator, failover, recovery);
        this.monitoring = new MonitoringService(coordinator, metrics, replication, failover, router);
        this.advisor = new RecommendationEngine(BuiltInRules.all(), recommendations);
        this.simulation = new SimulationService(coordinator, router, metrics, replication, failover, recovery, verifier);
    }

    /**
     * Registers the configured nodes: reachable ones become ACTIVE and enter the ring, unreachable ones start as FAILED
     * (the heartbeat service will recover them when they appear). Then reconciles, so a manager restart
     * re-establishes full replication of data that survived in the nodes.
     */
    public void bootstrap(List<NodeAddress> initial) {
        coordinator.topologyLock.lock();
        try {
            for (NodeAddress a : initial) {
                NodeInfo n = coordinator.register(a);
                boolean up = false;
                for (int attempt = 0; attempt < 10 && !up; attempt++) {
                    try {
                        n.stats = coordinator.client.ping(a, config.pingTimeoutMs * 2);
                        up = true;
                    } catch (CacheException e) {
                        try { Thread.sleep(300); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    }
                }
                if (up) {
                    coordinator.ring.addNode(a.id());
                    n.setState(NodeState.ACTIVE);
                    n.lastHeartbeatAt = System.currentTimeMillis();
                    coordinator.events.record("NODE_JOINED", a.id(), "INFO", a.id() + " is ACTIVE.");
                } else {
                    n.setState(NodeState.FAILED);
                    coordinator.events.record("NODE_UNREACHABLE", a.id(), "WARNING", a.id() + " not reachable at startup.");
                }
            }
            pushNodeConfig();
            if (coordinator.ring.size() > 0) rebalance.reconcileLiveRing();
        } finally { coordinator.topologyLock.unlock(); }
    }

    public void start() {
        heartbeat.start();
        monitoring.start();
        advisorScheduler.scheduleWithFixedDelay(this::runAdvisor, 15, 30, TimeUnit.SECONDS);
    }

    public AdvisorRunResult runAdvisor() {
        try {
            return advisor.run(monitoring.advisorSnapshot());
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.ERROR, "Advisor analysis could not collect its measurement snapshot", e);
            String message = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
            return new AdvisorRunResult(false, 0, 0, List.of(), message);
        }
    }

    private void pushNodeConfig() {
        for (NodeInfo n : coordinator.allNodes()) {
            try {
                coordinator.client.control(n.address(), "SETCONF " + config.evictionPolicy + " " + config.maxMemoryBytes, 1000);
            } catch (CacheException ignored) {}
        }
    }

    // ------------------------------------------------------------------ settings

    public ClusterSettings settings() {
        return new ClusterSettings(config.heartbeatIntervalMs, config.failureTimeoutMs, config.replicationFactor,
                config.virtualNodes, config.defaultTtlSeconds, config.maxMemoryBytes, config.evictionPolicy.name(),
                config.monitoringRefreshMs);
    }

    /** Validates first, applies second: an invalid request changes nothing. */
    public synchronized ClusterSettings applySettings(ClusterSettings s) {
        if (s.heartbeatIntervalMs() < 200 || s.heartbeatIntervalMs() > 10_000) throw new InvalidRequestException("Heartbeat interval must be 200-10000 ms.");
        if (s.failureTimeoutMs() < 3 * s.heartbeatIntervalMs() || s.failureTimeoutMs() > 120_000)
            throw new InvalidRequestException("Failure timeout must be at least 3x the heartbeat interval and at most 120000 ms.");
        int members = (int) coordinator.allNodes().stream().filter(n -> n.state() != NodeState.REMOVED).count();
        if (s.replicationFactor() < 0 || s.replicationFactor() > 5 || s.replicationFactor() + 1 > Math.max(1, members))
            throw new InvalidRequestException("Replication factor must be 0-5 and smaller than the number of nodes (" + members + ").");
        if (s.virtualNodes() < 10 || s.virtualNodes() > 1000) throw new InvalidRequestException("Virtual nodes must be 10-1000.");
        if (s.defaultTtlSeconds() < 0 || s.defaultTtlSeconds() > 30L * 24 * 3600) throw new InvalidRequestException("Default TTL must be 0-30 days.");
        if (s.maxMemoryBytes() < 1_048_576L) throw new InvalidRequestException("Memory limit must be at least 1 MB.");
        if (s.monitoringRefreshMs() < 1000 || s.monitoringRefreshMs() > 60_000) throw new InvalidRequestException("Refresh interval must be 1000-60000 ms.");
        EvictionPolicy policy;
        try { policy = EvictionPolicy.valueOf(s.evictionPolicy().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new InvalidRequestException("Eviction strategy must be LRU or LFU."); }

        boolean rfChanged = s.replicationFactor() != config.replicationFactor;
        boolean vnChanged = s.virtualNodes() != config.virtualNodes;
        config.heartbeatIntervalMs = s.heartbeatIntervalMs();
        config.failureTimeoutMs = s.failureTimeoutMs();
        config.suspectTimeoutMs = 2 * s.heartbeatIntervalMs();
        config.defaultTtlSeconds = s.defaultTtlSeconds();
        config.monitoringRefreshMs = s.monitoringRefreshMs();
        config.maxMemoryBytes = s.maxMemoryBytes();
        config.evictionPolicy = policy;
        config.replicationFactor = s.replicationFactor();
        pushNodeConfig();
        coordinator.events.record("SETTINGS_CHANGED", null, "INFO", "Cluster settings updated.");

        if (vnChanged || rfChanged) {
            coordinator.topologyLock.lock();
            try {
                if (vnChanged) {
                    ConsistentHashRing candidate = coordinator.ring.copyWithVirtualNodes(s.virtualNodes());
                    rebalance.reconcile(candidate, coordinator.ringMembers(), null, null);
                    coordinator.ring.reconfigure(s.virtualNodes());
                    config.virtualNodes = s.virtualNodes();
                }
                rebalance.reconcileLiveRing();
            } finally { coordinator.topologyLock.unlock(); }
        }
        return settings();
    }

    @Override
    public void close() {
        heartbeat.close(); monitoring.close(); simulation.close(); failover.close(); recovery.close(); replication.close();
        advisorScheduler.shutdownNow();
        coordinator.client.closeAll();
    }
}
