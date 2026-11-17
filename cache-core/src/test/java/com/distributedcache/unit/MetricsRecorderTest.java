package com.distributedcache.unit;

import com.distributedcache.monitoring.MetricsRecorder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricsRecorderTest {
    @Test
    void recordsMeasuredOperationBreakdownAndPerNodeBuckets() {
        MetricsRecorder metrics = new MetricsRecorder();
        metrics.record(MetricsRecorder.Op.GET, true, true, 1, 2_000_000, "node-a", "k1", false);
        metrics.record(MetricsRecorder.Op.GET, true, false, 1, 4_000_000, "node-b", "k2", false);
        metrics.record(MetricsRecorder.Op.SET, true, null, 1, 1_000_000, "node-a", "k3", false);
        metrics.record(MetricsRecorder.Op.UPDATE, true, null, 1, 3_000_000, "node-a", "k3", false);
        metrics.record(MetricsRecorder.Op.DELETE, false, null, 1, 5_000_000, null, "k4", false);

        var points = metrics.timeline(5);
        assertEquals(5, points.stream().mapToLong(MetricsRecorder.TimePoint::requests).sum());
        assertEquals(1, points.stream().mapToLong(MetricsRecorder.TimePoint::hits).sum());
        assertEquals(1, points.stream().mapToLong(MetricsRecorder.TimePoint::misses).sum());
        assertEquals(1, points.stream().mapToLong(MetricsRecorder.TimePoint::failures).sum());
        assertEquals(2, points.stream().mapToLong(MetricsRecorder.TimePoint::getRequests).sum());
        assertEquals(1, points.stream().mapToLong(MetricsRecorder.TimePoint::setRequests).sum());
        assertEquals(1, points.stream().mapToLong(MetricsRecorder.TimePoint::updateRequests).sum());
        assertEquals(1, points.stream().mapToLong(MetricsRecorder.TimePoint::deleteRequests).sum());
        assertEquals(3, points.stream().mapToLong(p -> p.requestsByNode().getOrDefault("node-a", 0L)).sum());
        assertEquals(1, points.stream().mapToLong(p -> p.requestsByNode().getOrDefault("node-b", 0L)).sum());
        assertTrue(points.stream().anyMatch(p -> p.avgLatencyMs() > 0));
    }

    @Test
    void retainsOneHourOfPerSecondMeasurementBuckets() {
        MetricsRecorder metrics = new MetricsRecorder();
        assertEquals(3600, metrics.timeline(3600).size());
    }

    @Test
    void hotKeyStatisticsCountKeyedGetsOnlyAndReportBoundedCoverage() {
        MetricsRecorder metrics = new MetricsRecorder();
        metrics.record(MetricsRecorder.Op.SET, true, null, 1, 1_000_000, "node-a", "same", false);
        metrics.record(MetricsRecorder.Op.UPDATE, true, null, 1, 1_000_000, "node-a", "same", false);
        metrics.record(MetricsRecorder.Op.GET, true, true, 1, 1_000_000, "node-a", "same", false);
        metrics.record(MetricsRecorder.Op.GET, false, null, 1, 1_000_000, null, "missing", false);

        var stats = metrics.hotKeyStats();
        assertEquals(2, stats.readAccesses());
        assertEquals(2, stats.trackedReadAccesses());
        assertEquals(2, stats.trackedDistinctKeys());
        assertEquals(5000, stats.trackingLimit());
        assertFalse(stats.trackingLimitReached());
        assertEquals(2, metrics.hotKeys(10).stream().mapToLong(MetricsRecorder.HotKey::count).sum());

        for (int i = 0; i < stats.trackingLimit() + 1; i++) {
            metrics.record(MetricsRecorder.Op.GET, true, true, 1, 1_000_000, "node-a", "key-" + i, false);
        }
        var overflow = metrics.hotKeyStats();
        assertTrue(overflow.trackingLimitReached());
        assertEquals(5000, overflow.trackedDistinctKeys());
        assertEquals(5003, overflow.readAccesses());
        assertEquals(5000, overflow.trackedReadAccesses());
    }
}
