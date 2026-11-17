package com.distributedcache.api.simulation;

import com.distributedcache.DistributedCacheApplication;
import com.distributedcache.persistence.SimulationRunEntity;
import com.distributedcache.persistence.SimulationRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Verifies that a completed persisted run and its analytics detail survive an application-context restart. */
class SimulationHistoryRestartIntegrationTest {
    @TempDir Path temp;

    @Test
    void simulationHistoryAndDetailSurviveBackendRestart() throws Exception {
        String jdbcUrl = "jdbc:h2:file:" + temp.resolve("durable-history").toAbsolutePath().toString().replace('\\', '/')
                + ";DB_CLOSE_DELAY=-1";
        String id = "restart01";

        try (ConfigurableApplicationContext first = start(jdbcUrl)) {
            SimulationRunEntity run = new SimulationRunEntity();
            run.id = id;
            run.nodeId = "node-1";
            run.mode = "ISOLATE";
            run.phase = "COMPLETED";
            run.outcome = "COMPLETED";
            run.startedAt = 1_700_000_000_000L;
            run.endedAt = run.startedAt + 5_000;
            run.detectionMs = 100L;
            run.failoverMs = 200L;
            run.recoveryMs = 4_000L;
            run.requestsTotal = 100L;
            run.requestsSucceeded = 98L;
            run.requestsFailed = 2L;
            run.fallbackRequests = 7L;
            run.recoveryOutcome = "SUCCEEDED";
            run.originalPrimaryOwnersJson = "{\"product:101\":\"node-1\"}";
            run.promotedReplicasJson = "{\"product:101\":\"node-2\"}";
            run.errorRatePct = 2.0;
            run.reportJson = """
                    {"simulationId":"restart01","failedNode":"node-1","mode":"ISOLATE","outcome":"COMPLETED",
                     "failureStartedAt":1700000000000,"failureDetectedAt":1700000000100,
                     "failoverStartedAt":1700000000100,"failoverCompletedAt":1700000000300,
                     "restartedAt":1700000001000,"recoveryStartedAt":1700000001000,"recoveryCompletedAt":1700000005000,
                     "detectionMs":100,"failoverMs":200,"recoveryMs":4000,"syncMs":4000,
                     "requestsTotal":100,"requestsSucceeded":98,"requestsFailed":2,"errorRatePct":2.0,
                     "requestsServedByFallback":7,"keysPromoted":1,"reReplicated":1,
                     "replicationSent":0,"replicationAcked":0,"replicationFailed":0,
                     "averageLatencyMs":2.0,"p95LatencyMs":3.0,"p99LatencyMs":4.0,"latencySamples":100,
                     "requestsPerSecond":20.0,"affectedKeys":[],"recoverySucceeded":true,"finalNodeStatus":"ACTIVE",
                     "consistency":null,"notes":[]}
                    """;
            run.eventsJson = "[{\"time\":1700000000000,\"type\":\"SIM_REPORT_READY\",\"message\":\"done\"}]";
            run.createdAt = run.startedAt;
            run.updatedAt = run.endedAt;
            first.getBean(SimulationRunRepository.class).saveAndFlush(run);
        }

        try (ConfigurableApplicationContext restarted = start(jdbcUrl)) {
            var entity = restarted.getBean(SimulationRunRepository.class).findById(id).orElseThrow();
            assertEquals("COMPLETED", entity.outcome);
            assertEquals(98L, entity.requestsSucceeded);
            assertEquals(2L, entity.requestsFailed);
            assertEquals(7L, entity.fallbackRequests);
            assertEquals("SUCCEEDED", entity.recoveryOutcome);
            assertEquals("{\"product:101\":\"node-2\"}", entity.promotedReplicasJson);
            var detail = restarted.getBean(SimulationController.class).get(id);
            assertEquals(id, detail.summary().id());
            assertEquals(4_000L, detail.report().recoveryMs());
            assertEquals(1, detail.events().size());
            assertNotNull(restarted.getBean(ObjectMapper.class));
        }
    }

    private ConfigurableApplicationContext start(String jdbcUrl) {
        return new SpringApplicationBuilder(DistributedCacheApplication.class)
                .web(WebApplicationType.NONE)
                .profiles("test")
                .run("--spring.datasource.url=" + jdbcUrl,
                        "--cluster.embedded-nodes=0",
                        "--spring.main.banner-mode=off");
    }
}
