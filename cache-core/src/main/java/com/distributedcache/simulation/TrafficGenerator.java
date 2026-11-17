package com.distributedcache.simulation;

import com.distributedcache.exception.CacheException;
import com.distributedcache.routing.CacheRouter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;

/** Generates real client traffic (80% GET / 20% SET) through the gateway so the simulation has real requests to measure. */
final class TrafficGenerator {
    private static final int KEYS = 200, WORKERS = 8;
    private static final long GENERATED_ENTRY_TTL_MS = 10 * 60_000L;
    private final CacheRouter router;
    private final int rps;
    private final String keyPrefix;
    private final List<String> keys = new ArrayList<>();
    private volatile boolean running;
    private final List<Thread> threads = new ArrayList<>();

    TrafficGenerator(CacheRouter router, int rps, String runId) {
        this.router = router; this.rps = Math.max(1, rps); this.keyPrefix = "sim:" + runId + ":";
    }

    void seed() {
        try { router.searchKeys("", 200).forEach(entry -> keys.add(entry.key())); }
        catch (RuntimeException ignored) { /* the workload can still use its uniquely named entries */ }
        for (int i = 0; i < KEYS; i++) {
            String key = keyPrefix + i;
            try {
                router.set(key, "generated simulation workload", GENERATED_ENTRY_TTL_MS);
                keys.add(key);
            } catch (CacheException ignored) { /* router records the real failed request */ }
        }
    }

    void start() {
        running = true;
        long intervalNanos = 1_000_000_000L * WORKERS / rps;
        for (int w = 0; w < WORKERS; w++) {
            Thread t = Thread.ofVirtual().name("sim-traffic-" + w).unstarted(() -> loop(intervalNanos));
            threads.add(t);
            t.start();
        }
    }

    private void loop(long intervalNanos) {
        long next = System.nanoTime();
        while (running) {
            ThreadLocalRandom r = ThreadLocalRandom.current();
            if (keys.isEmpty()) { LockSupport.parkNanos(intervalNanos); continue; }
            String key = keys.get(r.nextInt(keys.size()));
            try {
                if (r.nextDouble() < 0.8) router.get(key);
                else {
                    String generatedKey = keyPrefix + r.nextInt(KEYS);
                    router.set(generatedKey, "generated simulation workload", GENERATED_ENTRY_TTL_MS);
                }
            } catch (CacheException ignored) { /* failures are already counted by the router */ }
            next += intervalNanos;
            long wait = next - System.nanoTime();
            if (wait > 0) LockSupport.parkNanos(wait); else next = System.nanoTime();
        }
    }

    void stop() {
        running = false;
        for (Thread t : threads) t.interrupt();
        for (Thread t : threads) {
            try { t.join(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        threads.clear();
    }
}
