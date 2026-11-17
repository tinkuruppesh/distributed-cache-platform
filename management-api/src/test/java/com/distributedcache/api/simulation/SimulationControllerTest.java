package com.distributedcache.api.simulation;

import com.distributedcache.persistence.SimulationRunEntity;
import com.distributedcache.simulation.SimulationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import java.util.List;

class SimulationControllerTest {
    private final SimulationController controller = new SimulationController(null, null, new ObjectMapper());

    @Test
    void legacyZeroWithoutCompletionTimestampMeansUnavailable() {
        assertNull(SimulationController.legacyUnavailable(0L, 0));
    }

    @Test
    void measuredZeroWithCompletionTimestampRemainsZero() {
        assertEquals(0L, SimulationController.legacyUnavailable(0L, 1_735_000_000_000L));
    }

    @Test
    void nullMeasurementRemainsUnavailable() {
        assertNull(SimulationController.legacyUnavailable(null, 1_735_000_000_000L));
    }

    @Test
    void savedReportKeepsMeasuredZeroButNormalizesUnavailableLegacyDurations() {
        SimulationRunEntity run = new SimulationRunEntity();
        run.requestsTotal = 100L;
        run.reportJson = """
                {"simulationId":"fixture","failedNode":"node-1","mode":"ISOLATE","outcome":"COMPLETED",
                 "failureStartedAt":100,"failureDetectedAt":101,"failoverStartedAt":102,"failoverCompletedAt":0,
                 "restartedAt":0,"recoveryStartedAt":0,"recoveryCompletedAt":0,
                 "detectionMs":0,"failoverMs":0,"recoveryMs":0,"syncMs":0,
                 "requestsTotal":100,"requestsSucceeded":95,"requestsFailed":5,"errorRatePct":5.0,
                 "requestsPerSecond":1.0}
                """;

        SimulationService.Report report = controller.report(run);
        assertEquals(0L, report.detectionMs(), "a zero-duration detection with a recorded timestamp is measured");
        assertNull(report.failoverMs(), "zero without a failover completion timestamp was the legacy unavailable sentinel");
        assertNull(report.recoveryMs());
        assertNull(report.syncMs());
        assertEquals(100L, report.requestsTotal());
        assertEquals(95L, report.requestsSucceeded());
        assertEquals(5L, report.requestsFailed());
    }

    @Test
    void savedReportDoesNotRepresentZeroRequestsAsZeroErrorRate() {
        SimulationRunEntity run = new SimulationRunEntity();
        run.requestsTotal = 0L;
        run.reportJson = """
                {"simulationId":"empty","failedNode":"node-1","mode":"ISOLATE","outcome":"COMPLETED",
                 "requestsTotal":0,"requestsSucceeded":0,"requestsFailed":0,"errorRatePct":0.0}
                """;

        SimulationService.Report report = controller.report(run);
        assertEquals(0L, report.requestsTotal());
        assertEquals(0L, report.requestsSucceeded());
        assertNull(report.errorRatePct(), "an error rate has no denominator when no requests were measured");
    }

    @Test
    void savedReportLeavesMissingRequestCountersUnavailable() {
        SimulationRunEntity run = new SimulationRunEntity();
        run.reportJson = """
                {"simulationId":"missing","failedNode":"node-1","mode":"ISOLATE","outcome":"COMPLETED"}
                """;

        SimulationService.Report report = controller.report(run);
        assertNull(report.requestsTotal());
        assertNull(report.requestsSucceeded());
        assertNull(report.errorRatePct());
    }

    @Test
    void analyticsCountsFailedButDoesNotReclassifyCancelledRuns() {
        var completed = new SimulationController.Summary("done", "node-1", "ISOLATE", "COMPLETED", "COMPLETED",
                1, 2L, 5L, 10L, 20L, 100L, 98L, 2L, 2.0);
        var failed = new SimulationController.Summary("failed", "node-2", "CRASH", "FAILED", "RECOVERY_NOT_COMPLETED",
                2, 3L, 7L, null, null, null, null, null, null);
        var cancelled = new SimulationController.Summary("cancelled", "node-3", "ISOLATE", "CANCELLED", "CANCELLED",
                3, 4L, 9L, null, null, null, null, null, null);

        var result = SimulationController.aggregate(List.of(completed, failed, cancelled));

        assertEquals(3, result.totalSimulations());
        assertEquals(1, result.completedSimulations());
        assertEquals(1, result.failedSimulations());
        assertEquals(1, result.detectionSamples());
        assertEquals(98.0, result.requestSuccessPct());
    }
}
