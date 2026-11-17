package com.distributedcache.unit;

import com.distributedcache.cache.CacheEntry;
import com.distributedcache.cache.CacheStore;
import com.distributedcache.cache.EvictionPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

public class CacheStoreTest {
    private AtomicLong now;
    private CacheStore store;

    @BeforeEach
    void setUp() {
        now = new AtomicLong(1_000_000);
        store = new CacheStore(0, EvictionPolicy.LRU, now::get, 1000);
    }

    @Test
    void setAndGetReturnsValueAndVersion() {
        CacheEntry e = store.put("user:101", "Ruppesh", 0);
        assertEquals("Ruppesh", store.get("user:101").value());
        assertTrue(e.version() > 0);
        assertEquals(-1, store.ttlMillis("user:101"));
    }

    @Test
    void versionIncreasesOnEveryWrite() {
        long v1 = store.put("k", "a", 0).version();
        long v2 = store.put("k", "b", 0).version();
        assertTrue(v2 > v1);
    }

    @Test
    void entryExpiresAfterTtl() {
        store.put("session:1", "ABC", 600);
        assertEquals(600, store.ttlMillis("session:1"));
        now.addAndGet(599);
        assertNotNull(store.get("session:1"));
        now.addAndGet(2);
        assertNull(store.get("session:1"));      // lazy expiration
        assertEquals(-2, store.ttlMillis("session:1"));
    }

    @Test
    void scheduledCleanupRemovesExpiredEntries() {
        store.put("a", "1", 100);
        store.put("b", "2", 0);
        now.addAndGet(500);
        assertEquals(1, store.cleanupExpired());
        assertEquals(1, store.size());
        assertEquals(1, store.expiredCount());
    }

    @Test
    void expireChangesTtlOfLiveKey() {
        store.put("k", "v", 0);
        assertNotNull(store.expire("k", 1000));
        assertEquals(1000, store.ttlMillis("k"));
        assertNull(store.expire("missing", 1000));
    }

    @Test
    void deleteWritesTombstoneThatBlocksStaleResurrection() {
        CacheEntry live = store.put("k", "v", 0);
        CacheEntry tomb = store.delete("k");
        assertNull(store.get("k"));
        assertTrue(tomb.deleted());
        assertTrue(tomb.version() > live.version());
        assertFalse(store.applyReplica(live), "an older live copy must not overwrite the tombstone");
        assertNull(store.get("k"));
    }

    @Test
    void replicaWriteIsAppliedOnlyIfNewer() {
        CacheEntry newer = new CacheEntry("k", "new", 1, 0, 200, 1, false);
        CacheEntry older = new CacheEntry("k", "old", 1, 0, 100, 1, false);
        assertTrue(store.applyReplica(newer));
        assertFalse(store.applyReplica(older));
        assertFalse(store.applyReplica(newer), "identical version is not applied twice");
        assertEquals("new", store.get("k").value());
    }

    @Test
    void lruEvictsLeastRecentlyUsedFirst() {
        AtomicLong t = new AtomicLong(1000);
        CacheStore s = new CacheStore(540, EvictionPolicy.LRU, t::incrementAndGet, 1000);
        for (int i = 0; i < 5; i++) s.put("k" + i, "xxxxxxxxxxxxxxxxxxxx", 0);   // 108 bytes each
        assertEquals(0, s.evictionCount());
        s.get("k0");                                                              // k0 becomes most recently used
        s.put("k5", "xxxxxxxxxxxxxxxxxxxx", 0);                                   // over the limit
        assertTrue(s.evictionCount() >= 1);
        assertNotNull(s.peek("k0"), "recently used key must survive");
        assertNull(s.peek("k1"), "least recently used key must be evicted");
        assertTrue(s.usedBytes() <= 540);
        assertFalse(s.recentEvictedKeys().isEmpty());
        assertFalse(s.recentEvictions().isEmpty());
        assertTrue(s.recentEvictions().stream().allMatch(e -> e.time() > 0 && !e.key().isBlank()));
    }

    @Test
    void lfuEvictsLeastFrequentlyUsedFirst() {
        AtomicLong t = new AtomicLong(1000);
        CacheStore s = new CacheStore(540, EvictionPolicy.LFU, t::incrementAndGet, 1000);
        for (int i = 0; i < 5; i++) s.put("k" + i, "xxxxxxxxxxxxxxxxxxxx", 0);
        for (int i = 0; i < 4; i++) s.get("k" + i);          // k4 never read
        for (int j = 0; j < 5; j++) s.get("k1");              // k1 is the hot key
        s.put("k5", "xxxxxxxxxxxxxxxxxxxx", 0);
        assertNotNull(s.peek("k1"), "most frequently used key must survive");
        assertNull(s.peek("k4"), "never-read key must be evicted first");
    }

    @Test
    void concurrentWritersNeverCorruptCountersOrLoseKeys() throws Exception {
        CacheStore s = new CacheStore(0, EvictionPolicy.LRU);
        int threads = 8, perThread = 2000;
        List<Thread> ts = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int id = t;
            Thread th = new Thread(() -> {
                for (int i = 0; i < perThread; i++) s.put("k" + (i % 500), "v" + id, 0);
            });
            ts.add(th);
            th.start();
        }
        for (Thread th : ts) th.join();
        assertEquals(500, s.size());
        assertEquals((long) threads * perThread, s.sets());
        long sum = 0;
        for (CacheEntry e : s.snapshot()) sum += e.approxBytes();
        assertEquals(sum, s.usedBytes(), "byte accounting must stay exact under concurrency");
    }
}
