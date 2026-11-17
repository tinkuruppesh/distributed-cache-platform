package com.distributedcache.cluster;

import com.distributedcache.cache.CacheEntry;
import com.distributedcache.exception.CacheException;
import com.distributedcache.exception.NodeUnavailableException;
import com.distributedcache.networking.NodeAddress;
import com.distributedcache.networking.NodeClient;
import com.distributedcache.node.NodeConfig;
import com.distributedcache.node.NodeServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.distributedcache.cluster.TestCluster.await;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the TCP node protocol directly (no cluster manager). */
public class NodeProtocolTest {
    private NodeServer server;
    private NodeAddress addr;
    private NodeClient client;

    @BeforeEach
    void setUp() throws Exception {
        addr = new NodeAddress("solo", "127.0.0.1", TestCluster.freePort());
        server = new NodeServer(NodeConfig.of("solo", addr.port(), "s3cret", List.of())).start();
        client = new NodeClient("s3cret", 500);
    }

    @AfterEach
    void tearDown() {
        client.closeAll();
        server.close();
    }

    @Test
    void putGetDeleteTtlAndExpireOverTcp() {
        CacheEntry e = client.put(addr, "user:101", "Hello World\nwith spaces", 0, 1000);
        assertEquals("Hello World\nwith spaces", client.get(addr, "user:101", 1000).value());
        assertEquals(e.version(), client.get(addr, "user:101", 1000).version());
        assertEquals(-1, client.ttl(addr, "user:101", 1000));
        assertNotNull(client.expire(addr, "user:101", 60_000, 1000));
        long ttl = client.ttl(addr, "user:101", 1000);
        assertTrue(ttl > 0 && ttl <= 60_000);
        assertNotNull(client.delete(addr, "user:101", 1000));
        assertNull(client.get(addr, "user:101", 1000));
        assertNull(client.delete(addr, "user:101", 1000));
    }

    @Test
    void replicaWriteWithOlderVersionIsRejected() {
        CacheEntry newer = new CacheEntry("k", "new", 1, 0, System.currentTimeMillis() + 10_000, 1, false);
        CacheEntry older = new CacheEntry("k", "old", 1, 0, System.currentTimeMillis(), 1, false);
        assertTrue(client.repl(addr, newer, 1000));
        assertFalse(client.repl(addr, older, 1000));
        assertEquals("new", client.get(addr, "k", 1000).value());
    }

    @Test
    void scanReturnsAllEntriesIncludingTombstones() {
        client.put(addr, "a", "1", 0, 1000);
        client.put(addr, "b", "2", 0, 1000);
        client.delete(addr, "b", 1000);
        List<CacheEntry> all = client.scan(addr, 1000);
        assertEquals(2, all.size());
        assertTrue(all.stream().anyMatch(CacheEntry::deleted));
    }

    @Test
    void wrongSecretIsRejected() {
        NodeClient bad = new NodeClient("wrong", 500);
        try {
            CacheException ex = assertThrows(CacheException.class, () -> bad.ping(addr, 1000));
            assertTrue(ex.getMessage().contains("unauthorized") || ex.getMessage().contains("unavailable"));
        } finally { bad.closeAll(); }
    }

    @Test
    void invalidKeyIsRejectedByTheNode() {
        assertTrue(client.call(addr, "PUT bad/key aGk= 0", 1000).startsWith("ERR"));
        assertNull(client.get(addr, "bad", 1000));
    }

    @Test
    void isolatedNodeBlackHolesRequestsAndRecovers() {
        client.put(addr, "k", "v", 0, 1000);
        client.control(addr, "ISOLATE", 1000);
        assertThrows(NodeUnavailableException.class, () -> client.ping(addr, 250));
        client.control(addr, "UNISOLATE", 1000);
        assertEquals("v", client.get(addr, "k", 1000).value(), "ISOLATE keeps data (partition), UNISOLATE restores service");
    }

    @Test
    void crashWipesMemory() {
        client.put(addr, "k", "v", 0, 1000);
        client.control(addr, "CRASH", 1000);
        client.control(addr, "UNISOLATE", 1000);
        assertNull(client.get(addr, "k", 1000));
    }

    @Test
    void gossipSpreadsMembershipToAllNodes() throws Exception {
        List<NodeServer> nodes = new ArrayList<>();
        List<NodeAddress> addrs = new ArrayList<>();
        try {
            NodeAddress seed = new NodeAddress("g0", "127.0.0.1", TestCluster.freePort());
            addrs.add(seed);
            nodes.add(new NodeServer(fast("g0", seed.port(), List.of())).start());
            for (int i = 1; i <= 3; i++) {
                NodeAddress a = new NodeAddress("g" + i, "127.0.0.1", TestCluster.freePort());
                addrs.add(a);
                nodes.add(new NodeServer(fast(a.id(), a.port(), List.of(seed))).start());   // only know the seed
            }
            boolean converged = await(() -> {
                for (NodeAddress a : addrs) {
                    String view = client.members(a, 500);
                    for (NodeAddress other : addrs) {
                        if (!view.contains(other.id() + ",127.0.0.1," + other.port() + ",")) return false;
                    }
                    if (view.contains("DEAD") || view.contains("SUSPECT")) return false;
                }
                return true;
            }, 10_000);
            assertTrue(converged, "every node must learn every other node through gossip");
        } finally { for (NodeServer n : nodes) n.close(); }
    }

    private static NodeConfig fast(String id, int port, List<NodeAddress> seeds) {
        return new NodeConfig(id, "127.0.0.1", "127.0.0.1", port, "s3cret", 1L << 20,
                com.distributedcache.cache.EvictionPolicy.LRU, seeds, 100, 60_000);
    }
}
