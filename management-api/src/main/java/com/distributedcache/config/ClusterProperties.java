package com.distributedcache.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Binds the "cluster.*" section of application.yml. */
@ConfigurationProperties(prefix = "cluster")
public class ClusterProperties {
    private String secret = "dev-cluster-secret-change-me";
    private int replicationFactor = 2;
    private int virtualNodes = 100;
    private long heartbeatIntervalMs = 1000;
    private long failureTimeoutMs = 5000;
    private int pingTimeoutMs = 500;
    private int requestTimeoutMs = 800;
    private long maxMemoryBytes = 64L * 1024 * 1024;
    private String evictionPolicy = "LRU";
    private boolean syncReplication = false;
    private boolean simulationEnabled = true;
    private int embeddedNodes = 0;
    private int embeddedBasePort = 7001;
    private List<NodeEntry> nodes = new ArrayList<>();

    public static class NodeEntry {
        private String id;
        private String host;
        private int port;
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }
        public int getPort() { return port; }
        public void setPort(int port) { this.port = port; }
    }

    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public int getReplicationFactor() { return replicationFactor; }
    public void setReplicationFactor(int v) { this.replicationFactor = v; }
    public int getVirtualNodes() { return virtualNodes; }
    public void setVirtualNodes(int v) { this.virtualNodes = v; }
    public long getHeartbeatIntervalMs() { return heartbeatIntervalMs; }
    public void setHeartbeatIntervalMs(long v) { this.heartbeatIntervalMs = v; }
    public long getFailureTimeoutMs() { return failureTimeoutMs; }
    public void setFailureTimeoutMs(long v) { this.failureTimeoutMs = v; }
    public int getPingTimeoutMs() { return pingTimeoutMs; }
    public void setPingTimeoutMs(int v) { this.pingTimeoutMs = v; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int v) { this.requestTimeoutMs = v; }
    public long getMaxMemoryBytes() { return maxMemoryBytes; }
    public void setMaxMemoryBytes(long v) { this.maxMemoryBytes = v; }
    public String getEvictionPolicy() { return evictionPolicy; }
    public void setEvictionPolicy(String v) { this.evictionPolicy = v; }
    public boolean isSyncReplication() { return syncReplication; }
    public void setSyncReplication(boolean v) { this.syncReplication = v; }
    public boolean isSimulationEnabled() { return simulationEnabled; }
    public void setSimulationEnabled(boolean v) { this.simulationEnabled = v; }
    public int getEmbeddedNodes() { return embeddedNodes; }
    public void setEmbeddedNodes(int v) { this.embeddedNodes = v; }
    public int getEmbeddedBasePort() { return embeddedBasePort; }
    public void setEmbeddedBasePort(int v) { this.embeddedBasePort = v; }
    public List<NodeEntry> getNodes() { return nodes; }
    public void setNodes(List<NodeEntry> nodes) { this.nodes = nodes; }
}
