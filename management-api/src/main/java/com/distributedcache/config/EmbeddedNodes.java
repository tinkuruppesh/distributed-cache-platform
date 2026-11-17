package com.distributedcache.config;

import com.distributedcache.networking.NodeAddress;
import com.distributedcache.node.NodeConfig;
import com.distributedcache.node.NodeServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dev/demo convenience: runs cache nodes inside the manager's JVM. Each is a REAL, independent TCP server with its own
 * CacheStore, gossip agent and port - the manager talks to them over TCP exactly as it would to separate containers.
 * In Docker mode (embedded-nodes = 0) nodes are separate containers instead.
 */
public class EmbeddedNodes implements AutoCloseable {
    private final Map<String, NodeServer> servers = new ConcurrentHashMap<>();
    private final List<NodeAddress> initial = new ArrayList<>();
    private final String secret;

    public EmbeddedNodes(ClusterProperties p) {
        this.secret = p.getSecret();
        List<NodeAddress> all = new ArrayList<>();
        for (int i = 0; i < p.getEmbeddedNodes(); i++)
            all.add(new NodeAddress("node-" + (i + 1), "127.0.0.1", p.getEmbeddedBasePort() + i));
        for (NodeAddress a : all) {
            start(a, all);
            initial.add(a);
        }
    }

    private void start(NodeAddress a, List<NodeAddress> seeds) {
        try {
            NodeConfig cfg = new NodeConfig(a.id(), "127.0.0.1", a.host(), a.port(), secret, 64L * 1024 * 1024,
                    com.distributedcache.cache.EvictionPolicy.LRU, seeds, 1000,
                    com.distributedcache.cache.CacheStore.DEFAULT_TOMBSTONE_RETENTION_MS);
            servers.put(a.id(), new NodeServer(cfg).start());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start embedded node " + a + ": " + e.getMessage(), e);
        }
    }

    public List<NodeAddress> initialAddresses() { return initial; }

    public boolean isEnabled() { return !initial.isEmpty(); }

    /** Starts one more embedded node (used by "Add node" in the dashboard). port <= 0 picks a free port. */
    public NodeAddress startNew(String id, int port) {
        int p = port;
        if (p <= 0) {
            try (ServerSocket s = new ServerSocket(0)) { p = s.getLocalPort(); }
            catch (IOException e) { throw new IllegalStateException("No free port available"); }
        }
        NodeAddress a = new NodeAddress(id, "127.0.0.1", p);
        List<NodeAddress> seeds = new ArrayList<>(initial);
        start(a, seeds);
        return a;
    }

    public void stop(String id) {
        NodeServer s = servers.remove(id);
        if (s != null) s.close();
    }

    @Override
    public void close() {
        for (NodeServer s : servers.values()) s.close();
        servers.clear();
    }
}
