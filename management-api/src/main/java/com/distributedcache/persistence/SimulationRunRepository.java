package com.distributedcache.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SimulationRunRepository extends JpaRepository<SimulationRunEntity, String> {
    @Query("select s.id as id, s.nodeId as nodeId, s.mode as mode, s.phase as phase, s.outcome as outcome, "
            + "s.startedAt as startedAt, s.endedAt as endedAt, s.detectionMs as detectionMs, "
            + "s.failoverMs as failoverMs, s.recoveryMs as recoveryMs, s.requestsTotal as requestsTotal, "
            + "s.errorRatePct as errorRatePct, s.reportJson as reportJson from SimulationRunEntity s")
    List<SimulationRunAnalyticsProjection> findAllForAnalytics();

    @Query("select s from SimulationRunEntity s where :q is null or :q = '' or lower(s.id) like lower(concat('%', :q, '%')) "
            + "or lower(s.nodeId) like lower(concat('%', :q, '%')) or lower(s.outcome) like lower(concat('%', :q, '%')) "
            + "order by s.startedAt desc")
    List<SimulationRunEntity> search(@Param("q") String q);
}
