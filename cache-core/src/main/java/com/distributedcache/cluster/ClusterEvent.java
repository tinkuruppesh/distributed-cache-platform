package com.distributedcache.cluster;

public record ClusterEvent(long id, long time, String type, String nodeId, String severity, String message) {}
