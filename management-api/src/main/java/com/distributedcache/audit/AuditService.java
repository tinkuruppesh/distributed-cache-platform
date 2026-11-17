package com.distributedcache.audit;

import com.distributedcache.persistence.AuditLogEntity;
import com.distributedcache.persistence.AuditLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private final AuditLogRepository repo;

    public AuditService(AuditLogRepository repo) { this.repo = repo; }

    public void record(String username, String action, String target, String result, String ip) {
        String description = action == null ? "Administrative action" : action;
        if (target != null && !target.isBlank()) description += " on " + target;
        record(username, action, target, result, ip, description, null);
    }

    public void record(String username, String action, String target, String result, String ip,
                       String description, String details) {
        try {
            AuditLogEntity e = new AuditLogEntity();
            e.username = username == null ? "anonymous" : username;
            e.action = action.length() > 200 ? action.substring(0, 200) : action;
            e.target = target != null && target.length() > 300 ? target.substring(0, 300) : target;
            e.description = truncate(description, 1000);
            e.details = truncate(details, 4000);
            e.result = truncate(result == null ? "UNKNOWN" : result, 40);
            e.ipAddress = ip;
            e.createdAt = System.currentTimeMillis();
            repo.save(e);
        } catch (RuntimeException ex) {
            log.error("Audit write failed for action '{}' and target '{}'", action, target, ex);
        }
    }

    public Page<AuditLogEntity> search(String q, String action, String status, Long from, Long to, int page, int size) {
        String normalizedStatus = status == null || status.isBlank() ? null : status.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalizedStatus != null && !java.util.Set.of("SUCCESS", "FAILED", "INFO", "WARNING", "CRITICAL").contains(normalizedStatus)) {
            throw new com.distributedcache.exception.InvalidRequestException("status must be SUCCESS, FAILED, INFO, WARNING, or CRITICAL.");
        }
        if (from != null && to != null && from >= to) {
            throw new com.distributedcache.exception.InvalidRequestException("from must be earlier than to.");
        }
        return repo.search(blankToNull(q), blankToNull(action), normalizedStatus, from, to,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))));
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String truncate(String s, int max) { return s == null || s.length() <= max ? s : s.substring(0, max); }
}
