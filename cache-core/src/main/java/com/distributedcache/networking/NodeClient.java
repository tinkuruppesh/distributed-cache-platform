package com.distributedcache.networking;

import com.distributedcache.cache.CacheEntry;
import com.distributedcache.exception.CacheException;
import com.distributedcache.exception.NodeUnavailableException;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP client for node-to-node and gateway-to-node calls, with a tiny per-node connection pool.
 * Every call has a hard timeout: a black-holed node must turn into NodeUnavailableException, never a hang.
 */
public final class NodeClient {
    private static final int MAX_POOLED = 8;

    /** Bounded connection pool with O(1) size tracking; ConcurrentLinkedQueue.size() traverses the queue. */
    private static final class ConnectionPool {
        private final ConcurrentLinkedQueue<Conn> connections = new ConcurrentLinkedQueue<>();
        private final AtomicInteger size = new AtomicInteger();

        Conn poll() {
            Conn conn = connections.poll();
            if (conn != null) size.decrementAndGet();
            return conn;
        }

        boolean offer(Conn conn) {
            if (size.incrementAndGet() <= MAX_POOLED) {
                connections.offer(conn);
                return true;
            }
            size.decrementAndGet();
            return false;
        }

        Conn pollForClose() { return poll(); }
    }

    private interface ReaderFn<T> { T read(BufferedReader in) throws IOException; }

    private static final class Conn {
        final Socket socket; final BufferedReader in; final BufferedWriter out;
        Conn(Socket s) throws IOException {
            socket = s;
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        }
    }

    private final String secret;
    private final int connectTimeoutMs;
    private final ConcurrentHashMap<String, ConnectionPool> pools = new ConcurrentHashMap<>();

