package com.distributedcache.hashing;

/** One point on the ring. position is in [0,1) for drawing the ring in the dashboard. */
public record VirtualNode(String nodeId, int index, long hash, double position) {}
