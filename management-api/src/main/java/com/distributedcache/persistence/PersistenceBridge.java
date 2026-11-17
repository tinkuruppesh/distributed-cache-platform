package com.distributedcache.persistence;

import com.distributedcache.advisor.Recommendation;
import com.distributedcache.cluster.ClusterRuntime;
import com.distributedcache.cluster.ClusterSettings;
import com.distributedcache.cluster.ClusterEvent;
import com.distributedcache.failover.FailoverRecord;
import com.distributedcache.simulation.SimulationService.RunView;
import com.distributedcache.audit.AuditService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Connects the in-memory engine to PostgreSQL: failover history, simulation reports and recommendations are written
 * through to the database; persisted settings and recommendations are reloaded at startup.
 */
@Component
public class PersistenceBridge {
    private static final Logger log = LoggerFactory.getLogger(PersistenceBridge.class);
    public static final String SETTINGS_KEY = "cluster.settings";

    private final ClusterRuntime rt;
    private final FailoverHistoryRepository failovers;
    private final SimulationRunRepository simulations;
    private final RecommendationRepository recommendations;
    private final AppSettingRepository settings;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public PersistenceBridge(ClusterRuntime rt, FailoverHistoryRepository failovers, SimulationRunRepository simulations,
                             RecommendationRepository recommendations, AppSettingRepository settings, ObjectMapper mapper,
                             AuditService audit) {
        this.rt = rt; this.failovers = failovers; this.simulations = simulations;
        this.recommendations = recommendations; this.settings = settings; this.mapper = mapper; this.audit = audit;
    }

    @PostConstruct
    void init() {
        // reload history
        try {
            List<Recommendation> loaded = new ArrayList<>();
            for (RecommendationEntity e : recommendations.findAll()) loaded.add(fromEntity(e));
            rt.recommendations.load(loaded);
            settings.findById(SETTINGS_KEY).ifPresent(s -> {
                try {
                    rt.applySettings(mapper.readValue(s.settingValue, ClusterSettings.class));
                    log.info("Applied persisted cluster settings");
                } catch (Exception ex) { log.warn("Ignoring invalid persisted settings: {}", ex.getMessage()); }
            });
        } catch (RuntimeException e) { log.warn("Could not reload persisted state: {}", e.getMessage()); }

        // write-through listeners
        rt.failover.addListener(this::saveFailover);
        rt.simulation.addListener(this::saveSimulation);
        rt.recommendations.addListener(this::saveRecommendation);
        rt.coordinator.events.addListener(this::auditClusterEvent);
    }

    private void auditClusterEvent(ClusterEvent event) {
        if (!java.util.Set.of("NODE_SUSPECTED", "NODE_FAILED", "FAILOVER_STARTED", "REPLICA_PROMOTED",
                "FAILOVER_COMPLETED", "REREPLICATION_ERROR", "REREPLICATION_COMPLETED",
                "NODE_RECOVERY_DETECTED", "NODE_RECOVERY_FAILED", "NODE_ACTIVE", "RESYNC_COMPLETED", "RESYNC_ERROR")
                .contains(event.type())) return;
        String details = "eventId=" + event.id() + "; type=" + event.type() + "; severity=" + event.severity()
                + "; time=" + event.time();
        audit.record("system", "CLUSTER_EVENT " + event.type(), event.nodeId(), event.severity(), null,
                event.message(), details);
    }

    public void saveSettings(ClusterSettings s) {
        try {
            AppSettingEntity e = new AppSettingEntity();
            e.settingKey = SETTINGS_KEY;
            e.settingValue = mapper.writeValueAsString(s);
            e.updatedAt = System.currentTimeMillis();
            settings.save(e);
        } catch (Exception ex) { log.warn("Could not persist settings: {}", ex.getMessage()); }
    }

