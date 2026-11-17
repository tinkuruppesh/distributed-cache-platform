package com.distributedcache.persistence;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, Long> {
    @Query("select a from AuditLogEntity a where "
            + "(:q is null or :q = '' or lower(a.username) like lower(concat('%', :q, '%')) "
            + "or lower(a.action) like lower(concat('%', :q, '%')) or lower(coalesce(a.target, '')) like lower(concat('%', :q, '%'))) "
            + "and (:action is null or :action = '' or lower(a.action) like lower(concat('%', :action, '%'))) "
            + "and (:status is null or :status = '' or (:status = 'SUCCESS' and upper(a.result) like 'SUCCESS%') "
            + "or (:status = 'FAILED' and (upper(a.result) like 'FAILED%' or upper(a.result) like 'DENIED%')) "
            + "or (:status = 'INFO' and upper(a.result) like 'INFO%') "
            + "or (:status = 'WARNING' and upper(a.result) like 'WARNING%') "
            + "or (:status = 'CRITICAL' and upper(a.result) like 'CRITICAL%')) "
            + "and (:fromTime is null or a.createdAt >= :fromTime) and (:toTime is null or a.createdAt < :toTime)")
    Page<AuditLogEntity> search(@Param("q") String q, @Param("action") String action,
                                @Param("status") String status, @Param("fromTime") Long fromTime,
                                @Param("toTime") Long toTime, Pageable pageable);
}
