package com.distributedcache.config;

import com.distributedcache.cache.EvictionPolicy;
import com.distributedcache.cluster.ClusterConfig;
import com.distributedcache.cluster.ClusterRuntime;
import com.distributedcache.networking.NodeAddress;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/** Creates the cluster manager (pure-Java engine from cache-core) as Spring beans. */
@Configuration
@EnableConfigurationProperties(ClusterProperties.class)
public class ClusterConfiguration {

    @Bean(destroyMethod = "close")
    public EmbeddedNodes embeddedNodes(ClusterProperties p) {
        return new EmbeddedNodes(p);
    }

    @Bean(destroyMethod = "close")
    public ClusterRuntime clusterRuntime(ClusterProperties p, EmbeddedNodes embedded) {
        ClusterConfig cfg = new ClusterConfig();
        cfg.clusterSecret = p.getSecret();
        cfg.replicationFactor = p.getReplicationFactor();
        cfg.virtualNodes = p.getVirtualNodes();
        cfg.heartbeatIntervalMs = p.getHeartbeatIntervalMs();
        cfg.failureTimeoutMs = p.getFailureTimeoutMs();
        cfg.suspectTimeoutMs = 2 * p.getHeartbeatIntervalMs();
        cfg.pingTimeoutMs = p.getPingTimeoutMs();
        cfg.requestTimeoutMs = p.getRequestTimeoutMs();
        cfg.maxMemoryBytes = p.getMaxMemoryBytes();
        cfg.evictionPolicy = EvictionPolicy.valueOf(p.getEvictionPolicy().toUpperCase());
        cfg.syncReplication = p.isSyncReplication();
        cfg.simulationEnabled = p.isSimulationEnabled();

        List<NodeAddress> nodes = new ArrayList<>(embedded.initialAddresses());
        for (ClusterProperties.NodeEntry n : p.getNodes()) nodes.add(new NodeAddress(n.getId(), n.getHost(), n.getPort()));

        ClusterRuntime rt = new ClusterRuntime(cfg);
        rt.bootstrap(nodes);
        rt.start();
        return rt;
    }
}
