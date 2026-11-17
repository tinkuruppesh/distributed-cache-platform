package com.distributedcache.cluster;

/** The settings the Settings page may change. */
public record ClusterSettings(long heartbeatIntervalMs, long failureTimeoutMs, int replicationFactor, int virtualNodes,
                              long defaultTtlSeconds, long maxMemoryBytes, String evictionPolicy, long monitoringRefreshMs) {}
