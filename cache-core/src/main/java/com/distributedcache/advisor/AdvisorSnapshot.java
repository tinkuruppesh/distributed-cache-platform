package com.distributedcache.advisor;

import com.distributedcache.monitoring.MetricsRecorder.HotKey;

import java.util.List;

/** Measured inputs of the rule engine - every number comes from real gateway / node / replication measurements. */
public record AdvisorSnapshot(List<NodeMetrics> nodes, long totalRequests, long hits, long misses,
                              double p95LatencyMs, double p99LatencyMs, int latencySamples,
                              List<HotKey> hotKeys,
                              long replicationAcked, long replicationFailed, double replicationP95LagMs,
                              long setsWithTtl, long setsNoTtl,
                              int activeNodes, int totalNodes, int replicationFactor,
                              long heartbeatIntervalMs, long failureTimeoutMs,
                              long readAccesses, long trackedReadAccesses, int trackedDistinctKeys,
                              int keyTrackingLimit, boolean keyTrackingLimitReached) {
    public record NodeMetrics(String id, String state, long usedBytes, long maxBytes, long entries,
                              long gatewayRequests, long evictions) {}
}