    public NodeClient(String secret, int connectTimeoutMs) {
        this.secret = secret;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    // ------------------------------------------------------------------ low level

    private <T> T exchange(NodeAddress a, String line, int timeoutMs, ReaderFn<T> fn) {
        String poolKey = a.host() + ":" + a.port();
        ConnectionPool pool = pools.computeIfAbsent(poolKey, k -> new ConnectionPool());
        Conn c = pool.poll();
        boolean pooled = c != null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Socket newSocket = null;
            try {
                if (c == null) {
                    newSocket = new Socket();
                    newSocket.connect(new InetSocketAddress(a.host(), a.port()), connectTimeoutMs);
                    newSocket.setTcpNoDelay(true);
                    c = new Conn(newSocket);
                    newSocket = null; // the connection now owns the socket
                }
                c.socket.setSoTimeout(timeoutMs);
                c.out.write(secret + " " + line + "\n");
                c.out.flush();
                T result = fn.read(c.in);
                if (!pool.offer(c)) close(c);
                return result;
            } catch (SocketTimeoutException e) {
                close(c);
                close(newSocket);
                throw new NodeUnavailableException(a.toString(), "timeout after " + timeoutMs + "ms");
            } catch (IOException e) {
                close(c);
                close(newSocket);
                c = null;
                if (attempt == 0 && (pooled || isSocketOptionFailure(e))) {
                    pooled = false;
                    continue;
                }   // Retry a stale pooled socket or transient Windows socket-option failure once.
                throw new NodeUnavailableException(a.toString(), e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }
        throw new NodeUnavailableException(a.toString(), "connection failed");
    }

    private static void close(Conn c) {
        if (c == null) return;
        try { c.socket.close(); } catch (IOException ignored) {}
    }

    private static void close(Socket socket) {
        if (socket == null) return;
        try { socket.close(); } catch (IOException ignored) {}
    }

    private static boolean isSocketOptionFailure(IOException e) {
        if (!(e instanceof SocketException) || e.getMessage() == null) return false;
        String message = e.getMessage().toLowerCase(Locale.ROOT);
        return message.contains("setsocketopt") || message.contains("socket option");
    }

    private static String readLine(BufferedReader in) throws IOException {
        String l = in.readLine();
        if (l == null) throw new IOException("connection closed by peer");
        return l;
    }

    /** Sends one line, returns the single response line. */
    public String call(NodeAddress a, String line, int timeoutMs) {
        return exchange(a, line, timeoutMs, NodeClient::readLine);
    }

    private static String ok(NodeAddress a, String resp) {
        if (resp.startsWith("OK")) return resp.length() > 3 ? resp.substring(3) : "";
        if (resp.equals("NIL")) return null;
        throw new CacheException("Node " + a + " replied: " + resp);
    }

    // ------------------------------------------------------------------ typed commands

    /** Heartbeat: returns the node's health counters (uptime, entries, memory, ...). */
    public Map<String, String> ping(NodeAddress a, int timeoutMs) {
        String r = call(a, "PING", timeoutMs);
        String body = ok(a, r);
        return Wire.parseKv(body == null ? "" : body);
    }

    public CacheEntry put(NodeAddress a, String key, String value, long ttlMs, int timeoutMs) {
        return decodeEntry(a, call(a, "PUT " + key + " " + Wire.b64(value) + " " + ttlMs, timeoutMs));
    }

    public CacheEntry get(NodeAddress a, String key, int timeoutMs) {
        return decodeEntry(a, call(a, "GET " + key, timeoutMs));
    }

    public CacheEntry delete(NodeAddress a, String key, int timeoutMs) {
        return decodeEntry(a, call(a, "DEL " + key, timeoutMs));
    }

    public CacheEntry expire(NodeAddress a, String key, long ttlMs, int timeoutMs) {
        return decodeEntry(a, call(a, "EXPIRE " + key + " " + ttlMs, timeoutMs));
    }

    public long ttl(NodeAddress a, String key, int timeoutMs) {
        return Long.parseLong(ok(a, call(a, "TTL " + key, timeoutMs)));
    }

    /** Replica write. Returns true if applied, false if the replica already had a newer/equal version. */
    public boolean repl(NodeAddress a, CacheEntry e, int timeoutMs) {
        return "APPLIED".equals(ok(a, call(a, "REPL " + Wire.encode(e), timeoutMs)));
    }

    public void drop(NodeAddress a, String key, int timeoutMs) { ok(a, call(a, "DROP " + key, timeoutMs)); }

    public List<CacheEntry> scan(NodeAddress a, int timeoutMs) {
        return exchange(a, "SCAN", timeoutMs, in -> {
            String head = readLine(in);
            if (!head.startsWith("OK")) throw new IOException("SCAN failed: " + head);
            int n = Integer.parseInt(head.substring(3).trim());
            List<CacheEntry> out = new ArrayList<>(n);
            for (int i = 0; i < n; i++) out.add(Wire.decode(readLine(in).split(" "), 0));
            return out;
        });
    }

    /** Asks node `a` whether it can reach host:port (used for quorum confirmation of failures). */
    public boolean probe(NodeAddress a, String host, int port, int timeoutMs) {
        return "1".equals(ok(a, call(a, "PROBE " + host + " " + port, timeoutMs)));
    }

    /** Admin/simulation commands: ISOLATE, CRASH, UNISOLATE, SETCONF <policy> <maxBytes>. */
    public String control(NodeAddress a, String command, int timeoutMs) {
        String r = ok(a, call(a, command, timeoutMs));
        return r == null ? "" : r;
    }

    public String gossip(NodeAddress a, String payload, int timeoutMs) {
        String r = ok(a, call(a, "GOSSIP " + payload, timeoutMs));
        return r == null ? "" : r;
    }

    public String members(NodeAddress a, int timeoutMs) {
        String r = ok(a, call(a, "MEMBERS", timeoutMs));
        return r == null ? "" : r;
    }

    private CacheEntry decodeEntry(NodeAddress a, String resp) {
        String body = ok(a, resp);
        return body == null ? null : Wire.decode(body.split(" "), 0);
    }

    public void closeAll() {
        for (ConnectionPool pool : pools.values()) { Conn c; while ((c = pool.pollForClose()) != null) close(c); }
    }
}
