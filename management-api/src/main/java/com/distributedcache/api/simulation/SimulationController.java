package com.distributedcache.api.simulation;

import com.distributedcache.cluster.ClusterRuntime;
import com.distributedcache.exception.ConflictException;
import com.distributedcache.exception.InvalidRequestException;
import com.distributedcache.exception.NotFoundException;
import com.distributedcache.persistence.SimulationRunEntity;
import com.distributedcache.persistence.SimulationRunAnalyticsProjection;
import com.distributedcache.persistence.SimulationRunRepository;
import com.distributedcache.simulation.SimulationService;
import com.distributedcache.simulation.SimulationService.Mode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.function.Function;

/** Failure simulation (ADMIN only, and only when cluster.simulation-enabled=true). */
@RestController
@RequestMapping("/api/v1/simulations")
public class SimulationController {
    public record StartRequest(@NotBlank String nodeId, @NotNull Mode mode, boolean generateTraffic,
                               @Min(0) @Max(500) int trafficRps, @Min(0) @Max(1800) int autoRestartAfterSec, boolean confirm) {}

    public record Summary(String id, String nodeId, String mode, String phase, String outcome, long startedAt, Long endedAt,
                          Long detectionMs, Long failoverMs, Long recoveryMs, Long requestsTotal,
                          Long requestsSucceeded, Long requestsFailed, Double errorRatePct) {}

    public record Detail(Summary summary, List<SimulationService.SimEvent> events, SimulationService.Report report, Long autoRestartAt) {}

    public record AnalyticsSummary(long totalSimulations, long completedSimulations, long failedSimulations,
                                   Double avgDetectionMs, int detectionSamples,
                                   Double avgFailoverMs, int failoverSamples,
                                   Double avgRecoveryMs, int recoverySamples,
                                   Double requestSuccessPct, Long requestsSucceeded, Long requestsTotal) {}

    public record TrendPoint(String date, long simulations,
                             Double avgDetectionMs, int detectionSamples,
                             Double avgFailoverMs, int failoverSamples,
                             Double avgRecoveryMs, int recoverySamples,
                             Double requestSuccessPct, Double errorRatePct,
                             Long requestsSucceeded, Long requestsTotal, int requestSamples) {}

    public record Comparison(String group, long simulations,
                             Double avgDetectionMs, int detectionSamples,
                             Double avgFailoverMs, int failoverSamples,
                             Double avgRecoveryMs, int recoverySamples) {}

    public record FilterOptions(List<String> outcomes, List<String> modes, List<String> nodes) {}

    public record AnalyticsPage(List<Summary> items, int page, int size, long totalElements, int totalPages,
                                AnalyticsSummary summary, List<TrendPoint> trends,
                                List<Comparison> byNode, List<Comparison> byMode,
                                String slowestObservedRecoveryNode, FilterOptions filters) {}

    private final ClusterRuntime rt;
    private final SimulationRunRepository repo;
    private final ObjectMapper mapper;

