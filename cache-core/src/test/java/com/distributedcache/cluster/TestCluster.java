package com.distributedcache.cluster;

import com.distributedcache.networking.NodeAddress;
import com.distributedcache.node.NodeConfig;
import com.distributedcache.node.NodeServer;

import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

/** Starts N real cache nodes (real TCP sockets on free ports) plus a cluster manager with fast timeouts. */
final class TestCluster implements AutoCloseable {
    static final String SECRET = "test-secret";
    final List<NodeServer> servers = new ArrayList<>();
    final List<NodeAddress> addrs = new ArrayList<>();
    final ClusterConfig cfg = new ClusterConfig();
    final ClusterRuntime rt;

    TestCluster(int nodes) throws Exception {
        cfg.clusterSecret = SECRET;
        cfg.heartbeatIntervalMs = 200;
        cfg.failureTimeoutMs = 1200;
        cfg.suspectTimeoutMs = 400;
        cfg.pingTimeoutMs = 150;
        cfg.requestTimeoutMs = 400;
        cfg.replicationFactor = 2;
        cfg.virtualNodes = 64;
        for (int i = 0; i < nodes; i++) addrs.add(new NodeAddress("node-" + i, "127.0.0.1", freePort()));
        for (NodeAddress a : addrs) servers.add(new NodeServer(NodeConfig.of(a.id(), a.port(), SECRET, addrs)).start());
        rt = new ClusterRuntime(cfg);
        rt.bootstrap(addrs);
        rt.start();
    }

    NodeAddress newNode(String id) throws Exception {
        NodeAddress a = new NodeAddress(id, "127.0.0.1", freePort());
        servers.add(new NodeServer(NodeConfig.of(id, a.port(), SECRET, addrs)).start());
        addrs.add(a);
        return a;
    }

    NodeAddress addr(String id) { return addrs.stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow(); }

    NodeServer server(String id) { return servers.stream().filter(s -> s.config().nodeId().equals(id)).findFirst().orElseThrow(); }

    void control(String id, String cmd) { rt.coordinator.client.control(addr(id), cmd, 1000); }

    NodeState state(String id) { return rt.coordinator.require(id).state(); }

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    static boolean await(BooleanSupplier cond, long timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            try { if (cond.getAsBoolean()) return true; } catch (RuntimeException ignored) {}
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return cond.getAsBoolean();
    }

    boolean awaitConsistent(long timeoutMs) { return await(() -> rt.verifier.verify().consistent(), timeoutMs); }

    @Override
    public void close() {
        rt.close();
        for (NodeServer s : servers) s.close();
    }
}
