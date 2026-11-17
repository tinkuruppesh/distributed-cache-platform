package com.distributedcache.persistence;

import jakarta.persistence.*;

@Entity
@Table(name = "simulation_runs")
public class SimulationRunEntity {
    @Id public String id;
    @Column(nullable = false) public String nodeId;
    @Column(nullable = false) public String mode;
    @Column(nullable = false) public String phase;
    public String outcome;
    @Column(nullable = false) public long startedAt;
    public Long endedAt;
    public Long detectionMs;
    public Long failoverMs;
    public Long recoveryMs;
    public Long requestsTotal;
    public Long requestsSucceeded;
    public Long requestsFailed;
    public Long fallbackRequests;
    public String recoveryOutcome;
    /** JSON node IDs, because a run can promote different replicas for different keys. */
    @Column(length = 100000) public String originalPrimaryOwnersJson;
    @Column(length = 100000) public String promotedReplicasJson;
    public Double errorRatePct;
    @Column(length = 100000) public String reportJson;
    @Column(length = 100000) public String eventsJson;
    @Column(nullable = false) public long createdAt;
    @Column(nullable = false) public long updatedAt;
}
