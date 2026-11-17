package com.distributedcache.api.monitoring;

import com.distributedcache.cluster.ClusterEvent;
import com.distributedcache.cluster.ClusterRuntime;
import com.distributedcache.monitoring.MetricsRecorder;
import com.distributedcache.monitoring.MonitoringService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.regex.Pattern;

/** Every number here is measured: gateway counters, node heartbeats, replication acks. Nothing is hard-coded. */
@RestController
@RequestMapping("/api/v1/monitoring")
@Validated
public class MonitoringController {
    private static final Pattern SENSITIVE_KEY = Pattern.compile("(?i).*(token|secret|password|passwd|session|auth|credential|apikey).*");
    public record CacheOperationView(long time, String operation, String result, String node, String key, Double latencyMs) {}
    private final ClusterRuntime rt;

    public MonitoringController(ClusterRuntime rt) { this.rt = rt; }

    @GetMapping("/overview")
    public MonitoringService.Overview overview() { return rt.monitoring.overview(); }

    @GetMapping("/traffic")
    public List<MetricsRecorder.TimePoint> traffic(@RequestParam(name = "seconds", defaultValue = "60") @Min(5) @Max(3600) int seconds) {
        return rt.metrics.timeline(seconds);
    }

    @GetMapping("/cache-performance")
    public Map<String, Object> cachePerformance(@RequestParam(name = "seconds", defaultValue = "60") @Min(5) @Max(3600) int seconds) {
        var o = rt.monitoring.overview();
        return Map.of("hitRatioPct", o.hitRatioPct(), "missRatioPct", o.missRatioPct(), "latency", o.latency(),
                "counters", rt.metrics.counters(), "hotKeys", rt.metrics.hotKeys(10), "hotKeyTracking", rt.metrics.hotKeyStats(),
                "timeline", rt.metrics.timeline(seconds),
                "replication", rt.replication.stats(),
                "replicationMode", rt.config.syncReplication ? "SYNCHRONOUS" : "ASYNCHRONOUS");
    }

    @GetMapping("/memory")
    public Map<String, Object> memory(@RequestParam(name = "points", defaultValue = "150") @Min(5) @Max(300) int points) {
        return Map.of("nodes", rt.monitoring.nodes(), "history", rt.monitoring.history(points));
    }

    @GetMapping("/replication")
    public MonitoringService.ReplicationView replication() { return rt.monitoring.replicationView(); }

    @GetMapping("/events")
    public List<ClusterEvent> events(@RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(500) int limit) {
        return rt.coordinator.events.recent(limit);
    }

    @GetMapping("/cache-operations")
    public List<CacheOperationView> cacheOperations(@RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(500) int limit) {
        List<CacheOperationView> events = new ArrayList<>();
        for (MetricsRecorder.OperationEvent e : rt.metrics.recentOperations(500)) {
            events.add(new CacheOperationView(e.time(), e.operation(), e.result(), e.node(), maskKey(e.key()), e.latencyMs()));
        }
        for (var sample : rt.replication.lagSamples(600)) {
            events.add(new CacheOperationView(sample.time(), "REPLICATION", sample.applied() ? "APPLIED" : "STALE",
                    sample.node(), maskKey(sample.key()), null));
        }
        for (var failure : rt.replication.recentFailures(100)) {
            events.add(new CacheOperationView(failure.time(), "REPLICATION", "FAILED: " + failure.error(),
                    failure.node(), maskKey(failure.key()), null));
        }
        for (MonitoringService.NodeView node : rt.monitoring.nodes()) {
            for (MonitoringService.EvictionView eviction : node.recentEvictions()) {
                events.add(new CacheOperationView(eviction.time(), "EVICT", "EVICTED", node.id(), maskKey(eviction.key()), null));
            }
        }
        events.sort(Comparator.comparingLong(CacheOperationView::time).reversed());
        return events.size() <= limit ? events : new ArrayList<>(events.subList(0, limit));
    }

    private static String maskKey(String key) {
        return key != null && SENSITIVE_KEY.matcher(key).matches() ? "[masked]" : key;
    }
}
