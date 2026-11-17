package com.distributedcache.node;

import com.distributedcache.cache.EvictionPolicy;
import com.distributedcache.networking.NodeAddress;

import java.util.*;

/** Configuration of one standalone cache node. */
public record NodeConfig(String nodeId, String bindHost, String advertiseHost, int port, String secret,
                         long maxBytes, EvictionPolicy policy, List<NodeAddress> seeds,
                         long gossipIntervalMs, long tombstoneRetentionMs) {

    public static NodeConfig of(String id, int port, String secret, List<NodeAddress> seeds) {
        return new NodeConfig(id, "0.0.0.0", "127.0.0.1", port, secret, 64L * 1024 * 1024, EvictionPolicy.LRU,
                seeds, 1000, com.distributedcache.cache.CacheStore.DEFAULT_TOMBSTONE_RETENTION_MS);
    }

    /** Reads NODE_ID, NODE_HOST, NODE_PORT, CLUSTER_SECRET, MAX_BYTES, EVICTION, PEERS (id=host:port,id=host:port). */
    public static NodeConfig fromEnv(Map<String, String> env) {
        String id = env.getOrDefault("NODE_ID", "node-1");
        String host = env.getOrDefault("NODE_HOST", "127.0.0.1");
        int port = Integer.parseInt(env.getOrDefault("NODE_PORT", "7001"));
        String secret = env.getOrDefault("CLUSTER_SECRET", "change-me-cluster-secret");
        long max = Long.parseLong(env.getOrDefault("MAX_BYTES", String.valueOf(64L * 1024 * 1024)));
        EvictionPolicy pol = EvictionPolicy.valueOf(env.getOrDefault("EVICTION", "LRU").toUpperCase());
        List<NodeAddress> seeds = new ArrayList<>();
        String peers = env.getOrDefault("PEERS", "");
        for (String p : peers.split(",")) {
            p = p.trim();
            if (p.isEmpty()) continue;
            String[] idRest = p.split("=");
            String[] hp = idRest[1].split(":");
            seeds.add(new NodeAddress(idRest[0], hp[0], Integer.parseInt(hp[1])));
        }
        return new NodeConfig(id, "0.0.0.0", host, port, secret, max, pol, seeds, 1000,
                com.distributedcache.cache.CacheStore.DEFAULT_TOMBSTONE_RETENTION_MS);
    }
}
