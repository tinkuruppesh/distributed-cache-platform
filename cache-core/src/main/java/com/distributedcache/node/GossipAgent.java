package com.distributedcache.node;

import com.distributedcache.exception.CacheException;
import com.distributedcache.networking.NodeAddress;
import com.distributedcache.networking.NodeClient;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Simplified gossip membership (heartbeat-counter style).
 * - Every tick the node bumps its own heartbeat counter and pushes its member table to a few random peers.
 * - Merge rule: higher incarnation wins, otherwise higher heartbeat counter wins.
 * - Status (ALIVE/SUSPECT/DEAD) is derived LOCALLY from "how long since this member's counter last increased".
 * Limits: no indirect probing, no anti-entropy of suspicion state, convergence time grows ~log(N) rounds.
 */
public final class GossipAgent {
    public record Member(String id, String host, int port, long heartbeat, long incarnation, long localUpdatedAt) {}

    private final String selfId, selfHost;
    private final int selfPort;
    private final NodeClient client;
    private final long suspectMs, deadMs;
    private final Map<String, Member> table = new ConcurrentHashMap<>();
    private volatile long incarnation = 1;
    private volatile long heartbeat = 0;

    public GossipAgent(String selfId, String selfHost, int selfPort, NodeClient client, List<NodeAddress> seeds,
                       long suspectMs, long deadMs) {
        this.selfId = selfId; this.selfHost = selfHost; this.selfPort = selfPort;
        this.client = client; this.suspectMs = suspectMs; this.deadMs = deadMs;
        long now = System.currentTimeMillis();
        table.put(selfId, new Member(selfId, selfHost, selfPort, 0, 1, now));
        for (NodeAddress s : seeds) if (!s.id().equals(selfId)) table.put(s.id(), new Member(s.id(), s.host(), s.port(), 0, 0, now));
    }

    /** One gossip round (called by the node's scheduler). */
    public void tick() {
        long now = System.currentTimeMillis();
        heartbeat++;
        table.put(selfId, new Member(selfId, selfHost, selfPort, heartbeat, incarnation, now));
        List<Member> peers = new ArrayList<>();
        for (Member m : table.values()) if (!m.id().equals(selfId)) peers.add(m);
        if (peers.isEmpty()) return;
        Collections.shuffle(peers, ThreadLocalRandom.current());
        String payload = encode();
        for (Member p : peers.subList(0, Math.min(2, peers.size()))) {
            try {
                String reply = client.gossip(new NodeAddress(p.id(), p.host(), p.port()), payload, 300);
                merge(reply);
            } catch (CacheException ignored) { /* unreachable peer: its counter simply stops increasing */ }
        }
    }

    /** Called when this node "restarts" so peers accept it as a fresh life. */
    public void bumpIncarnation() { incarnation++; }

    public void merge(String payload) {
        if (payload == null || payload.isBlank()) return;
        long now = System.currentTimeMillis();
        for (String part : payload.split(";")) {
            String[] f = part.split(",");
            if (f.length < 5 || f[0].equals(selfId)) continue;
            Member in = new Member(f[0], f[1], Integer.parseInt(f[2]), Long.parseLong(f[3]), Long.parseLong(f[4]), now);
            table.merge(in.id(), in, (cur, inc) ->
                    (inc.incarnation() > cur.incarnation() || (inc.incarnation() == cur.incarnation() && inc.heartbeat() > cur.heartbeat()))
                            ? inc : cur);
        }
    }

    public String encode() {
        StringBuilder sb = new StringBuilder();
        for (Member m : table.values()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(m.id()).append(',').append(m.host()).append(',').append(m.port()).append(',')
              .append(m.heartbeat()).append(',').append(m.incarnation());
        }
        return sb.toString();
    }

    /** Local view with derived status, e.g. for the dashboard: id,host,port,heartbeat,incarnation,status,ageMs;... */
    public String encodeView() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (Member m : table.values()) {
            long age = now - m.localUpdatedAt();
            String status = m.id().equals(selfId) || age < suspectMs ? "ALIVE" : age < deadMs ? "SUSPECT" : "DEAD";
            if (sb.length() > 0) sb.append(';');
            sb.append(m.id()).append(',').append(m.host()).append(',').append(m.port()).append(',')
              .append(m.heartbeat()).append(',').append(m.incarnation()).append(',').append(status).append(',').append(age);
        }
        return sb.toString();
    }
}
