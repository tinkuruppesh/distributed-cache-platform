package com.distributedcache.node;

import com.distributedcache.cache.CacheEntry;
import com.distributedcache.cache.CacheStore;
import com.distributedcache.cache.EvictionPolicy;
import com.distributedcache.networking.NodeAddress;
import com.distributedcache.networking.NodeClient;
import com.distributedcache.networking.Wire;
import com.distributedcache.util.KeyValidator;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * One independent cache node: local CacheStore + TCP command server + gossip agent.
 * Every connection is served on a Java 21 virtual thread (cheap blocking I/O, no thread pool tuning).
 *
 * Simulation support: ISOLATE makes the node "black-hole" every request (like a network partition);
 * CRASH additionally wipes memory (like a process restart); UNISOLATE brings it back.
 */
public final class NodeServer implements AutoCloseable {
    private final NodeConfig config;
    private final CacheStore store;
    private final NodeClient peerClient;
    private final GossipAgent gossip;
    private final long startedAt = System.currentTimeMillis();
    private final LongAdder requests = new LongAdder(), replWrites = new LongAdder();
    private final byte[] secretBytes;
    private ScheduledExecutorService scheduler;
    private ServerSocket server;
    private volatile boolean running;
    private volatile boolean isolated;
    private volatile long incarnation = 1;

    public NodeServer(NodeConfig config) {
        this.config = config;
        this.store = new CacheStore(config.maxBytes(), config.policy(), System::currentTimeMillis, config.tombstoneRetentionMs());
        this.peerClient = new NodeClient(config.secret(), 300);
        this.gossip = new GossipAgent(config.nodeId(), config.advertiseHost(), config.port(), peerClient,
                config.seeds(), config.gossipIntervalMs() * 3, config.gossipIntervalMs() * 8);
        this.secretBytes = config.secret().getBytes(StandardCharsets.UTF_8);
    }

    public NodeServer start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(config.bindHost(), config.port()));
        running = true;
        Thread acceptor = new Thread(this::acceptLoop, "node-" + config.nodeId() + "-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "node-" + config.nodeId() + "-bg");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(store::cleanupExpired, 1, 1, TimeUnit.SECONDS);   // active TTL cleanup
        scheduler.scheduleWithFixedDelay(() -> { if (!isolated) { try { gossip.tick(); } catch (RuntimeException ignored) {} } },
                config.gossipIntervalMs(), config.gossipIntervalMs(), TimeUnit.MILLISECONDS);
        return this;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = server.accept();
                Thread.startVirtualThread(() -> handle(s));
            } catch (IOException e) {
                if (running) { /* transient accept error */ }
            }
        }
    }

    private void handle(Socket s) {
        try (s;
             BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8))) {
            s.setTcpNoDelay(true);
            String line;
            while ((line = in.readLine()) != null) {
                String resp = dispatch(line);
                if (resp == null) continue;                       // isolated: stay silent so callers time out
                if (resp.equals("ERR unauthorized")) { out.write(resp + "\n"); out.flush(); return; }
                out.write(resp);
                out.write('\n');
                out.flush();
            }
        } catch (IOException ignored) {
            // client went away
        }
    }

    private String dispatch(String line) {
        String[] p = line.split(" ");
        if (p.length < 2 || !MessageDigest.isEqual(p[0].getBytes(StandardCharsets.UTF_8), secretBytes)) return "ERR unauthorized";
        String cmd = p[1];
        try {
            if (isolated && !cmd.equals("UNISOLATE")) return null;
            switch (cmd) {
                case "PING": return "OK " + stats();
                case "PUT": {
                    requests.increment();
                    KeyValidator.validateKey(p[2]);
                    long ttl = Long.parseLong(p[4]);
                    KeyValidator.validateTtlMs(ttl);
                    return "OK " + Wire.encode(store.put(p[2], Wire.unb64(p[3]), ttl));
                }
                case "GET": {
                    requests.increment();
                    CacheEntry e = store.get(p[2]);
                    return e == null ? "NIL" : "OK " + Wire.encode(e);
                }
                case "DEL": {
                    requests.increment();
                    CacheEntry e = store.delete(p[2]);
                    return e == null ? "NIL" : "OK " + Wire.encode(e);
                }
                case "EXPIRE": {
                    requests.increment();
                    CacheEntry e = store.expire(p[2], Long.parseLong(p[3]));
                    return e == null ? "NIL" : "OK " + Wire.encode(e);
                }
                case "TTL": requests.increment(); return "OK " + store.ttlMillis(p[2]);
                case "REPL": {
                    replWrites.increment();
                    return "OK " + (store.applyReplica(Wire.decode(p, 2)) ? "APPLIED" : "STALE");
                }
                case "DROP": store.dropLocal(p[2]); return "OK";
                case "SCAN": {
                    List<CacheEntry> all = store.snapshot();
                    StringBuilder sb = new StringBuilder("OK ").append(all.size());
                    for (CacheEntry e : all) sb.append('\n').append(Wire.encode(e));
                    return sb.toString();
                }
                case "PROBE": {
                    try { peerClient.ping(new NodeAddress("probe", p[2], Integer.parseInt(p[3])), 250); return "OK 1"; }
                    catch (RuntimeException e) { return "OK 0"; }
                }
                case "GOSSIP": gossip.merge(p.length > 2 ? p[2] : ""); return "OK " + gossip.encode();
                case "MEMBERS": return "OK " + gossip.encodeView();
                case "SETCONF": store.configure(EvictionPolicy.valueOf(p[2]), Long.parseLong(p[3])); return "OK";
                case "ISOLATE": isolated = true; return "OK";
                case "CRASH": isolated = true; store.clear(); return "OK";
                case "UNISOLATE": {
                    if (isolated) { isolated = false; incarnation++; gossip.bumpIncarnation(); }
                    return "OK";
                }
                default: return "ERR unknown command";
            }
        } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
            return "ERR bad request: " + e.getMessage();
        } catch (RuntimeException e) {
            return "ERR " + e.getMessage();
        }
    }

    private String stats() {
        return "node=" + config.nodeId()
                + " uptime=" + (System.currentTimeMillis() - startedAt)
                + " entries=" + store.size()
                + " used=" + store.usedBytes()
                + " max=" + store.maxBytes()
                + " hits=" + store.hits()
                + " misses=" + store.misses()
                + " sets=" + store.sets()
                + " deletes=" + store.deletes()
                + " expired=" + store.expiredCount()
                + " evictions=" + store.evictionCount()
                + " evictionEvents=" + store.recentEvictions().stream().map(e -> java.util.Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(e.key().getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ":" + e.time()).collect(java.util.stream.Collectors.joining(","))
                + " requests=" + requests.sum()
                + " repl=" + replWrites.sum()
                + " policy=" + store.policy()
                + " incarnation=" + incarnation;
    }

    public CacheStore store() { return store; }
    public NodeConfig config() { return config; }
    public boolean isIsolated() { return isolated; }

    @Override
    public void close() {
        running = false;
        try { if (server != null) server.close(); } catch (IOException ignored) {}
        if (scheduler != null) scheduler.shutdownNow();
        peerClient.closeAll();
    }
}
