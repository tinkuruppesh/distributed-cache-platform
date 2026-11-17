package com.distributedcache.persistence;

/** Narrow persisted projection for analytics; avoids loading each record's potentially large event history. */
public interface SimulationRunAnalyticsProjection {
    String getId();
    String getNodeId();
    String getMode();
    String getPhase();
    String getOutcome();
    long getStartedAt();
    Long getEndedAt();
    Long getDetectionMs();
    Long getFailoverMs();
    Long getRecoveryMs();
    Long getRequestsTotal();
    Double getErrorRatePct();
    String getReportJson();
}
