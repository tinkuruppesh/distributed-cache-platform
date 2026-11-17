package com.distributedcache.api.cluster;

import com.distributedcache.cluster.ClusterRuntime;
import com.distributedcache.cluster.NodeInfo;
import com.distributedcache.config.EmbeddedNodes;
import com.distributedcache.exception.InvalidRequestException;
import com.distributedcache.monitoring.MonitoringService;
import com.distributedcache.networking.NodeAddress;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cluster")
@Validated
public class ClusterController {
    public record AddNodeRequest(@NotBlank String id, String host, Integer port, boolean startEmbedded) {}

    private final ClusterRuntime rt;
    private final EmbeddedNodes embedded;

    public ClusterController(ClusterRuntime rt, EmbeddedNodes embedded) { this.rt = rt; this.embedded = embedded; }

    @GetMapping
    public Map<String, Object> cluster() {
        return Map.of("overview", rt.monitoring.overview(), "settings", rt.settings(),
                "simulationEnabled", rt.config.simulationEnabled, "embeddedNodes", embedded.isEnabled());
    }

    @GetMapping("/nodes")
    public List<MonitoringService.NodeView> nodes() { return rt.monitoring.nodes(); }

    @GetMapping("/nodes/{nodeId}")
    public Map<String, Object> node(@PathVariable("nodeId") String nodeId) {
        NodeInfo n = rt.coordinator.require(nodeId);
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("node", rt.monitoring.nodeView(n));
        result.put("rawStats", n.stats);
        result.put("recentEvents", rt.coordinator.events.recent(200).stream().filter(e -> nodeId.equals(e.nodeId())).limit(20).toList());
        result.put("heartbeatIntervalMs", rt.config.heartbeatIntervalMs);
        result.put("failureTimeoutMs", rt.config.failureTimeoutMs);
        result.put("latestRecovery", rt.recovery.latestFor(nodeId, 0).orElse(null));
        return result;
    }

    @PostMapping("/nodes")
    public ResponseEntity<MonitoringService.NodeView> addNode(@Valid @RequestBody AddNodeRequest req) {
        NodeAddress addr;
        if (req.startEmbedded()) {
            if (!embedded.isEnabled()) throw new InvalidRequestException("Embedded nodes are only available in the dev profile.");
            addr = embedded.startNew(req.id(), req.port() == null ? 0 : req.port());
        } else {
            if (req.host() == null || req.host().isBlank() || req.port() == null) throw new InvalidRequestException("host and port are required.");
            addr = new NodeAddress(req.id(), req.host(), req.port());
        }
        try {
            NodeInfo n = rt.membership.join(addr);
            return ResponseEntity.status(HttpStatus.CREATED).body(rt.monitoring.nodeView(n));
        } catch (RuntimeException e) {
            if (req.startEmbedded()) embedded.stop(req.id());   // do not leave an orphan server behind
            throw e;
        }
    }

    @DeleteMapping("/nodes/{nodeId}")
    public Map<String, String> removeNode(@PathVariable("nodeId") String nodeId) {
        rt.membership.remove(nodeId);
        embedded.stop(nodeId);
        return Map.of("message", "Node " + nodeId + " removed after its data was moved to the new owners");
    }

    @PostMapping("/nodes/{nodeId}/maintenance")
    public Map<String, String> maintenance(@PathVariable("nodeId") String nodeId) {
        rt.membership.maintenance(nodeId);
        return Map.of("message", nodeId + " drained and placed in maintenance");
    }

    @PostMapping("/nodes/{nodeId}/resume")
    public Map<String, String> resume(@PathVariable("nodeId") String nodeId) {
        rt.membership.resume(nodeId);
        return Map.of("message", nodeId + " resynchronized and active again");
    }

    @GetMapping("/hash-ring")
    public MonitoringService.RingView hashRing(@RequestParam(name = "sample", defaultValue = "60") @Min(0) @Max(500) int sample) {
        return rt.monitoring.hashRing(sample);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        var o = rt.monitoring.overview();
        return Map.of("status", o.health(), "activeNodes", o.activeNodes(), "totalNodes", o.totalNodes(),
                "failedNodes", o.failedNodes(), "suspectedNodes", o.suspectedNodes());
    }

    @GetMapping("/events")
    public List<com.distributedcache.cluster.ClusterEvent> events(@RequestParam(name = "limit", defaultValue = "50") @Min(1) @Max(500) int limit) {
        return rt.coordinator.events.recent(limit);
    }

    @GetMapping("/gossip")
    public MonitoringService.GossipOverview gossip() { return rt.monitoring.gossip(); }

    @GetMapping("/failovers")
    public List<com.distributedcache.failover.FailoverRecord> failovers() {
        var l = new java.util.ArrayList<>(rt.failover.history());
        java.util.Collections.reverse(l);
        return l;
    }
}
