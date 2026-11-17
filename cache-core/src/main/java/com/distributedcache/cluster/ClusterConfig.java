package com.distributedcache.cluster;

import com.distributedcache.cache.EvictionPolicy;

/** Runtime-tunable cluster configuration (fields are volatile so settings changes apply immediately). */
public final class ClusterConfig {
    public volatile String clusterSecret = "change-me-cluster-secret";
    public volatile int replicationFactor = 2;          // number of REPLICAS (copies = 1 + replicationFactor)
    public volatile int virtualNodes = 100;
    public volatile long heartbeatIntervalMs = 1000;
    public volatile long failureTimeoutMs = 5000;
    public volatile long suspectTimeoutMs = 2000;
    public volatile int pingTimeoutMs = 500;
    public volatile int requestTimeoutMs = 800;
    public volatile int scanTimeoutMs = 5000;
    public volatile long defaultTtlSeconds = 0;         // 0 = no expiry
    public volatile long maxMemoryBytes = 64L * 1024 * 1024;
    public volatile EvictionPolicy evictionPolicy = EvictionPolicy.LRU;
    public volatile boolean syncReplication = false;    // false = asynchronous replication (default)
    public volatile boolean simulationEnabled = true;   // failure simulation is demo-only
    public volatile int maxValueChars = 256 * 1024;
    public volatile long monitoringRefreshMs = 3000;    // used by the dashboard
}
