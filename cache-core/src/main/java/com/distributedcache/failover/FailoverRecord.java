package com.distributedcache.failover;

public record FailoverRecord(String id, String nodeId, long lastHeartbeatAt, long detectedAt, long failoverStartedAt,
                             long failoverCompletedAt, int keysPromoted, int reReplicated, int reReplicationFailures,
                             long reReplicationMs, int reachableNodes, int memberCount, String trigger) {}
