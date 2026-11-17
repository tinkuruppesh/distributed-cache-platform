package com.distributedcache.node;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Entry point for running a node as its own process/container: java -jar cache-core-1.0.0.jar (config via env). */
public final class CacheNodeMain {
    public static void main(String[] args) throws Exception {
        Map<String, String> env = new HashMap<>(System.getenv());
        for (String a : args) {                       // optional --KEY=value overrides
            if (a.startsWith("--") && a.contains("=")) env.put(a.substring(2, a.indexOf('=')), a.substring(a.indexOf('=') + 1));
        }
        NodeConfig cfg = NodeConfig.fromEnv(env);
        NodeServer node = new NodeServer(cfg).start();
        Runtime.getRuntime().addShutdownHook(new Thread(node::close));
        System.out.println("Cache node " + cfg.nodeId() + " listening on port " + cfg.port());
        new CountDownLatch(1).await();
    }
}