    private void saveFailover(FailoverRecord r) {
        try {
            FailoverHistoryEntity e = new FailoverHistoryEntity();
            e.id = r.id(); e.nodeId = r.nodeId(); e.detectedAt = r.detectedAt();
            e.failoverStartedAt = r.failoverStartedAt(); e.failoverCompletedAt = r.failoverCompletedAt();
            e.keysPromoted = r.keysPromoted(); e.reReplicated = r.reReplicated(); e.triggerType = r.trigger();
            e.createdAt = System.currentTimeMillis();
            failovers.save(e);
        } catch (RuntimeException ex) { log.warn("Could not persist failover: {}", ex.getMessage()); }
    }

    private synchronized void saveSimulation(RunView v) {
        try {
            SimulationRunEntity e = new SimulationRunEntity();
            e.id = v.id(); e.nodeId = v.nodeId(); e.mode = v.mode().name(); e.phase = v.phase().name();
            e.startedAt = v.startedAt(); e.endedAt = v.endedAt() == 0 ? null : v.endedAt();
            if (v.report() != null) {
                e.outcome = v.report().outcome(); e.detectionMs = v.report().detectionMs();
                e.failoverMs = v.report().failoverMs(); e.recoveryMs = v.report().recoveryMs();
                e.requestsTotal = v.report().requestsTotal(); e.errorRatePct = v.report().errorRatePct();
                e.requestsSucceeded = v.report().requestsSucceeded();
                e.requestsFailed = v.report().requestsFailed();
                e.fallbackRequests = v.report().requestsServedByFallback();
                e.recoveryOutcome = v.report().recoverySucceeded() ? "SUCCEEDED"
                        : v.report().recoveryCompletedAt() > 0 ? "FAILED" : "UNAVAILABLE";
                Map<String, String> originalOwners = new LinkedHashMap<>();
                Map<String, String> promotedReplicas = new LinkedHashMap<>();
                if (v.report().affectedKeys() != null) {
                    for (var key : v.report().affectedKeys()) {
                        if (key.originalPrimary() != null) originalOwners.put(key.key(), key.originalPrimary());
                        if (key.newPrimary() != null && !key.newPrimary().equals(key.originalPrimary()))
                            promotedReplicas.put(key.key(), key.newPrimary());
                    }
                }
                e.originalPrimaryOwnersJson = mapper.writeValueAsString(originalOwners);
                e.promotedReplicasJson = mapper.writeValueAsString(promotedReplicas);
                e.reportJson = mapper.writeValueAsString(v.report());
            }
            e.eventsJson = mapper.writeValueAsString(v.events());
            e.createdAt = v.startedAt(); e.updatedAt = System.currentTimeMillis();
            simulations.save(e);
        } catch (Exception ex) { log.warn("Could not persist simulation: {}", ex.getMessage()); }
    }

    private void saveRecommendation(Recommendation r) {
        try {
            RecommendationEntity e = new RecommendationEntity();
            e.id = r.id(); e.dedupeKey = r.dedupeKey(); e.category = r.category().name(); e.severity = r.severity().name();
            e.title = trunc(r.title(), 300); e.description = trunc(r.description(), 2000);
            e.supportingMetric = trunc(r.supportingMetric(), 1000); e.reason = trunc(r.reason(), 2000);
            e.recommendedAction = trunc(r.recommendedAction(), 2000); e.status = r.status().name();
            e.createdAt = r.createdAt(); e.updatedAt = r.updatedAt();
            recommendations.save(e);
        } catch (RuntimeException ex) { log.warn("Could not persist recommendation: {}", ex.getMessage()); }
    }

    private static Recommendation fromEntity(RecommendationEntity e) {
        return new Recommendation(e.id, e.dedupeKey, Recommendation.Category.valueOf(e.category),
                Recommendation.Severity.valueOf(e.severity), e.title, e.description, e.supportingMetric, e.reason,
                e.recommendedAction, e.createdAt, e.updatedAt, Recommendation.Status.valueOf(e.status));
    }

    private static String trunc(String s, int max) { return s == null ? null : (s.length() <= max ? s : s.substring(0, max)); }
}
