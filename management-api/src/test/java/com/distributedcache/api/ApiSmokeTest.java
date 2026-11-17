package com.distributedcache.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.distributedcache.advisor.Finding;
import com.distributedcache.advisor.Recommendation;
import com.distributedcache.cluster.ClusterRuntime;
import com.distributedcache.DistributedCacheApplication;
import com.distributedcache.persistence.RecommendationRepository;
import com.distributedcache.persistence.AuditLogRepository;
import com.distributedcache.persistence.SimulationRunEntity;
import com.distributedcache.persistence.SimulationRunRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Boots the whole application (H2 + 3 embedded nodes) and exercises auth, RBAC, cache and monitoring end to end. */
@SpringBootTest(classes = DistributedCacheApplication.class,
        properties = "management.prometheus.metrics.export.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiSmokeTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired SimulationRunRepository simulationRuns;
    @Autowired RecommendationRepository recommendationRepository;
    @Autowired ClusterRuntime clusterRuntime;
    @Autowired AuditLogRepository auditLogRepository;

    private String login(String user, String pass) throws Exception {
        String body = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user + "\",\"password\":\"" + pass + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode n = mapper.readTree(body);
        return "Bearer " + n.get("token").asText();
    }

    @Test
    void protectedEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/cluster/nodes")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void advisorDetailAndStatusRoutesBindIdsAndPersistStatusChanges() throws Exception {
        String dedupeKey = "advisor-route-test:" + UUID.randomUUID();
        clusterRuntime.recommendations.upsert(List.of(new Finding(dedupeKey, Recommendation.Category.PERFORMANCE,
                Recommendation.Severity.INFO, "Advisor route binding test", "test finding", "measured test metric",
                "test reason", "test action")));
        Recommendation created = clusterRuntime.recommendations.list(Recommendation.Status.OPEN, null).stream()
                .filter(recommendation -> recommendation.dedupeKey().equals(dedupeKey)).findFirst().orElseThrow();
        String admin = login("admin", "Admin@12345");
        String base = "/api/v1/advisor/recommendations/" + created.id();

        mvc.perform(get(base).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(created.id()));
        mvc.perform(post(base + "/acknowledge").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
        mvc.perform(post(base + "/resolve").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RESOLVED"));
        assertEquals("RESOLVED", recommendationRepository.findById(created.id()).orElseThrow().status,
                "the resolve action must be written through to the test database");
        mvc.perform(post(base + "/dismiss").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISMISSED"));
        mvc.perform(get(base).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DISMISSED"));
    }

    @Test
    void auditApiSearchFiltersPaginationAndCacheActionsUsePersistedRecords() throws Exception {
        String admin = login("admin", "Admin@12345");
        String key = "audit-test-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/cache").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("key", key, "value", "initial"))))
                .andExpect(status().isCreated());
        mvc.perform(put("/api/v1/cache/" + key).header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("value", "updated", "ttlSeconds", 300))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/audit-logs").queryParam("q", key).queryParam("status", "SUCCESS")
                        .queryParam("page", "0").queryParam("size", "1").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items.length()").value(1)).andExpect(jsonPath("$.totalPages").value(2));
        mvc.perform(get("/api/v1/audit-logs").queryParam("action", "CACHE_SET").queryParam("q", key)
                        .queryParam("status", "SUCCESS").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].username").value("admin"))
                .andExpect(jsonPath("$.items[0].target").value(key))
                .andExpect(jsonPath("$.items[0].description").value("Cache entry created."))
                .andExpect(jsonPath("$.items[0].details").value(org.hamcrest.Matchers.containsString("primary=")));
        mvc.perform(get("/api/v1/audit-logs").queryParam("from", "0").queryParam("to", String.valueOf(System.currentTimeMillis() + 1))
                        .queryParam("q", key).header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(get("/api/v1/audit-logs").queryParam("status", "INVALID").header("Authorization", admin))
                .andExpect(status().isBadRequest());
        assertEquals(2, auditLogRepository.findAll().stream().filter(row -> key.equals(row.target)).count(),
                "successful SET and UPDATE audit rows are persisted in the test database");
    }

    @Test
    void auditApiRemainsAdministratorOnly() throws Exception {
        String viewer = login("viewer", "Viewer@12345");
        mvc.perform(get("/api/v1/audit-logs").header("Authorization", viewer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/audit-logs")).andExpect(status().isUnauthorized());
    }

    @Test
    void clusterFailureAndRecoveryEventsArePersistedAsAuditRecords() {
        String marker = "audit-lifecycle-" + UUID.randomUUID();
        clusterRuntime.coordinator.events.record("NODE_FAILED", "node-test", "CRITICAL", marker + " failed");
        clusterRuntime.coordinator.events.record("NODE_ACTIVE", "node-test", "INFO", marker + " recovered");

        var records = auditLogRepository.findAll().stream()
                .filter(row -> row.description != null && row.description.contains(marker)).toList();
        assertEquals(2, records.size());
        assertTrue(records.stream().anyMatch(row -> row.action.endsWith("NODE_FAILED")));
        assertTrue(records.stream().anyMatch(row -> row.action.endsWith("NODE_ACTIVE")));
        assertTrue(records.stream().allMatch(row -> "system".equals(row.username) && row.details.contains("eventId=")));
    }

    @Test
    void cacheLifecycleThroughTheGateway() throws Exception {
        String admin = login("admin", "Admin@12345");
        mvc.perform(post("/api/v1/cache").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"user:101\",\"value\":\"Ruppesh\",\"ttlSeconds\":600}")).andExpect(status().isCreated());
        mvc.perform(get("/api/v1/cache/user:101").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("Ruppesh"));
        mvc.perform(get("/api/v1/cache/user:101/ttl").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasExpiry").value(true));
        mvc.perform(get("/api/v1/cache/user:101/exists").header("Authorization", admin)).andExpect(jsonPath("$.exists").value(true));
        mvc.perform(delete("/api/v1/cache/user:101").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/v1/cache/user:101").header("Authorization", admin)).andExpect(status().isNotFound());
    }

    @Test
    void invalidInputGetsA400() throws Exception {
        String admin = login("admin", "Admin@12345");
        mvc.perform(post("/api/v1/cache").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"bad key with spaces\",\"value\":\"x\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void viewerIsReadOnlyAndSeesMaskedValues() throws Exception {
        String admin = login("admin", "Admin@12345"), viewer = login("viewer", "Viewer@12345");
        mvc.perform(post("/api/v1/cache").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"secret:note\",\"value\":\"top-secret\"}")).andExpect(status().isCreated());
        mvc.perform(post("/api/v1/cache").header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"x\",\"value\":\"y\"}")).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/cache/secret:note").header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"value\":\"overwritten\"}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/cache/bulk-delete").header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"keys\":[\"x\"]}")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/cache/clear").header("Authorization", viewer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/cache/secret:note").header("Authorization", viewer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.masked").value(true));
        mvc.perform(get("/api/v1/audit-logs").header("Authorization", viewer)).andExpect(status().isForbidden());
        mvc.perform(put("/api/v1/settings").header("Authorization", viewer).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void monitoringAndClusterEndpointsReturnRealData() throws Exception {
        String admin = login("admin", "Admin@12345");
        String body = mvc.perform(get("/api/v1/cluster/nodes").header("Authorization", admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode nodes = mapper.readTree(body);
        assertEquals(3, nodes.size());
        assertEquals("ACTIVE", nodes.get(0).get("state").asText());
        mvc.perform(get("/api/v1/monitoring/overview").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.health").value("HEALTHY"))
                .andExpect(jsonPath("$.totalNodes").value(3))
                .andExpect(jsonPath("$.activeNodes").value(3))
                .andExpect(jsonPath("$.failedNodes").value(0))
                .andExpect(jsonPath("$.recoveringNodes").value(0))
                .andExpect(jsonPath("$.latency.samples").isNumber());
        mvc.perform(get("/api/v1/monitoring/traffic?seconds=5").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].time").isNumber())
                .andExpect(jsonPath("$[0].requests").isNumber()).andExpect(jsonPath("$[0].failures").isNumber())
                .andExpect(jsonPath("$[0].getRequests").isNumber()).andExpect(jsonPath("$[0].setRequests").isNumber())
                .andExpect(jsonPath("$[0].updateRequests").isNumber()).andExpect(jsonPath("$[0].deleteRequests").isNumber())
                .andExpect(jsonPath("$[0].requestsByNode").isMap());
        mvc.perform(get("/api/v1/monitoring/cache-performance?seconds=5").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.counters.hits").isNumber())
                .andExpect(jsonPath("$.counters.misses").isNumber())
                .andExpect(jsonPath("$.timeline[0].hits").isNumber())
                .andExpect(jsonPath("$.timeline[0].misses").isNumber())
                .andExpect(jsonPath("$.replication.sent").isNumber())
                .andExpect(jsonPath("$.replication.acked").isNumber())
                .andExpect(jsonPath("$.replication.applied").isNumber())
                .andExpect(jsonPath("$.replication.stale").isNumber())
                .andExpect(jsonPath("$.replication.alreadyCurrent").isNumber())
                .andExpect(jsonPath("$.replication.failed").isNumber())
                .andExpect(jsonPath("$.replication.ackLatencySampleCount").isNumber())
                .andExpect(jsonPath("$.replication.avgAckLatencyMs").isNumber())
                .andExpect(jsonPath("$.replication.p95AckLatencyMs").isNumber())
                .andExpect(jsonPath("$.replicationMode").value("ASYNCHRONOUS"));
        mvc.perform(get("/api/v1/cluster/events?limit=8").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/cluster/hash-ring").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.virtualNodes").isArray());
        mvc.perform(get("/api/v1/advisor/recommendations?status=OPEN").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/monitoring/cache-performance?seconds=5").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hotKeyTracking.readAccesses").isNumber())
                .andExpect(jsonPath("$.hotKeyTracking.trackedReadAccesses").isNumber())
                .andExpect(jsonPath("$.hotKeyTracking.trackingLimit").value(5000))
                .andExpect(jsonPath("$.hotKeyTracking.trackingLimitReached").isBoolean());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.names").isArray());
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("cache_requests_get_total")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("cache_latency_p50_ms")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("cache_replication_already_current_total")));
    }

    @Test
    void advisorRunReportsAnalysisOutcomeAndPerRuleFailures() throws Exception {
        String admin = login("admin", "Admin@12345");
        mvc.perform(post("/api/v1/advisor/run").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successful").value(true))
                .andExpect(jsonPath("$.created").isNumber())
                .andExpect(jsonPath("$.evaluatedRules").value(9))
                .andExpect(jsonPath("$.ruleFailures").isArray())
                .andExpect(jsonPath("$.ruleFailures.length()").value(0));
    }

    @Test
    void replicationMonitoringReportsScanCoverageToAuthenticatedViewers() throws Exception {
        mvc.perform(get("/api/v1/monitoring/replication")).andExpect(status().isUnauthorized());
        String viewer = login("viewer", "Viewer@12345");
        mvc.perform(get("/api/v1/monitoring/replication").header("Authorization", viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keysInspected").isNumber())
                .andExpect(jsonPath("$.sampleLimit").value(5000))
                .andExpect(jsonPath("$.scanComplete").isBoolean())
                .andExpect(jsonPath("$.nodesAttempted").isNumber())
                .andExpect(jsonPath("$.nodesScanned").isNumber())
                .andExpect(jsonPath("$.scanErrors").isArray())
                .andExpect(jsonPath("$.perNode[0].expectedPrimaryCount").isNumber())
                .andExpect(jsonPath("$.perNode[0].actualReplicaCount").isNumber())
                .andExpect(jsonPath("$.perNode[0].scanStatus").value("SUCCESS"))
                .andExpect(jsonPath("$.perNode[0].keysDiscovered").isNumber())
                .andExpect(jsonPath("$.latencySamples").isArray());
    }

    @Test
    void overviewDataEndpointsAcceptDashboardFilters() throws Exception {
        String admin = login("admin", "Admin@12345");
        mvc.perform(get("/api/v1/monitoring/traffic?seconds=5").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].time").isNumber())
                .andExpect(jsonPath("$[0].requests").isNumber());
        mvc.perform(get("/api/v1/monitoring/cache-performance?seconds=5").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.counters.hits").isNumber())
                .andExpect(jsonPath("$.counters.misses").isNumber())
                .andExpect(jsonPath("$.timeline[0].hits").isNumber())
                .andExpect(jsonPath("$.timeline[0].misses").isNumber());
        mvc.perform(get("/api/v1/cluster/events?limit=8").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/advisor/recommendations?status=OPEN").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray());
        mvc.perform(get("/api/v1/monitoring/traffic?seconds=900").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(900));
        mvc.perform(get("/api/v1/monitoring/cache-performance?seconds=900").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.timeline.length()").value(900));
        mvc.perform(get("/api/v1/monitoring/traffic?seconds=3600").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3600));
    }

    @Test
    void nodeDetailsExposeHeartbeatAndRecoveryContextAndViewerCannotOperateNodes() throws Exception {
        String admin = login("admin", "Admin@12345"), viewer = login("viewer", "Viewer@12345");
        mvc.perform(get("/api/v1/cluster/nodes/node-1").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.node.lastHeartbeatAt").isNumber())
                .andExpect(jsonPath("$.heartbeatIntervalMs").isNumber()).andExpect(jsonPath("$.failureTimeoutMs").isNumber());
        mvc.perform(get("/api/v1/cluster/nodes").header("Authorization", viewer)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/cluster/nodes/node-1/maintenance").header("Authorization", viewer)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/cluster/nodes/node-1/resume").header("Authorization", viewer)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/cluster/nodes/node-1").header("Authorization", viewer)).andExpect(status().isForbidden());
    }

    @Test
    void cacheExplorerSearchReadsUpdatesTtlAndDeletesThroughTheCacheEngine() throws Exception {
        String admin = login("admin", "Admin@12345");
        mvc.perform(post("/api/v1/cache").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"explorer-alpha\",\"value\":\"first\",\"ttlSeconds\":60}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.writtenTo").isNotEmpty())
                .andExpect(jsonPath("$.replicas.length()").value(1));
        mvc.perform(post("/api/v1/cache").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"explorer-beta\",\"value\":\"second\"}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/cache/explorer-alpha/read").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hit").value(true))
                .andExpect(jsonPath("$.value").value("first")).andExpect(jsonPath("$.servedBy").isNotEmpty())
                .andExpect(jsonPath("$.latencyMs").isNumber()).andExpect(jsonPath("$.placement.ringPosition").isNumber());
        mvc.perform(get("/api/v1/cache/explorer-alpha/locate").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.key").value("explorer-alpha"))
                .andExpect(jsonPath("$.primary").isNotEmpty()).andExpect(jsonPath("$.ringPosition").isNumber())
                .andExpect(jsonPath("$.replicas").isArray()).andExpect(jsonPath("$.states").isMap());
        mvc.perform(get("/api/v1/cache/explorer-alpha/read").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hit").value(true));
        mvc.perform(get("/api/v1/cache/missing-explorer-key/read").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hit").value(false));
        mvc.perform(get("/api/v1/monitoring/cache-performance?seconds=5").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.counters.gets").isNumber())
                .andExpect(jsonPath("$.counters.hits").isNumber()).andExpect(jsonPath("$.counters.misses").isNumber());

        mvc.perform(get("/api/v1/cache/keys?prefix=explorer-&limit=1&offset=0&sortBy=KEY").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sizeBytes").isNumber()).andExpect(jsonPath("$[0].dataType").value("STRING"));
        mvc.perform(get("/api/v1/cache/keys?prefix=explorer-&limit=1&offset=1").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));

        mvc.perform(put("/api/v1/cache/explorer-alpha").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"updated\",\"ttlSeconds\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.message").value("Updated"));
        mvc.perform(get("/api/v1/cache/explorer-alpha/read").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.value").value("updated"));
        mvc.perform(post("/api/v1/cache/explorer-alpha/expire").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttlSeconds\":60}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/cache/explorer-alpha/ttl").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasExpiry").value(true));
        mvc.perform(post("/api/v1/cache/explorer-alpha/expire").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ttlSeconds\":0}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/cache/explorer-alpha/ttl").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasExpiry").value(false));

        mvc.perform(post("/api/v1/cache/bulk-delete").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"keys\":[\"explorer-alpha\",\"explorer-beta\"]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted.length()").value(2))
                .andExpect(jsonPath("$.failed").isEmpty());
        mvc.perform(get("/api/v1/cache/explorer-alpha/read").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hit").value(false));
        mvc.perform(get("/api/v1/monitoring/cache-operations?limit=20").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$[0].operation").isNotEmpty());
        mvc.perform(get("/api/v1/monitoring/cache-performance?seconds=5").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.counters.updates").isNumber());

        mvc.perform(post("/api/v1/cache").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"explorer-clear-check\",\"value\":\"clear-me\"}"))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/v1/cache/clear").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.deleted").isArray());
        mvc.perform(get("/api/v1/cache/explorer-clear-check/read").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hit").value(false));
    }

    @Test
    void simulationRequiresAdminAndConfirmation() throws Exception {
        String admin = login("admin", "Admin@12345"), operator = login("operator", "Operator@12345");
        String req = "{\"nodeId\":\"node-1\",\"mode\":\"ISOLATE\",\"generateTraffic\":false,\"trafficRps\":0,\"autoRestartAfterSec\":1,\"confirm\":false}";
        mvc.perform(post("/api/v1/simulations").header("Authorization", operator).contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/simulations").header("Authorization", admin).contentType(MediaType.APPLICATION_JSON).content(req))
                .andExpect(status().isBadRequest());
    }

    @Test
    void simulationDetailPathVariableBindsWithCurrentCompilerSettings() throws Exception {
        String admin = login("admin", "Admin@12345");
        mvc.perform(get("/api/v1/simulations/does-not-exist").header("Authorization", admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Unknown simulation: does-not-exist"));
    }

    @Test
    void simulationListOptionalQueryParameterBindsWithoutParameterMetadata() throws Exception {
        String admin = login("admin", "Admin@12345");
        mvc.perform(get("/api/v1/simulations").header("Authorization", admin))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/simulations").queryParam("q", "node-1").header("Authorization", admin))
                .andExpect(status().isOk());
    }

    @Test
    void simulationAnalyticsApiReturnsMeasuredRequestCountersAndNullableDurations() throws Exception {
        SimulationRunEntity run = new SimulationRunEntity();
        run.id = "analytics-01";
        run.nodeId = "node-1";
        run.mode = "ISOLATE";
        run.phase = "COMPLETED";
        run.outcome = "COMPLETED";
        run.startedAt = 1_735_000_000_000L;
        run.createdAt = run.startedAt;
        run.updatedAt = run.startedAt;
        run.requestsTotal = 100L;
        run.eventsJson = "[]";
        run.reportJson = """
                {"simulationId":"analytics-01","failedNode":"node-1","mode":"ISOLATE","outcome":"COMPLETED",
                 "failureStartedAt":100,"failureDetectedAt":101,"failoverStartedAt":102,"failoverCompletedAt":0,
                 "restartedAt":0,"recoveryStartedAt":0,"recoveryCompletedAt":0,
                 "detectionMs":1,"failoverMs":0,"recoveryMs":0,"syncMs":0,
                 "requestsTotal":100,"requestsSucceeded":95,"requestsFailed":5,"errorRatePct":5.0,
                 "requestsPerSecond":1.0}
                """;
        simulationRuns.save(run);

        String admin = login("admin", "Admin@12345");
        mvc.perform(get("/api/v1/simulations").queryParam("q", "analytics-01").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].requestsTotal").value(100))
                .andExpect(jsonPath("$[0].requestsSucceeded").value(95))
                .andExpect(jsonPath("$[0].requestsFailed").value(5))
                .andExpect(jsonPath("$[0].detectionMs").value(1))
                .andExpect(jsonPath("$[0].failoverMs").doesNotExist())
                .andExpect(jsonPath("$[0].recoveryMs").doesNotExist());
        mvc.perform(get("/api/v1/simulations/analytics-01").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.requestsSucceeded").value(95))
                .andExpect(jsonPath("$.report.failoverMs").doesNotExist());
    }

    @Test
    void simulationAnalyticsFiltersPaginatesSortsAndAggregatesStoredResults() throws Exception {
        long day1 = 1_735_689_600_000L;
        saveAnalyticsFixture("phase-fixture-01", "node-1", "ISOLATE", "COMPLETED", day1, 10L, 30L, 100L, 100L, 95L);
        saveAnalyticsFixture("phase-fixture-02", "node-2", "CRASH", "COMPLETED", day1 + 86_400_000L, 20L, 20L, 200L, 1000L, 900L);
        saveAnalyticsFixture("phase-fixture-03", "node-1", "CRASH", "RECOVERY_NOT_COMPLETED", day1 + 2 * 86_400_000L, 40L, null, null, 50L, 40L);
        String admin = login("admin", "Admin@12345");

        mvc.perform(get("/api/v1/simulations/analytics").queryParam("q", "phase-fixture")
                        .queryParam("size", "2").queryParam("page", "0").queryParam("sortBy", "recovery")
                        .queryParam("direction", "desc").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].id").value("phase-fixture-02"))
                .andExpect(jsonPath("$.items[2]").doesNotExist())
                .andExpect(jsonPath("$.summary.totalSimulations").value(3))
                .andExpect(jsonPath("$.summary.completedSimulations").value(2))
                .andExpect(jsonPath("$.summary.avgDetectionMs").value(15))
                .andExpect(jsonPath("$.summary.detectionSamples").value(2))
                .andExpect(jsonPath("$.summary.requestsTotal").value(1150))
                .andExpect(jsonPath("$.summary.requestsSucceeded").value(1035))
                .andExpect(jsonPath("$.trends.length()").value(3))
                .andExpect(jsonPath("$.byNode.length()").value(2))
                .andExpect(jsonPath("$.byNode[1].group").value("node-2"))
                .andExpect(jsonPath("$.slowestObservedRecoveryNode").value("node-2"));

        mvc.perform(get("/api/v1/simulations/analytics").queryParam("q", "phase-fixture")
                        .queryParam("outcome", "COMPLETED").queryParam("mode", "CRASH")
                        .queryParam("nodeId", "node-2").queryParam("from", String.valueOf(day1 + 86_400_000L))
                        .queryParam("to", String.valueOf(day1 + 2 * 86_400_000L))
                        .queryParam("sortBy", "date").header("Authorization", admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].id").value("phase-fixture-02"));

        mvc.perform(get("/api/v1/simulations/analytics").queryParam("q", "phase-fixture")
                        .queryParam("page", "1").queryParam("size", "2").header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));

        mvc.perform(get("/api/v1/simulations/analytics").queryParam("q", "phase-fixture")
                        .queryParam("sortBy", "date").queryParam("direction", "asc")
                        .header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value("phase-fixture-01"));
        mvc.perform(get("/api/v1/simulations/analytics").queryParam("q", "phase-fixture")
                        .queryParam("sortBy", "detection").queryParam("direction", "asc")
                        .header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value("phase-fixture-01"));
        mvc.perform(get("/api/v1/simulations/analytics").queryParam("q", "phase-fixture")
                        .queryParam("sortBy", "failover").queryParam("direction", "asc")
                        .header("Authorization", admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value("phase-fixture-02"));
    }

    private void saveAnalyticsFixture(String id, String nodeId, String mode, String outcome, long startedAt,
                                      Long detectionMs, Long failoverMs, Long recoveryMs,
                                      long requestsTotal, long requestsSucceeded) throws Exception {
        SimulationRunEntity run = new SimulationRunEntity();
        run.id = id;
        run.nodeId = nodeId;
        run.mode = mode;
        run.phase = "COMPLETED".equals(outcome) ? "COMPLETED" : "FAILED";
        run.outcome = outcome;
        run.startedAt = startedAt;
        run.createdAt = startedAt;
        run.updatedAt = startedAt;
        run.endedAt = startedAt + 500;
        run.detectionMs = detectionMs;
        run.failoverMs = failoverMs;
        run.recoveryMs = recoveryMs;
        run.requestsTotal = requestsTotal;
        run.errorRatePct = 100.0 * (requestsTotal - requestsSucceeded) / requestsTotal;
        run.eventsJson = "[]";
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("simulationId", id); report.put("failedNode", nodeId); report.put("mode", mode); report.put("outcome", outcome);
        report.put("failureStartedAt", startedAt); report.put("failureDetectedAt", detectionMs == null ? 0 : startedAt + detectionMs);
        report.put("failoverStartedAt", failoverMs == null ? 0 : startedAt + 100); 
        report.put("failoverCompletedAt", failoverMs == null ? 0 : startedAt + 100 + failoverMs);
        report.put("restartedAt", recoveryMs == null ? 0 : startedAt + 200);
        report.put("recoveryStartedAt", recoveryMs == null ? 0 : startedAt + 200);
        report.put("recoveryCompletedAt", recoveryMs == null ? 0 : startedAt + 200 + recoveryMs);
        report.put("detectionMs", detectionMs); report.put("failoverMs", failoverMs);
        report.put("recoveryMs", recoveryMs); report.put("syncMs", recoveryMs);
        report.put("requestsTotal", requestsTotal); report.put("requestsSucceeded", requestsSucceeded);
        report.put("requestsFailed", requestsTotal - requestsSucceeded); report.put("errorRatePct", run.errorRatePct);
        report.put("requestsPerSecond", 1.0);
        run.reportJson = mapper.writeValueAsString(report);
        simulationRuns.save(run);
    }
}
