package com.distributedcache.observability;

import com.distributedcache.cluster.ClusterRuntime;
import com.distributedcache.cluster.NodeInfo;
import com.distributedcache.cluster.NodeState;
import io.micrometer.core.instrument.*;
import io.micrometer.core.instrument.binder.MeterBinder;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Exposes the engine's REAL measurements to Prometheus through Micrometer. The React dashboard reads the very same
 * numbers from our REST API (both come from MetricsRecorder / node heartbeats), so there is one source of truth.
 */
@Component
public class ClusterMetricsBinder implements MeterBinder {
    private final ClusterRuntime rt;
    private final ScheduledExecutorService refresher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "metrics-refresh");
        t.setDaemon(true);
        return t;
    });

    public ClusterMetricsBinder(ClusterRuntime rt) { this.rt = rt; }

    @Override
    public void bindTo(MeterRegistry registry) {
        FunctionCounter.builder("cache.requests", rt.metrics, m -> m.counters().total()).description("Gateway requests").register(registry);
        FunctionCounter.builder("cache.requests.failed", rt.metrics, m -> m.counters().failed()).description("Failed gateway requests").register(registry);
        FunctionCounter.builder("cache.requests.get", rt.metrics, m -> m.counters().gets()).description("Gateway GET operations").register(registry);
        FunctionCounter.builder("cache.requests.set", rt.metrics, m -> m.counters().sets()).description("Gateway SET operations").register(registry);
        FunctionCounter.builder("cache.requests.update", rt.metrics, m -> m.counters().updates()).description("Gateway UPDATE operations").register(registry);
        FunctionCounter.builder("cache.requests.delete", rt.metrics, m -> m.counters().deletes()).description("Gateway DELETE operations").register(registry);
        FunctionCounter.builder("cache.hits", rt.metrics, m -> m.counters().hits()).description("Cache hits").register(registry);
        FunctionCounter.builder("cache.misses", rt.metrics, m -> m.counters().misses()).description("Cache misses").register(registry);
        FunctionCounter.builder("cache.fallbacks", rt.metrics, m -> m.counters().fallbacks()).description("Requests served after replica fallback").register(registry);
        FunctionCounter.builder("cache.replication.sent", rt.replication, r -> r.stats().sent()).register(registry);
        FunctionCounter.builder("cache.replication.acked", rt.replication, r -> r.stats().acked()).description("Replica responses received (APPLIED or STALE)").register(registry);
        FunctionCounter.builder("cache.replication.applied", rt.replication, r -> r.stats().applied()).description("Replica writes applied").register(registry);
        FunctionCounter.builder("cache.replication.stale", rt.replication, r -> r.stats().stale()).description("Replica writes rejected as stale").register(registry);
        FunctionCounter.builder("cache.replication.already.current", rt.replication, r -> r.stats().alreadyCurrent())
                .description("Replica responses where an equal or newer copy already existed").register(registry);
        FunctionCounter.builder("cache.replication.failed", rt.replication, r -> r.stats().failed()).description("Replication failures").register(registry);
        FunctionCounter.builder("cluster.failovers", rt.failover, f -> f.failoverCount()).description("Completed failovers").register(registry);

        Gauge.builder("cache.latency.p95.ms", rt.metrics, m -> m.latency().p95Ms()).register(registry);
        Gauge.builder("cache.latency.p99.ms", rt.metrics, m -> m.latency().p99Ms()).register(registry);
        Gauge.builder("cache.latency.avg.ms", rt.metrics, m -> m.latency().avgMs()).register(registry);
        Gauge.builder("cache.latency.p50.ms", rt.metrics, m -> m.latency().p50Ms()).register(registry);
        Gauge.builder("cache.requests.per.second", rt.metrics, m -> m.requestsPerSecond(5)).register(registry);
        Gauge.builder("cache.replication.lag.p95.ms", rt.replication, r -> r.stats().p95LagMs()).description("P95 send-to-replica-response operation latency in milliseconds").register(registry);
        Gauge.builder("cache.replication.operation.latency.avg.ms", rt.replication, r -> r.stats().avgLatencyMs()).description("Average recent send-to-replica-response operation latency in milliseconds").register(registry);
        Gauge.builder("cache.replication.operation.latency.p95.ms", rt.replication, r -> r.stats().p95LatencyMs()).description("P95 recent send-to-replica-response operation latency in milliseconds").register(registry);
        Gauge.builder("cluster.nodes.active", rt.coordinator, c -> c.allNodes().stream().filter(n -> n.state() == NodeState.ACTIVE).count()).register(registry);
        Gauge.builder("cluster.nodes.total", rt.coordinator, c -> c.allNodes().size()).register(registry);

        Timer failover = Timer.builder("cluster.failover.duration").description("Failover start -> routing updated").register(registry);
        Timer detection = Timer.builder("cluster.failure.detection.duration").description("Last heartbeat -> declared FAILED").register(registry);
        Timer recovery = Timer.builder("cluster.recovery.duration").description("Recovery start -> ACTIVE").register(registry);
        rt.failover.addListener(r -> {
            failover.record(Duration.ofMillis(Math.max(0, r.failoverCompletedAt() - r.failoverStartedAt())));
            detection.record(Duration.ofMillis(Math.max(0, r.detectedAt() - r.lastHeartbeatAt())));
        });
        rt.recovery.addListener(r -> { if (r.success()) recovery.record(Duration.ofMillis(Math.max(0, r.completedAt() - r.startedAt()))); });

        MultiGauge up = MultiGauge.builder("cluster.node.up").description("1 if the node is ACTIVE").register(registry);
        MultiGauge mem = MultiGauge.builder("cluster.node.memory.used.bytes").register(registry);
        MultiGauge entries = MultiGauge.builder("cluster.node.entries").register(registry);
        MultiGauge evictions = MultiGauge.builder("cluster.node.evictions").register(registry);
        MultiGauge gatewayRequests = MultiGauge.builder("cluster.node.gateway.requests").description("Gateway requests served by each node").register(registry);
        MultiGauge replicationWrites = MultiGauge.builder("cluster.node.replication.writes").description("Replica writes received by each node").register(registry);
        refresher.scheduleWithFixedDelay(() -> {
            try {
                List<MultiGauge.Row<?>> r1 = new ArrayList<>(), r2 = new ArrayList<>(), r3 = new ArrayList<>(), r4 = new ArrayList<>(), r5 = new ArrayList<>(), r6 = new ArrayList<>();
                var requestsByNode = rt.metrics.requestsPerNode();
                for (NodeInfo n : rt.coordinator.allNodes()) {
                    Tags t = Tags.of("node", n.id);
                    r1.add(MultiGauge.Row.of(t, n.state() == NodeState.ACTIVE ? 1 : 0));
                    r2.add(MultiGauge.Row.of(t, n.stat("used")));
                    r3.add(MultiGauge.Row.of(t, n.stat("entries")));
                    r4.add(MultiGauge.Row.of(t, n.stat("evictions")));
                    r5.add(MultiGauge.Row.of(t, requestsByNode.getOrDefault(n.id, 0L)));
                    r6.add(MultiGauge.Row.of(t, n.stat("repl")));
                }
                up.register(r1, true); mem.register(r2, true); entries.register(r3, true); evictions.register(r4, true);
                gatewayRequests.register(r5, true); replicationWrites.register(r6, true);
            } catch (RuntimeException ignored) {}
        }, 1, 2, TimeUnit.SECONDS);
    }

    @PreDestroy
    void stop() { refresher.shutdownNow(); }
}
