package com.distributedcache.unit;

import com.distributedcache.hashing.ConsistentHashRing;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class ConsistentHashRingTest {
    private static ConsistentHashRing ring(int nodes, int vnodes) {
        ConsistentHashRing r = new ConsistentHashRing(vnodes);
        for (int i = 1; i <= nodes; i++) r.addNode("node-" + i);
        return r;
    }

    @Test
    void sameKeyAlwaysMapsToSameNode() {
        ConsistentHashRing r = ring(6, 100);
        String first = r.getPrimaryNode("user:42");
        for (int i = 0; i < 100; i++) assertEquals(first, r.getPrimaryNode("user:42"));
    }

    @Test
    void virtualNodesSpreadKeysEvenly() {
        ConsistentHashRing r = ring(6, 100);
        Map<String, Integer> count = new HashMap<>();
        int keys = 20000;
        for (int i = 0; i < keys; i++) count.merge(r.getPrimaryNode("key:" + i), 1, Integer::sum);
        assertEquals(6, count.size());
        for (int c : count.values()) {
            double share = 100.0 * c / keys;
            assertTrue(share > 10 && share < 25, "share out of range: " + share);
        }
    }

    @Test
    void ownershipPercentagesSumTo100() {
        ConsistentHashRing r = ring(5, 100);
        double sum = r.ownership().values().stream().mapToDouble(Double::doubleValue).sum();
        assertEquals(100.0, sum, 0.001);
    }

    @Test
    void addingNodeOnlyMovesKeysToTheNewNode() {
        ConsistentHashRing r = ring(6, 100);
        int keys = 10000;
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < keys; i++) before.put("k" + i, r.getPrimaryNode("k" + i));
        r.addNode("node-7");
        int moved = 0;
        for (int i = 0; i < keys; i++) {
            String after = r.getPrimaryNode("k" + i);
            if (!after.equals(before.get("k" + i))) {
                moved++;
                assertEquals("node-7", after, "a key may only move TO the new node");
            }
        }
        double pct = 100.0 * moved / keys;
        assertTrue(pct > 5 && pct < 25, "about 1/7 of keys should move, got " + pct + "%");
    }

    @Test
    void removingNodeOnlyMovesItsOwnKeys() {
        ConsistentHashRing r = ring(6, 100);
        Map<String, String> before = new HashMap<>();
        for (int i = 0; i < 10000; i++) before.put("k" + i, r.getPrimaryNode("k" + i));
        r.removeNode("node-3");
        for (int i = 0; i < 10000; i++) {
            String b = before.get("k" + i), a = r.getPrimaryNode("k" + i);
            if (!b.equals("node-3")) assertEquals(b, a, "keys of other nodes must not move");
            else assertNotEquals("node-3", a);
        }
    }

    @Test
    void firstReplicaBecomesPrimaryWhenPrimaryIsRemoved() {
        ConsistentHashRing r = ring(6, 100);
        for (int i = 0; i < 2000; i++) {
            String key = "k" + i;
            List<String> pref = r.preferenceList(key, 3);
            ConsistentHashRing copy = r.copy();
            copy.removeNode(pref.get(0));
            assertEquals(pref.get(1), copy.getPrimaryNode(key), "replica promotion == ring successor");
        }
    }

    @Test
    void preferenceListHasDistinctPhysicalNodes() {
        ConsistentHashRing r = ring(4, 50);
        for (int i = 0; i < 500; i++) {
            List<String> l = r.preferenceList("k" + i, 3);
            assertEquals(3, l.size());
            assertEquals(3, new HashSet<>(l).size());
            assertEquals(l.subList(1, 3), r.getReplicaNodes("k" + i, 2));
        }
        assertEquals(2, ring(2, 10).preferenceList("x", 5).size(), "cannot return more nodes than exist");
    }

    @Test
    void affectedKeysAreExactlyThoseWhoseOwnersChange() {
        ConsistentHashRing r = ring(5, 100);
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 3000; i++) keys.add("k" + i);
        List<String> affected = r.getAffectedKeys(keys, "node-6", true, 3);
        assertFalse(affected.isEmpty());
        assertTrue(affected.size() < keys.size());
        ConsistentHashRing after = r.copy();
        after.addNode("node-6");
        for (String k : keys) {
            boolean changed = !r.preferenceList(k, 3).equals(after.preferenceList(k, 3));
            assertEquals(changed, affected.contains(k));
        }
    }

    @Test
    void reconfigureKeepsMembersWithNewVirtualNodeCount() {
        ConsistentHashRing r = ring(3, 100);
        r.reconfigure(20);
        assertEquals(60, r.virtualNodes().size());
        assertEquals(3, r.size());
    }

    @Test
    void emptyRingReturnsNoOwner() {
        assertNull(new ConsistentHashRing(10).getPrimaryNode("k"));
    }
}