    public SimulationController(ClusterRuntime rt, SimulationRunRepository repo, ObjectMapper mapper) {
        this.rt = rt; this.repo = repo; this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<SimulationService.RunView> start(@Valid @RequestBody StartRequest req) {
        if (!req.confirm()) throw new InvalidRequestException("Confirmation required: this action takes a node out of service (demo only).");
        var view = rt.simulation.start(new SimulationService.StartRequest(req.nodeId(), req.mode(),
                req.generateTraffic(), req.trafficRps(), req.autoRestartAfterSec()));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    /** Live runs (this JVM) plus finished runs from the database; optional free-text search. */
    @GetMapping
    public List<Summary> list(@RequestParam(name = "q", required = false) String q) {
        Map<String, Summary> out = new LinkedHashMap<>();
        for (var v : rt.simulation.list()) {
            Summary s = summary(v);
            if (matches(s, q)) out.put(s.id(), s);
        }
        for (SimulationRunEntity e : repo.search(q == null ? "" : q)) out.putIfAbsent(e.id, summary(e));
        List<Summary> l = new ArrayList<>(out.values());
        l.sort((a, b) -> Long.compare(b.startedAt(), a.startedAt()));
        return l;
    }

    /** Paginated simulation history plus date-bucketed trends and comparisons over the same filtered source rows. */
    @GetMapping("/analytics")
    public AnalyticsPage analytics(@RequestParam(name = "q", required = false) String q,
                                   @RequestParam(name = "outcome", required = false) String outcome,
                                   @RequestParam(name = "mode", required = false) String mode,
                                   @RequestParam(name = "nodeId", required = false) String nodeId,
                                   @RequestParam(name = "from", required = false) Long from,
                                   @RequestParam(name = "to", required = false) Long to,
                                   @RequestParam(name = "sortBy", defaultValue = "date") String sortBy,
                                   @RequestParam(name = "direction", defaultValue = "desc") String direction,
                                   @RequestParam(name = "page", defaultValue = "0") int page,
                                   @RequestParam(name = "size", defaultValue = "25") int size) {
        if (page < 0) throw new InvalidRequestException("page must be zero or greater.");
        if (size < 1 || size > 100) throw new InvalidRequestException("size must be between 1 and 100.");
        if (from != null && to != null && from >= to) throw new InvalidRequestException("from must be earlier than to.");
        if (!Set.of("date", "detection", "failover", "recovery").contains(sortBy.toLowerCase(Locale.ROOT)))
            throw new InvalidRequestException("sortBy must be date, detection, failover, or recovery.");
        if (!Set.of("asc", "desc").contains(direction.toLowerCase(Locale.ROOT)))
            throw new InvalidRequestException("direction must be asc or desc.");

        Map<String, Summary> unique = new LinkedHashMap<>();
        for (SimulationRunAnalyticsProjection e : repo.findAllForAnalytics()) unique.put(e.getId(), summary(e));
        // Prefer the current JVM view for active runs; completed runs are also present in persistence and de-duplicated.
        for (var v : rt.simulation.list()) unique.put(v.id(), summary(v));
        List<Summary> all = new ArrayList<>(unique.values());
        List<Summary> filtered = all.stream()
                .filter(s -> matches(s, q))
                .filter(s -> outcome == null || outcome.isBlank() || displayOutcome(s).equalsIgnoreCase(outcome))
                .filter(s -> mode == null || mode.isBlank() || s.mode().equalsIgnoreCase(mode))
                .filter(s -> nodeId == null || nodeId.isBlank() || s.nodeId().equalsIgnoreCase(nodeId))
                .filter(s -> from == null || s.startedAt() >= from)
                .filter(s -> to == null || s.startedAt() < to)
                .sorted(summaryComparator(sortBy, direction))
                .toList();

        long total = filtered.size();
        int totalPages = (int) ((total + size - 1) / size);
        int effectivePage = totalPages == 0 ? 0 : Math.min(page, totalPages - 1);
        int start = Math.min(filtered.size(), effectivePage * size);
        int end = Math.min(filtered.size(), start + size);
        List<Summary> items = List.copyOf(filtered.subList(start, end));

        List<Comparison> byNode = comparisons(filtered, Summary::nodeId);
        List<Comparison> byMode = comparisons(filtered, Summary::mode);
        List<Comparison> observedRecovery = byNode.stream().filter(c -> c.recoverySamples() > 0)
                .sorted(Comparator.comparing(Comparison::avgRecoveryMs)).toList();
        String slowest = observedRecovery.size() > 1 ? observedRecovery.getLast().group() : null;
        FilterOptions options = new FilterOptions(
                all.stream().map(SimulationController::displayOutcome).distinct().sorted().toList(),
                all.stream().map(Summary::mode).filter(Objects::nonNull).distinct().sorted().toList(),
                all.stream().map(Summary::nodeId).filter(Objects::nonNull).distinct().sorted().toList());
        return new AnalyticsPage(items, effectivePage, size, total, totalPages, aggregate(filtered),
                trends(filtered), byNode, byMode, slowest, options);
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable("id") String id) {
        try {
            var v = rt.simulation.get(id);
            return new Detail(summary(v), v.events(), v.report(), v.autoRestartAt() == 0 ? null : v.autoRestartAt());
        } catch (NotFoundException live) {
            SimulationRunEntity e = repo.findById(id).orElseThrow(() -> new NotFoundException("Unknown simulation: " + id));
            return new Detail(summary(e), parse(e.eventsJson, new TypeReference<List<SimulationService.SimEvent>>() {}), report(e), null);
        }
    }

    @PostMapping("/{id}/restart-node")
    public SimulationService.RunView restart(@PathVariable("id") String id) { return rt.simulation.restartNode(id); }

    @PostMapping("/{id}/stop")
    public SimulationService.RunView stop(@PathVariable("id") String id) { return rt.simulation.stop(id); }

    @GetMapping("/{id}/report")
    public SimulationService.Report report(@PathVariable("id") String id) {
        Detail d = get(id);
        if (d.report() == null) throw new ConflictException("Report not ready yet (phase " + d.summary().phase() + ").");
        return d.report();
    }

    // ------------------------------------------------------------------ mapping helpers

    private static boolean matches(Summary s, String q) {
        if (q == null || q.isBlank()) return true;
        String x = q.toLowerCase();
        return s.id().toLowerCase().contains(x) || s.nodeId().toLowerCase().contains(x)
                || s.mode().toLowerCase().contains(x) || s.phase().toLowerCase().contains(x)
                || (s.outcome() != null && s.outcome().toLowerCase().contains(x));
    }

    private static String displayOutcome(Summary s) { return s.outcome() == null ? s.phase() : s.outcome(); }

    private static Comparator<Summary> summaryComparator(String sortBy, String direction) {
        Function<Summary, Long> value = switch (sortBy.toLowerCase(Locale.ROOT)) {
            case "detection" -> Summary::detectionMs;
            case "failover" -> Summary::failoverMs;
            case "recovery" -> Summary::recoveryMs;
            default -> Summary::startedAt;
        };
        boolean descending = "desc".equalsIgnoreCase(direction);
        return (left, right) -> {
            Long a = value.apply(left), b = value.apply(right);
            int result;
            if (a == null && b == null) result = 0;
            else if (a == null) result = 1; // unavailable values remain last in either sort direction
            else if (b == null) result = -1;
            else result = Long.compare(a, b) * (descending ? -1 : 1);
            if (result != 0) return result;
            return left.id().compareTo(right.id());
        };
    }

    private record Metric(Double value, int samples) {}

    private static Metric average(List<Summary> rows, Function<Summary, Long> value) {
        double sum = 0;
        int count = 0;
        for (Summary row : rows) {
            Long measurement = value.apply(row);
            if (measurement != null && measurement >= 0) { sum += measurement; count++; }
        }
        return new Metric(count == 0 ? null : sum / count, count);
    }

    static AnalyticsSummary aggregate(List<Summary> rows) {
        List<Summary> completed = rows.stream().filter(s -> "COMPLETED".equals(s.outcome())).toList();
        long failed = rows.stream().filter(SimulationController::isFailed).count();
        Metric detection = average(completed, Summary::detectionMs);
        Metric failover = average(completed, Summary::failoverMs);
        Metric recovery = average(completed, Summary::recoveryMs);
        long requests = 0, succeeded = 0;
        for (Summary row : rows) {
            Long total = row.requestsTotal(), success = row.requestsSucceeded();
            if (validRequestCounts(total, success)) { requests += total; succeeded += success; }
        }
        return new AnalyticsSummary(rows.size(), completed.size(), failed, detection.value(), detection.samples(),
                failover.value(), failover.samples(), recovery.value(), recovery.samples(),
                requests == 0 ? null : 100.0 * succeeded / requests, succeeded, requests);
    }

    private static boolean isFailed(Summary summary) {
        if ("CANCELLED".equalsIgnoreCase(summary.outcome()) || "CANCELLED".equalsIgnoreCase(summary.phase())) return false;
        return "FAILED".equalsIgnoreCase(summary.phase())
                || summary.outcome() != null && !"COMPLETED".equalsIgnoreCase(summary.outcome());
    }

    private static List<TrendPoint> trends(List<Summary> rows) {
        Map<LocalDate, List<Summary>> days = new TreeMap<>();
        for (Summary row : rows) {
            LocalDate date = Instant.ofEpochMilli(row.startedAt()).atZone(ZoneOffset.UTC).toLocalDate();
            days.computeIfAbsent(date, ignored -> new ArrayList<>()).add(row);
        }
        List<TrendPoint> result = new ArrayList<>(days.size());
        for (var entry : days.entrySet()) {
            List<Summary> samples = entry.getValue();
            Metric detection = average(samples, Summary::detectionMs);
            Metric failover = average(samples, Summary::failoverMs);
            Metric recovery = average(samples, Summary::recoveryMs);
            long requests = 0, succeeded = 0;
            int requestSamples = 0;
            for (Summary sample : samples) {
                if (validRequestCounts(sample.requestsTotal(), sample.requestsSucceeded())) {
                    requests += sample.requestsTotal(); succeeded += sample.requestsSucceeded(); requestSamples++;
                }
            }
            Double successPct = requests == 0 ? null : 100.0 * succeeded / requests;
            result.add(new TrendPoint(entry.getKey().toString(), samples.size(), detection.value(), detection.samples(),
                    failover.value(), failover.samples(), recovery.value(), recovery.samples(), successPct,
                    successPct == null ? null : 100.0 - successPct, succeeded, requests, requestSamples));
        }
        return List.copyOf(result);
    }

    private static List<Comparison> comparisons(List<Summary> rows, Function<Summary, String> groupBy) {
        Map<String, List<Summary>> groups = new TreeMap<>();
        for (Summary row : rows) {
            String group = groupBy.apply(row);
            if (group != null && !group.isBlank()) groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(row);
        }
        List<Comparison> result = new ArrayList<>(groups.size());
        for (var entry : groups.entrySet()) {
            List<Summary> values = entry.getValue();
            Metric detection = average(values, Summary::detectionMs);
            Metric failover = average(values, Summary::failoverMs);
            Metric recovery = average(values, Summary::recoveryMs);
            result.add(new Comparison(entry.getKey(), values.size(), detection.value(), detection.samples(),
                    failover.value(), failover.samples(), recovery.value(), recovery.samples()));
        }
        return List.copyOf(result);
    }

    private static boolean validRequestCounts(Long total, Long succeeded) {
        return total != null && succeeded != null && total >= 0 && succeeded >= 0 && succeeded <= total;
    }

    private static Summary summary(SimulationService.RunView v) {
        var r = v.report();
        return new Summary(v.id(), v.nodeId(), v.mode().name(), v.phase().name(), r == null ? null : r.outcome(), v.startedAt(),
                v.endedAt() == 0 ? null : v.endedAt(), r == null ? null : r.detectionMs(), r == null ? null : r.failoverMs(),
                r == null ? null : r.recoveryMs(), r == null ? null : r.requestsTotal(),
                r == null ? null : r.requestsSucceeded(), r == null ? null : r.requestsFailed(), r == null ? null : r.errorRatePct());
    }

    private Summary summary(SimulationRunAnalyticsProjection e) {
        JsonNode report = readTree(e.getReportJson());
        Long detection = legacyUnavailable(jsonLong(report, "detectionMs", e.getDetectionMs()), jsonLong(report, "failureDetectedAt", 0L));
        Long failover = legacyUnavailable(jsonLong(report, "failoverMs", e.getFailoverMs()), jsonLong(report, "failoverCompletedAt", 0L));
        Long recovery = legacyUnavailable(jsonLong(report, "recoveryMs", e.getRecoveryMs()), jsonLong(report, "recoveryCompletedAt", 0L));
        Long requestsTotal = jsonLong(report, "requestsTotal", e.getRequestsTotal());
        Long requestsSucceeded = jsonLong(report, "requestsSucceeded", null);
        Long requestsFailed = jsonLong(report, "requestsFailed", null);
        Double errorRate = requestsTotal == null || requestsTotal == 0 ? null
                : jsonDouble(report, "errorRatePct", e.getErrorRatePct());
        return new Summary(e.getId(), e.getNodeId(), e.getMode(), e.getPhase(), e.getOutcome(), e.getStartedAt(),
                e.getEndedAt(), detection, failover, recovery, requestsTotal, requestsSucceeded, requestsFailed, errorRate);
    }

    private JsonNode readTree(String json) {
        if (json == null) return mapper.createObjectNode();
        try { return mapper.readTree(json); } catch (Exception ex) { return mapper.createObjectNode(); }
    }

    private static Long jsonLong(JsonNode source, String field, Long fallback) {
        JsonNode value = source.get(field);
        return value == null ? fallback : value.isNumber() ? value.longValue() : null;
    }

    private static Double jsonDouble(JsonNode source, String field, Double fallback) {
        JsonNode value = source.get(field);
        return value == null ? fallback : value.isNumber() ? value.doubleValue() : null;
    }

    private Summary summary(SimulationRunEntity e) {
        SimulationService.Report r = report(e);
        Long requestsTotal = r == null || r.requestsTotal() == null ? e.requestsTotal : r.requestsTotal();
        Double errorRatePct = requestsTotal == null || requestsTotal == 0 ? null
                : r == null ? e.errorRatePct : r.errorRatePct();
        return new Summary(e.id, e.nodeId, e.mode, e.phase, e.outcome, e.startedAt, e.endedAt,
                r == null ? e.detectionMs : r.detectionMs(), r == null ? e.failoverMs : r.failoverMs(),
                r == null ? e.recoveryMs : r.recoveryMs(),
                requestsTotal,
                r == null ? null : r.requestsSucceeded(), r == null ? null : r.requestsFailed(),
                errorRatePct);
    }

    SimulationService.Report report(SimulationRunEntity e) {
        if (e.reportJson == null) return null;
        try {
            SimulationService.Report r = mapper.readValue(e.reportJson, SimulationService.Report.class);
            Long requestsTotal = r.requestsTotal() == null ? e.requestsTotal : r.requestsTotal();
            Double errorRatePct = requestsTotal == null || requestsTotal == 0 ? null : r.errorRatePct();
            // Older reports used zero as the missing-value sentinel. Timestamp presence distinguishes a genuine
            // measured zero from a duration that was never observed, without changing the database schema.
            return new SimulationService.Report(r.simulationId(), r.failedNode(), r.mode(), r.outcome(),
                    r.failureStartedAt(), r.failureDetectedAt(), r.failoverStartedAt(), r.failoverCompletedAt(),
                    r.restartedAt(), r.recoveryStartedAt(), r.recoveryCompletedAt(),
                    legacyUnavailable(r.detectionMs(), r.failureDetectedAt()),
                    legacyUnavailable(r.failoverMs(), r.failoverCompletedAt()),
                    legacyUnavailable(r.recoveryMs(), r.recoveryCompletedAt()),
                    legacyUnavailable(r.syncMs(), r.recoveryCompletedAt()),
                    requestsTotal, r.requestsSucceeded(), r.requestsFailed(), errorRatePct,
                    r.requestsServedByFallback(), r.keysPromoted(), r.reReplicated(), r.replicationSent(),
                    r.replicationAcked(), r.replicationFailed(), r.averageLatencyMs(), r.p95LatencyMs(),
                    r.p99LatencyMs(), r.latencySamples(), r.requestsPerSecond(), r.affectedKeys(),
                    r.recoverySucceeded(), r.finalNodeStatus(), r.consistency(), r.notes());
        }
        catch (Exception ex) { return null; }
    }

    static Long legacyUnavailable(Long duration, long completionTimestamp) {
        return duration != null && duration == 0 && completionTimestamp == 0 ? null : duration;
    }

    private <T> T parse(String json, TypeReference<T> type) {
        try { return json == null ? null : mapper.readValue(json, type); } catch (Exception ex) { return null; }
    }
}
