package com.distributedcache.api.simulation;

import com.distributedcache.exception.ConflictException;
import com.distributedcache.exception.InvalidRequestException;
import com.distributedcache.cluster.ClusterRuntime;
import com.distributedcache.persistence.SimulationRunEntity;
import com.distributedcache.persistence.SimulationRunRepository;
import com.distributedcache.simulation.SimulationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Validated, insert-only restore path for an archived CANCELLED simulation detail. */
@Service
public class SimulationHistoryImportService {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,16}");

    private final SimulationRunRepository repository;
    private final ClusterRuntime runtime;
    private final ObjectMapper mapper;

    public SimulationHistoryImportService(SimulationRunRepository repository, ClusterRuntime runtime,
                                          ObjectMapper mapper) {
        this.repository = repository;
        this.runtime = runtime;
        this.mapper = mapper;
    }

    @Transactional
    public SimulationController.Detail importCancelled(SimulationController.Detail detail) {
        validate(detail);
        SimulationController.Summary summary = detail.summary();
        if (runtime.simulation.list().stream().anyMatch(run -> run.id().equals(summary.id())))
            throw new ConflictException("Simulation already exists in the current runtime: " + summary.id());
        if (repository.existsById(summary.id()))
            throw new ConflictException("Simulation already exists in persistent history: " + summary.id());

        SimulationRunEntity entity = new SimulationRunEntity();
        entity.id = summary.id();
        entity.nodeId = summary.nodeId();
        entity.mode = summary.mode();
        entity.phase = summary.phase();
        entity.outcome = summary.outcome();
        entity.startedAt = summary.startedAt();
        entity.endedAt = summary.endedAt();
        entity.detectionMs = summary.detectionMs();
        entity.failoverMs = summary.failoverMs();
        entity.recoveryMs = summary.recoveryMs();
        entity.requestsTotal = detail.report().requestsTotal();
        entity.requestsSucceeded = detail.report().requestsSucceeded();
        entity.requestsFailed = detail.report().requestsFailed();
        entity.fallbackRequests = detail.report().requestsServedByFallback();
        entity.recoveryOutcome = detail.report().recoverySucceeded() ? "SUCCEEDED"
                : detail.report().recoveryCompletedAt() > 0 ? "FAILED" : "UNAVAILABLE";
        entity.errorRatePct = detail.report().errorRatePct();
        entity.createdAt = summary.startedAt(); // matches PersistenceBridge's original write-through mapping
        entity.updatedAt = latestKnownTimestamp(detail);
        try {
            String reportJson = mapper.writeValueAsString(detail.report());
            String eventsJson = mapper.writeValueAsString(detail.events());
            if (reportJson.length() > 100_000 || eventsJson.length() > 100_000)
                throw new InvalidRequestException("Simulation report or events exceed the database column limit.");
            entity.reportJson = reportJson;
            entity.eventsJson = eventsJson;
            java.util.Map<String, String> originalOwners = new java.util.LinkedHashMap<>();
            java.util.Map<String, String> promotedReplicas = new java.util.LinkedHashMap<>();
            if (detail.report().affectedKeys() != null) {
                for (var key : detail.report().affectedKeys()) {
                    if (key.originalPrimary() != null) originalOwners.put(key.key(), key.originalPrimary());
                    if (key.newPrimary() != null && !key.newPrimary().equals(key.originalPrimary()))
                        promotedReplicas.put(key.key(), key.newPrimary());
                }
            }
            entity.originalPrimaryOwnersJson = mapper.writeValueAsString(originalOwners);
            entity.promotedReplicasJson = mapper.writeValueAsString(promotedReplicas);
            repository.saveAndFlush(entity); // flush here so duplicate IDs become a conflict before returning
        } catch (JsonProcessingException e) {
            throw new InvalidRequestException("Simulation report or events could not be serialized.");
        } catch (DataIntegrityViolationException e) {
            throw new ConflictException("Simulation already exists in persistent history: " + summary.id());
        }
        return detail;
    }

    private static void validate(SimulationController.Detail detail) {
        if (detail == null || detail.summary() == null || detail.report() == null || detail.events() == null)
            throw new InvalidRequestException("A complete simulation detail with summary, report, and events is required.");
        SimulationController.Summary s = detail.summary();
        var report = detail.report();
        if (s.id() == null || !ID.matcher(s.id()).matches())
            throw new InvalidRequestException("Simulation ID must contain 1 to 16 letters, digits, underscores, or hyphens.");
        if (s.nodeId() == null || s.nodeId().isBlank() || s.mode() == null
                || !Set.of("ISOLATE", "CRASH").contains(s.mode()))
            throw new InvalidRequestException("Simulation node and failure mode are required.");
        if (!"CANCELLED".equals(s.phase()) || !"CANCELLED".equals(s.outcome())
                || !"CANCELLED".equals(report.outcome()))
            throw new InvalidRequestException("This restore endpoint accepts only records whose phase and outcome are CANCELLED.");
        if (detail.autoRestartAt() != null)
            throw new InvalidRequestException("An archived simulation must not have a pending auto-restart.");
        if (!Objects.equals(s.id(), report.simulationId())
                || !Objects.equals(s.nodeId(), report.failedNode())
                || !Objects.equals(s.mode(), report.mode()))
            throw new InvalidRequestException("Simulation summary and report identity fields do not match.");
        if (s.startedAt() <= 0 || s.endedAt() == null || s.endedAt() < s.startedAt())
            throw new InvalidRequestException("Simulation start and end timestamps are invalid.");
        if (negative(s.detectionMs()) || negative(s.failoverMs()) || negative(s.recoveryMs())
                || negative(report.syncMs()) || negative(report.requestsTotal())
                || negative(report.requestsSucceeded()) || negative(report.requestsFailed()))
            throw new InvalidRequestException("Simulation durations and request counters must not be negative.");
        if (report.requestsTotal() != null && report.requestsSucceeded() != null
                && report.requestsFailed() != null
                && report.requestsSucceeded() + report.requestsFailed() != report.requestsTotal())
            throw new InvalidRequestException("Archived request counters do not add up to the reported total.");
        if (report.errorRatePct() != null && (report.errorRatePct() < 0 || report.errorRatePct() > 100))
            throw new InvalidRequestException("Archived error rate must be between 0 and 100 percent.");
        if (detail.events().isEmpty() || detail.events().size() > 10_000
                || detail.events().stream().anyMatch(event -> event == null || event.time() <= 0
                || event.type() == null || event.type().isBlank() || event.message() == null))
            throw new InvalidRequestException("A valid, non-empty simulation event timeline is required.");
        if (!Objects.equals(s.detectionMs(), report.detectionMs())
                || !Objects.equals(s.failoverMs(), report.failoverMs())
                || !Objects.equals(s.recoveryMs(), report.recoveryMs())
                || !Objects.equals(s.requestsTotal(), report.requestsTotal())
                || !Objects.equals(s.errorRatePct(), report.errorRatePct()))
            throw new InvalidRequestException("Summary metrics do not match the archived report.");
    }

    private static boolean negative(Long value) { return value != null && value < 0; }

    private static long latestKnownTimestamp(SimulationController.Detail detail) {
        long latest = Math.max(detail.summary().startedAt(), detail.summary().endedAt());
        for (SimulationService.SimEvent event : detail.events()) latest = Math.max(latest, event.time());
        return latest;
    }
}
